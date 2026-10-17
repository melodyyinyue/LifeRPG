package com.lifereplay.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;

public class VoiceRecorder {
    public interface Listener {
        void onLevel(int seconds);
        void onStopped(byte[] pcm);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_SECONDS = 45;
    private static final int MAX_SAMPLES = SAMPLE_RATE * MAX_SECONDS;
    private static final int MIN_SAMPLES = SAMPLE_RATE * 3 / 10;
    private static final int READ_SAMPLES = SAMPLE_RATE / 10;
    private final Listener listener;
    private volatile Session active;

    public VoiceRecorder(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("缺少录音回调");
        }
        this.listener = listener;
    }

    public synchronized void start() throws Exception {
        if (active != null) {
            throw new Exception("录音尚未停止，请稍后再试");
        }
        AudioRecord recorder = null;
        boolean handedOff = false;
        try {
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                throw new IllegalStateException();
            }
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, READ_SAMPLES * 4));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException();
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException();
            }
            Session session = new Session(recorder);
            Thread worker = new Thread(() -> record(session), "LifeReplay-Recorder");
            active = session;
            worker.start();
            handedOff = true;
        } catch (SecurityException ignored) {
            throw new Exception("无法使用麦克风，请授予录音权限后重试");
        } catch (Exception ignored) {
            throw new Exception("录音初始化失败，请检查麦克风是否被占用");
        } finally {
            if (!handedOff) {
                if (recorder != null) {
                    release(recorder);
                }
                active = null;
            }
        }
    }

    public void stop() {
        synchronized (this) {
            if (active != null) {
                // A blocking read requests only 100 ms of PCM. Never join on the UI thread.
                active.stopRequested = true;
            }
        }
    }

    public void cancel() {
        synchronized (this) {
            if (active != null) {
                active.cancelled = true;
                active.stopRequested = true;
            }
        }
    }

    public boolean isRecording() {
        // Remains true throughout stopping and resource release.
        return active != null;
    }

    private void record(Session session) {
        byte[] result = null;
        String error = null;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(MAX_SAMPLES * 2);
            short[] samples = new short[READ_SAMPLES];
            byte[] bytes = new byte[READ_SAMPLES * 2];
            int totalSamples = 0;
            int reportedSeconds = 0;
            while (!session.stopRequested && totalSamples < MAX_SAMPLES
                    && SystemClock.elapsedRealtime() - session.startedAt < MAX_SECONDS * 1000L) {
                int count = session.recorder.read(samples, 0,
                        Math.min(samples.length, MAX_SAMPLES - totalSamples),
                        AudioRecord.READ_BLOCKING);
                if (session.cancelled) {
                    break;
                }
                if (count <= 0) {
                    if (!session.stopRequested) {
                        error = "无法读取麦克风音频，请检查设备后重试";
                    }
                    break;
                }
                // The ASR transport requires little-endian PCM16, without a WAV header.
                for (int i = 0; i < count; i++) {
                    bytes[i * 2] = (byte) samples[i];
                    bytes[i * 2 + 1] = (byte) (samples[i] >> 8);
                }
                output.write(bytes, 0, count * 2);
                totalSamples += count;
                int seconds = (int) Math.min(MAX_SECONDS,
                        (SystemClock.elapsedRealtime() - session.startedAt) / 1000L);
                while (reportedSeconds < seconds && !session.stopRequested) {
                    notifyLevel(++reportedSeconds);
                }
            }
            if (!session.cancelled && error == null) {
                if (totalSamples == 0) {
                    error = "未录到音频，请重试";
                } else if (totalSamples < MIN_SAMPLES) {
                    error = "录音过短，请至少录制 0.3 秒";
                } else {
                    result = output.toByteArray();
                }
            }
        } catch (SecurityException ignored) {
            error = "麦克风权限不可用，请重新授予录音权限";
        } catch (RuntimeException ignored) {
            error = "录音失败，请检查麦克风后重试";
        } finally {
            release(session.recorder);
            boolean deliver;
            synchronized (this) {
                // Completion is the cancellation cutoff; callbacks never hold the state lock.
                deliver = !session.cancelled;
                active = null;
            }
            if (deliver) {
                try {
                    if (error != null) {
                        listener.onError(error);
                    } else if (result != null) {
                        listener.onStopped(result);
                    }
                } catch (RuntimeException ignored) {
                    // A listener failure must not retain AudioRecord or expose PCM in logs.
                }
            }
        }
    }

    private void notifyLevel(int seconds) {
        try {
            listener.onLevel(seconds);
        } catch (RuntimeException ignored) {
            // UI callbacks do not own the recording resource.
        }
    }

    private static void release(AudioRecord recorder) {
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } catch (RuntimeException ignored) {
            // Release still runs when stop fails, including permission revocation.
        } finally {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
                // No provider exception or recorded data is logged.
            }
        }
    }

    private static final class Session {
        final AudioRecord recorder;
        final long startedAt = SystemClock.elapsedRealtime();
        volatile boolean stopRequested;
        volatile boolean cancelled;

        Session(AudioRecord recorder) {
            this.recorder = recorder;
        }
    }
}
