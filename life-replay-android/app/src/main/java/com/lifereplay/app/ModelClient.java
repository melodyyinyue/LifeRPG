package com.lifereplay.app;

import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

/** 可选的兼容协议传输层；这些协议并不代表已经验证 MP 兼容性。 */
public class ModelClient {
    public static class Config {
        public String textUrl = "https://api.stepfun.com/v1/chat/completions";
        public String audioUrl = "https://api.stepfun.com/v1/audio/generate";
        public String asrUrl = "https://api.stepfun.com/v1/audio/asr/sse";
        public String key = "";
        public String audioKey = "";
        public String textModel = "step-3.5-flash";
        public String audioModel = "stepaudio-3-gen-preview";
        public String asrModel = "stepaudio-3-asr-max";
    }

    public interface Trace {
        void onCall(String capability, String model, boolean success, long millis);
    }

    private static final int TEXT_LIMIT = 2 * 1024 * 1024;
    private static final int AUDIO_LIMIT = 20 * 1024 * 1024;
    private static final int PCM_LIMIT = 2 * 1024 * 1024;
    private static final String INSTRUCTION =
            "严格按照 scripts 中的角色和台词表演，不新增、删改或复述台词。方括号内容用于生成环境音，"
                    + "圆括号内容用于指导表演，两者均不朗读。人声清晰，不使用音乐覆盖对白。";

    private final Config config;
    private final Trace trace;
    private final Object connectionLock = new Object();
    private final Set<HttpsURLConnection> connections = new HashSet<>();
    private long cancelGeneration;

    /** 复制配置；修改凭据或路由后需要创建新客户端。 */
    public ModelClient(Config config, Trace trace) {
        if (config == null) {
            throw new IllegalArgumentException("缺少模型配置");
        }
        this.config = new Config();
        this.config.textUrl = normalizeEndpoint(config.textUrl);
        this.config.audioUrl = config.audioUrl;
        this.config.asrUrl = config.asrUrl;
        this.config.key = config.key;
        this.config.audioKey = config.audioKey;
        this.config.textModel = config.textModel;
        this.config.audioModel = config.audioModel;
        this.config.asrModel = config.asrModel;
        this.trace = trace;
    }

    /** 将标准 OpenAI Base URL 转成端点；已有完整端点及自定义路由不改写。 */
    public static String normalizeEndpoint(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) {
            return new Config().textUrl;
        }
        try {
            URI uri = endpointUri(input);
            String path = uri.getRawPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String suffix = "/chat/completions";
            if (path.isEmpty()) {
                path = "/v1" + suffix;
            } else if (path.endsWith("/v1")) {
                path += suffix;
            } else if (!path.endsWith(suffix)) {
                return input;
            }
            return uri.getScheme() + "://" + uri.getRawAuthority() + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception ignored) {
            // 不修复不安全输入；请求时仍由同一 HTTPS 校验拒绝，且不回显地址。
            return input;
        }
    }

    private static URI endpointUri(String input) throws Exception {
        URI uri = new URI(input == null ? "" : input.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getPort() == 0
                || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("端点必须是无用户凭据及片段的完整 HTTPS URL");
        }
        return uri;
    }

    public static boolean isOfficialTextEndpoint(String input) {
        try {
            URI uri = endpointUri(input);
            return "api.stepfun.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception ignored) {
            return false;
        }
    }

    public JSONObject chat(String system, String user) throws Exception {
        return perform("text", config.textModel, call -> {
            if (system == null || user == null) {
                throw failure(call, "缺少对话内容");
            }
            boolean official = isOfficialTextEndpoint(config.textUrl);
            JSONObject request = new JSONObject()
                    .put("model", requireModel(call, config.textModel))
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", official
                                    ? system + "\n只输出一个完整的 JSON 对象，遵守上述格式示例。" : system))
                            .put(new JSONObject().put("role", "user").put("content", user)))
                    .put("stream", false);
            if (official) {
                request.put("response_format", new JSONObject().put("type", "json_object"))
                        .put("max_tokens", 8192);
            }
            return post(call, config.textUrl, official ? audioKey(call) : config.key,
                    request, "application/json", connection -> {
                JSONObject response = readJson(call, connection);
                observeModel(call, response);
                JSONArray choices = response.optJSONArray("choices");
                JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                if (choice == null) {
                    throw failure(call, "文本响应缺少 choices[0]，请检查模型是否支持 Chat Completions");
                }
                Object finishReason = choice.opt("finish_reason");
                if ("length".equals(finishReason)) {
                    throw failure(call, "模型输出达到长度限制，故事不完整；请缩短输入或减少生成内容后重试");
                }
                if ("content_filter".equals(finishReason) || "refusal".equals(finishReason)) {
                    throw failure(call, "模型拒绝生成或内容被过滤，请调整输入后重试");
                }
                if (finishReason != null && finishReason != JSONObject.NULL
                        && !"stop".equals(finishReason)) {
                    throw failure(call, "模型未正常结束文本生成（可能请求了工具调用），未生成完整故事");
                }
                JSONObject message = choice.optJSONObject("message");
                if (message == null) {
                    throw failure(call, "未知文本响应格式，缺少 message 对象");
                }
                Object refusal = message.opt("refusal");
                if (refusal != null && refusal != JSONObject.NULL && !"".equals(refusal)) {
                    throw failure(call, "模型拒绝生成，请调整输入后重试");
                }
                String content = chatContent(call, message.opt("content")).trim();
                if (content.isEmpty()) {
                    throw failure(call, "模型返回空文本，未生成故事");
                }
                // 仅接受完整的外层 JSON 代码块，绝不从任意文本中截取内部对象。
                if (content.startsWith("```")) {
                    int lineEnd = content.indexOf('\n');
                    if (lineEnd < 0 || !content.endsWith("```")) {
                        throw failure(call, "文本响应不是完整 JSON");
                    }
                    String fence = content.substring(0, lineEnd).trim();
                    if (!fence.equals("```json") && !fence.equals("```JSON")
                            && !fence.equals("```")) {
                        throw failure(call, "文本响应不是 JSON 代码块");
                    }
                    content = content.substring(lineEnd + 1, content.length() - 3).trim();
                }
                return parseObject(call, content);
            });
        });
    }

    private String chatContent(Call call, Object content) throws SafeFailure {
        if (content instanceof String) {
            return (String) content;
        }
        if (!(content instanceof JSONArray)) {
            throw failure(call, "未知文本响应格式，message.content 必须是字符串或文本内容数组");
        }
        JSONArray parts = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && "refusal".equals(part.optString("type"))) {
                throw failure(call, "模型拒绝生成，请调整输入后重试");
            }
            if (part == null || !("text".equals(part.optString("type"))
                    || "output_text".equals(part.optString("type")))
                    || !(part.opt("text") instanceof String)) {
                throw failure(call, "文本内容数组包含不支持的内容，无法确认完整故事");
            }
            text.append((String) part.opt("text"));
        }
        return text.toString();
    }

    public byte[] speak(JSONArray roles, JSONArray scripts) throws Exception {
        return speak(roles, scripts, INSTRUCTION);
    }

    public byte[] speak(JSONArray roles, JSONArray scripts, String instruction) throws Exception {
        return perform("audio", config.audioModel, call -> {
            validateSpeech(call, roles, scripts);
            if (instruction == null || instruction.trim().isEmpty() || instruction.length() > 500) {
                throw failure(call, "音频指令必须非空且不超过 500 字符");
            }
            JSONObject request = new JSONObject()
                    .put("model", requireModel(call, config.audioModel))
                    .put("task", "text_to_audio")
                    .put("roles", roles).put("scripts", scripts)
                    .put("instruction", instruction)
                    .put("response_format", "mp3").put("stream_format", "audio");
            return post(call, config.audioUrl, audioKey(call), request, "audio/mpeg", connection -> {
                String type = mediaType(connection);
                if (!(type.startsWith("audio/") || type.equals("application/octet-stream"))) {
                    throw failure(call, "音频响应 Content-Type 无效或返回了 JSON 错误");
                }
                byte[] bytes = readBody(call, connection, AUDIO_LIMIT);
                if (looksLikeJson(bytes) || !looksLikeMp3(bytes)) {
                    throw failure(call, "音频响应不是非空 MP3，可能为错误响应");
                }
                return bytes;
            });
        });
    }

    public String transcribe(byte[] pcm, Consumer<String> delta) throws Exception {
        return perform("asr", config.asrModel, call -> {
            if (pcm == null || pcm.length == 0 || pcm.length > PCM_LIMIT || pcm.length % 2 != 0) {
                throw failure(call, "录音必须为非空 PCM16，且不超过 2MB");
            }
            JSONObject transcription = new JSONObject()
                    .put("model", requireModel(call, config.asrModel)).put("language", "zh")
                    .put("enable_itn", true).put("enable_timestamp", false);
            JSONObject format = new JSONObject().put("type", "pcm").put("codec", "pcm_s16le")
                    .put("rate", 16000).put("bits", 16).put("channel", 1);
            JSONObject request = new JSONObject().put("audio", new JSONObject()
                    .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    .put("input", new JSONObject().put("transcription", transcription)
                            .put("format", format)));
            return post(call, config.asrUrl, audioKey(call), request, "text/event-stream", connection -> {
                if (!mediaType(connection).equals("text/event-stream")) {
                    throw failure(call, "ASR 响应不是 SSE 流");
                }
                return readTranscript(call, connection, delta);
            });
        });
    }

    /** 取消当前进行中的操作，之后新发起的操作仍可使用。 */
    public void cancelAll() {
        List<HttpsURLConnection> pending;
        synchronized (connectionLock) {
            cancelGeneration++;
            pending = new ArrayList<>(connections);
            connections.clear();
        }
        for (HttpsURLConnection connection : pending) {
            connection.disconnect();
        }
    }

    private String audioKey(Call call) throws SafeFailure {
        if (config.audioKey == null || config.audioKey.trim().isEmpty()) {
            throw failure(call, "缺少 Step Key，请在设置中填写 Step Key（文本 / 配音 / 识别共用）；不会使用代理 Key 替代");
        }
        return config.audioKey;
    }

    private void validateSpeech(Call call, JSONArray roles, JSONArray scripts) throws Exception {
        if (roles == null || scripts == null || scripts.length() == 0) {
            throw failure(call, "必须提供角色数组及非空台词数组");
        }
        long roleLength = 0;
        Set<String> names = new HashSet<>();
        for (int i = 0; i < roles.length(); i++) {
            JSONObject role = roles.optJSONObject(i);
            if (role == null || !(role.opt("name") instanceof String)
                    || !(role.opt("description") instanceof String)) {
                throw failure(call, "角色必须包含 name 与 description 字符串");
            }
            String name = role.getString("name");
            if (name.trim().isEmpty() || !names.add(name)) {
                throw failure(call, "角色名称为空或重复");
            }
            roleLength += name.length() + (long) role.getString("description").length();
        }
        if (roleLength > 500) {
            throw failure(call, "所有角色 name 与 description 合计超过 500 字符");
        }
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject script = scripts.optJSONObject(i);
            if (script == null || !(script.opt("text") instanceof String)
                    || script.getString("text").trim().isEmpty()) {
                throw failure(call, "台词必须包含非空 text 字符串");
            }
            if (script.has("speaker")) {
                if (!(script.opt("speaker") instanceof String)
                        || script.getString("speaker").trim().isEmpty()
                        || !names.contains(script.getString("speaker"))) {
                    throw failure(call, "台词 speaker 必须为非空字符串且对应已声明角色");
                }
            } else {
                String sound = script.getString("text").trim();
                if (!sound.startsWith("[") || !sound.endsWith("]")
                        || sound.substring(1, sound.length() - 1).trim().isEmpty()) {
                    throw failure(call, "无 speaker 的音效必须为非空方括号环境描述");
                }
            }
        }
        // 保守计入完整序列化内容，包括字段名与标点。
        String serializedScripts = scripts.toString();
        if (serializedScripts == null || serializedScripts.length() > 1000) {
            throw failure(call, "台词全部字段保守计数超过 1000 字符，请按完整台词分段");
        }
    }

    private <T> T perform(String capability, String route, Operation<T> operation) throws Exception {
        Call call;
        synchronized (connectionLock) {
            call = new Call(capability, "route:" + safeModel(route), cancelGeneration);
        }
        boolean success = false;
        try {
            checkCancelled(call);
            T result = operation.run(call);
            checkCancelled(call);
            success = true;
            return result;
        } catch (SafeFailure error) {
            throw error;
        } catch (Exception error) {
            // 仅按异常类型分类，不透传异常原文、地址或响应正文。
            checkCancelled(call);
            throw failure(call, safeError(error));
        } finally {
            if (call.attempted && trace != null) {
                long millis = Math.max(0L, (System.nanoTime() - call.startedNanos) / 1_000_000L);
                try {
                    trace.onCall(capability, call.model, success, millis);
                } catch (RuntimeException ignored) {
                    // 观测回调不能改变操作结果。
                }
            }
        }
    }

    private <T> T post(Call call, String url, String key, JSONObject body, String accept,
                       Response<T> response) throws Exception {
        requireKey(call, key);
        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection = open(call, url);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", accept);
            connection.setFixedLengthStreamingMode(request.length);
            checkCancelled(call);
            call.startedNanos = System.nanoTime();
            call.attempted = true;
            try (OutputStream output = connection.getOutputStream()) {
                for (int offset = 0; offset < request.length; ) {
                    checkCancelled(call);
                    int count = Math.min(8192, request.length - offset);
                    output.write(request, offset, count);
                    offset += count;
                }
            }
            checkStatus(call, connection);
            return response.read(connection);
        } finally {
            close(connection);
        }
    }

    private HttpsURLConnection open(Call call, String input) throws Exception {
        URI uri;
        try {
            uri = endpointUri(input);
        } catch (Exception ignored) {
            throw failure(call, "端点必须是有效、无用户凭据及片段的完整 HTTPS URL");
        }
        checkCancelled(call);
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(180_000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        synchronized (connectionLock) {
            if (call.generation != cancelGeneration || Thread.currentThread().isInterrupted()) {
                connection.disconnect();
                throw failure(call, "操作已取消");
            }
            connections.add(connection);
        }
        return connection;
    }

    private void close(HttpsURLConnection connection) {
        try {
            connection.disconnect();
        } finally {
            synchronized (connectionLock) {
                connections.remove(connection);
            }
        }
    }

    private void checkStatus(Call call, HttpsURLConnection connection) throws Exception {
        checkCancelled(call);
        int code = connection.getResponseCode();
        checkCancelled(call);
        if (code < 200 || code >= 300) {
            // 不读取错误正文、原因短语、URL、Location 或凭据。
            String diagnosis;
            if (code == 401) {
                diagnosis = "鉴权失败，请检查该服务的 API Key 是否有效";
            } else if (code == 403) {
                diagnosis = "鉴权或访问权限不足，请检查 Key 的模型权限及服务访问策略";
            } else if (code == 404) {
                diagnosis = "路由或模型不存在，请检查 Base URL、完整端点与模型名称";
            } else if (code == 429) {
                diagnosis = "请求限流或额度不足，请稍后重试并检查配额";
            } else if (code >= 500 && code < 600) {
                diagnosis = "上游服务异常，请稍后重试或联系服务提供方";
            } else if (code >= 300 && code < 400) {
                diagnosis = "不允许重定向，请填写最终 HTTPS 端点";
            } else {
                diagnosis = "服务拒绝请求，请检查端点、模型及请求参数";
            }
            throw failure(call, "HTTP_" + code + " · HTTP " + code + "（" + diagnosis + "）");
        }
        String encoding = connection.getContentEncoding();
        if (encoding != null && !encoding.trim().isEmpty()
                && !encoding.trim().equalsIgnoreCase("identity")) {
            throw failure(call, "不支持压缩响应编码");
        }
    }

    private JSONObject readJson(Call call, HttpsURLConnection connection) throws Exception {
        return readJson(call, connection, TEXT_LIMIT);
    }

    private JSONObject readJson(Call call, HttpsURLConnection connection, int limit) throws Exception {
        byte[] bytes = readBody(call, connection, limit);
        if (!looksLikeJson(bytes)) {
            throw failure(call, "服务返回空响应或非 JSON 内容（可能是网关页面），请检查端点和服务状态");
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException ignored) {
            throw failure(call, "JSON 响应不是有效 UTF-8，请检查服务协议");
        }
        JSONObject object = parseObject(call, json, limit);
        if (object.has("error") && !object.isNull("error")) {
            throw failure(call, responseError(object.optJSONObject("error")));
        }
        return object;
    }

    private static String responseError(JSONObject error) {
        if (error != null) {
            for (String field : new String[] {"code", "type"}) {
                Object value = error.opt(field);
                if (!(value instanceof String)) continue;
                switch (((String) value).toLowerCase(Locale.ROOT)) {
                    case "invalid_api_key":
                    case "authentication_error":
                    case "unauthorized":
                    case "permission_denied":
                        return "RESPONSE_AUTH · 响应错误：鉴权或权限不足，请检查 Key 及模型授权";
                    case "rate_limit_exceeded":
                    case "rate_limit_error":
                    case "insufficient_quota":
                        return "RESPONSE_RATE_LIMIT · 响应错误：限流或额度不足，请检查配额并稍后重试";
                    case "model_not_found":
                    case "invalid_model":
                        return "RESPONSE_MODEL_NOT_FOUND · 响应错误：模型不存在或不可用，请检查模型名称与权限";
                    default:
                        break;
                }
            }
        }
        return "RESPONSE_ERROR · 服务返回错误对象，请检查端点、模型及服务状态";
    }

    private JSONObject parseObject(Call call, String text) throws Exception {
        return parseObject(call, text, TEXT_LIMIT);
    }

    private JSONObject parseObject(Call call, String text, int limit) throws Exception {
        // 同步约束解析输入及其中的字段名、字符串，图片使用独立上限。
        if (text.length() > limit) {
            throw failure(call, "JSON 超过大小限制");
        }
        validateJsonLexemes(call, text);
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value = readValue(call, reader, 0, new int[] {100_000});
            if (!(value instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) {
                throw failure(call, "响应必须是单个严格 JSON 对象");
            }
            return (JSONObject) value;
        } catch (SafeFailure error) {
            throw error;
        } catch (Exception ignored) {
            throw failure(call, "响应不是有效的严格 JSON 对象");
        }
    }

    // Android JsonReader 仍接受部分非法字符串转义或控制字符，需要额外校验。
    private void validateJsonLexemes(Call call, String text) throws SafeFailure {
        for (int i = 0; i < text.length(); ) {
            checkCancelled(call);
            char c = text.charAt(i++);
            if (c == '"') {
                boolean closed = false;
                while (i < text.length()) {
                    c = text.charAt(i++);
                    if (c == '"') {
                        closed = true;
                        break;
                    }
                    if (c < 0x20) {
                        throw failure(call, "JSON 字符串包含未转义控制字符");
                    }
                    if (c == '\\') {
                        if (i == text.length()) {
                            throw failure(call, "JSON 转义不完整");
                        }
                        char escape = text.charAt(i++);
                        if (escape == 'u') {
                            for (int n = 0; n < 4; n++) {
                                if (i == text.length()) {
                                    throw failure(call, "JSON Unicode 转义不完整");
                                }
                                char hex = text.charAt(i++);
                                if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f')
                                        || (hex >= 'A' && hex <= 'F'))) {
                                    throw failure(call, "JSON Unicode 转义无效");
                                }
                            }
                        } else if ("\"\\/bfnrt".indexOf(escape) < 0) {
                            throw failure(call, "JSON 转义无效");
                        }
                    }
                }
                if (!closed) {
                    throw failure(call, "JSON 字符串未闭合");
                }
            } else if (" \t\r\n{}[],:".indexOf(c) < 0) {
                int start = i - 1;
                while (i < text.length() && " \t\r\n{}[],:\"".indexOf(text.charAt(i)) < 0) {
                    i++;
                }
                String token = text.substring(start, i);
                if (!token.equals("true") && !token.equals("false") && !token.equals("null")
                        && !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+\\-]?[0-9]+)?")) {
                    throw failure(call, "JSON 包含非法字面量");
                }
            }
        }
    }

    private Object readValue(Call call, JsonReader reader, int depth, int[] remaining) throws Exception {
        checkCancelled(call);
        // 防止图片 JSON 上限放宽后，大量短值造成对象内存放大。
        if (--remaining[0] < 0) {
            throw failure(call, "JSON 值数量超过限制");
        }
        if (depth > 64) {
            throw failure(call, "JSON 嵌套过深");
        }
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) {
                        throw failure(call, "JSON 包含重复字段");
                    }
                    object.put(name, readValue(call, reader, depth + 1, remaining));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.put(readValue(call, reader, depth + 1, remaining));
                }
                reader.endArray();
                return array;
            case STRING:
                return reader.nextString();
            case NUMBER:
                return new BigDecimal(reader.nextString());
            case BOOLEAN:
                return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            default:
                throw failure(call, "JSON 值格式无效");
        }
    }

    private byte[] readBody(Call call, HttpsURLConnection connection, int limit) throws Exception {
        checkLength(call, connection, limit);
        try (InputStream input = new LimitedInput(call, connection.getInputStream(), limit);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            checkCancelled(call);
            return output.toByteArray();
        }
    }

    private void checkLength(Call call, HttpsURLConnection connection, int limit) throws SafeFailure {
        long length = connection.getContentLengthLong();
        if (length > limit) {
            throw failure(call, "响应超过大小限制");
        }
    }

    private String readTranscript(Call call, HttpsURLConnection connection, Consumer<String> delta)
            throws Exception {
        checkLength(call, connection, TEXT_LIMIT);
        StringBuilder transcript = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String event = "";
        boolean hasData = false;
        boolean firstLine = true;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new LimitedInput(call, connection.getInputStream(), TEXT_LIMIT),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                checkCancelled(call);
                if (firstLine && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                firstLine = false;
                if (line.isEmpty()) {
                    if (event.equals("error")) {
                        throw failure(call, "ASR 服务返回错误事件");
                    }
                    if (hasData) {
                        String result = transcriptEvent(call, event, data.toString(), transcript, delta);
                        if (result != null) {
                            return result;
                        }
                    }
                    data.setLength(0);
                    event = "";
                    hasData = false;
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (field.equals("event")) {
                    event = value;
                } else if (field.equals("data")) {
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                }
            }
        }
        // 即使末尾有非空行或 [DONE]，流结束也不能替代 transcript.text.done。
        throw failure(call, "ASR 流未收到 transcript.text.done，转写不完整");
    }

    private String transcriptEvent(Call call, String event, String data, StringBuilder transcript,
                                   Consumer<String> delta) throws Exception {
        if (data.trim().equals("[DONE]")) {
            throw failure(call, "ASR 缺少 transcript.text.done 最终文本");
        }
        JSONObject object = parseObject(call, data);
        observeModel(call, object);
        if ((object.has("error") && !object.isNull("error"))
                || "error".equals(object.optString("type"))) {
            throw failure(call, "ASR 服务返回错误事件");
        }
        String type = object.opt("type") instanceof String ? object.getString("type") : event;
        if (!event.isEmpty() && !event.equals("message") && !type.equals(event)) {
            throw failure(call, "ASR 事件类型不一致");
        }
        if (type.equals("transcript.text.delta")) {
            Object chunk = object.opt("delta");
            if (!(chunk instanceof String)) {
                chunk = object.opt("text");
            }
            if (!(chunk instanceof String)) {
                throw failure(call, "ASR 增量事件缺少文本");
            }
            transcript.append((String) chunk);
            if (delta != null) {
                delta.accept(transcript.toString());
            }
        } else if (type.equals("transcript.text.done")) {
            if (!(object.opt("text") instanceof String)) {
                throw failure(call, "ASR 最终事件缺少 text 字符串");
            }
            String result = object.getString("text");
            transcript.setLength(0);
            transcript.append(result);
            if (delta != null) {
                delta.accept(result);
            }
            return result;
        }
        return null;
    }

    private static String mediaType(HttpsURLConnection connection) {
        String type = connection.getContentType();
        if (type == null) {
            return "";
        }
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeJson(byte[] bytes) {
        int i = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187
                && (bytes[2] & 255) == 191 ? 3 : 0;
        while (i < bytes.length && (bytes[i] == ' ' || bytes[i] == '\r'
                || bytes[i] == '\n' || bytes[i] == '\t')) {
            i++;
        }
        return i < bytes.length && (bytes[i] == '{' || bytes[i] == '[');
    }

    private static boolean looksLikeMp3(byte[] bytes) {
        if (bytes.length < 4) {
            return false;
        }
        if (bytes.length > 10 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return true;
        }
        int b1 = bytes[1] & 255;
        int b2 = bytes[2] & 255;
        return (bytes[0] & 255) == 255 && (b1 & 224) == 224
                && (b1 & 24) != 8 && (b1 & 6) == 2
                && (b2 & 240) != 0 && (b2 & 240) != 240 && (b2 & 12) != 12;
    }

    private String requireModel(Call call, String model) throws SafeFailure {
        if (model == null || model.trim().isEmpty()) {
            throw failure(call, "缺少路由模型名称");
        }
        return model;
    }

    private void requireKey(Call call, String key) throws SafeFailure {
        if (key == null || key.trim().isEmpty()) {
            throw failure(call, "缺少 API Key");
        }
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) < 33 || key.charAt(i) > 126) {
                throw failure(call, "API Key 格式无效");
            }
        }
    }

    private void observeModel(Call call, JSONObject object) {
        Object model = object.opt("model");
        if (model instanceof String) {
            String safe = safeModel((String) model);
            if (!safe.equals("redacted")) {
                // 仅记录服务自报标识，不据此断言底层模型提供方。
                call.model = "reported:" + safe;
            }
        }
    }

    private String safeModel(String model) {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,127}")
                || containsSecret(model, config.key) || containsSecret(model, config.audioKey)) {
            return "redacted";
        }
        return model;
    }

    private static boolean containsSecret(String value, String secret) {
        return secret != null && !secret.isEmpty() && value.contains(secret);
    }

    private void checkCancelled(Call call) throws SafeFailure {
        synchronized (connectionLock) {
            if (Thread.currentThread().isInterrupted() || call.generation != cancelGeneration) {
                throw failure(call, "操作已取消");
            }
        }
    }

    private static SafeFailure failure(Call call, String message) {
        return new SafeFailure(call.capability + ": " + message);
    }

    /** 仅此客户端生成的异常可直接显示消息，不信任外层包装异常的消息。 */
    public static boolean isSafeError(Exception error) {
        return error instanceof SafeFailure;
    }

    /** 仅匹配有限深度 cause 链的类型，绝不读取普通异常消息。 */
    public static String safeError(Exception error) {
        if (isSafeError(error)) return error.getMessage();
        if (hasCause(error, java.net.SocketTimeoutException.class))
            return "NETWORK_TIMEOUT · 网络请求超时，请检查网络及服务负载；远端结果与计费可能未知";
        if (hasCause(error, java.net.UnknownHostException.class))
            return "DNS · 域名解析失败，请检查端点域名、设备 DNS、网络或 VPN";
        if (hasCause(error, javax.net.ssl.SSLException.class))
            return "TLS · 安全连接失败，请检查设备时间、服务证书及代理/VPN，勿关闭证书校验";
        if (hasCause(error, java.net.ConnectException.class))
            return "CONNECT · 无法连接服务，请检查网络、端口、代理/VPN及服务可达性";
        if (hasCause(error, java.net.SocketException.class))
            return "SOCKET_DISCONNECTED · 网络连接中断，请检查网络或代理后再决定是否重试";
        if (hasCause(error, java.io.InterruptedIOException.class)
                || hasCause(error, InterruptedException.class))
            return "CANCELLED · 操作取消或中断，远端结果与计费可能未知，不会自动重试";
        if (hasCause(error, IOException.class))
            return "IO · 通信、响应读取或本地文件处理失败，请检查网络、服务及存储空间";
        return "PROCESSING_ERROR · 响应处理失败，请检查模型协议、输入及设备状态";
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (type.isInstance(error)) return true;
        }
        return false;
    }

    public String diagnosticTarget() {
        String endpoint;
        try {
            URI uri = endpointUri(config.textUrl);
            endpoint = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath();
            String decoded = uri.getHost() + uri.getPath();
            if (containsSecret(endpoint, config.key) || containsSecret(endpoint, config.audioKey)
                    || containsSecret(decoded, config.key) || containsSecret(decoded, config.audioKey)) {
                endpoint = "redacted";
            }
        } catch (Exception ignored) {
            endpoint = "无效 HTTPS 端点（已隐藏）";
        }
        return "端点（host + path，不含查询参数）：" + endpoint
                + "\n测试模型：" + safeModel(config.textModel);
    }

    /** 消息仅由预定义能力名、固定错误短语及 HTTP 状态码组成。 */
    public static final class SafeFailure extends IOException {
        private SafeFailure(String message) {
            super(message);
        }
    }

    private static final class Call {
        final String capability;
        final long generation;
        String model;
        long startedNanos;
        boolean attempted;

        Call(String capability, String model, long generation) {
            this.capability = capability;
            this.model = model;
            this.generation = generation;
        }
    }

    private interface Operation<T> {
        T run(Call call) throws Exception;
    }

    private interface Response<T> {
        T read(HttpsURLConnection connection) throws Exception;
    }

    private final class LimitedInput extends InputStream {
        private final Call call;
        private final InputStream input;
        private final int limit;
        private int total;

        LimitedInput(Call call, InputStream input, int limit) {
            this.call = call;
            this.input = input;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            checkCancelled(call);
            int value = input.read();
            checkCancelled(call);
            if (value != -1 && ++total > limit) {
                throw failure(call, "响应超过大小限制");
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkCancelled(call);
            int count = input.read(buffer, offset, Math.min(length, limit - total + 1));
            checkCancelled(call);
            if (count > 0 && (total += count) > limit) {
                throw failure(call, "响应超过大小限制");
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
