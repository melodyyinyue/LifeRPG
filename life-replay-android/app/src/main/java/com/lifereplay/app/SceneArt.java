package com.lifereplay.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Random;

/**
 * 场景图改为纯文本管线：文本模型只输出受限的图层参数，端上用 Canvas 绘制，
 * 不依赖任何生图接口，风格由固定调色板与固定绘制规则保证统一。
 */
public final class SceneArt {
    private SceneArt() {}

    public static final int WIDTH = 1024;
    public static final int HEIGHT = 576;

    public static final String SYSTEM =
            "你是横版叙事插画的图层参数设计师。只输出一个 JSON 对象，不要解释、不要代码块以外的文字。\n"
            + "格式示例：{\"palette\":\"night_rain\",\"scene\":\"city\",\"horizon\":0.74,\"weather\":\"rain\","
            + "\"weather_density\":0.6,\"weather_angle\":10,\"moon\":true,\"moon_x\":0.55,\"moon_y\":0.2,"
            + "\"light_x\":0.78,\"light_y\":0.7,\"light_strength\":0.7,\"foreground\":\"window\","
            + "\"figures\":[{\"x\":0.52,\"height\":0.46,\"pose\":\"standing\"}],\"seed\":1234}\n"
            + "取值约束：palette 只能是 night_rain / deep_night / cold_dawn / dusk_amber；"
            + "scene 只能是 city / interior / rooftop / station / road / field；"
            + "weather 只能是 rain / snow / fog / clear；"
            + "foreground 只能是 window / desk / railing / pillar / none；"
            + "pose 只能是 standing / walking / sitting。\n"
            + "数值约束：horizon 0.45-0.85；weather_density 0-1；weather_angle -30-30；"
            + "moon_x 与 light_x 0-1；moon_y 0.05-0.45；light_y 0.4-0.95；light_strength 0-1；"
            + "figures 0-3 个，x 为 0-1，height 为画面高度占比 0.2-0.6；seed 为 0-99999 的整数。\n"
            + "按照当前剧情选择最贴切的场景、天气、人物位置与光源方位；夜景与雨景优先，保持安静克制的电影感。";

    public static String input(JSONObject node) {
        String title = node.optString("title", "");
        String prompt = node.optString("image_prompt", "");
        String text = node.optString("text", "");
        if (text.length() > 600) {
            text = text.substring(0, 600);
        }
        return "章节标题：" + title + "\n画面描述：" + prompt + "\n正文片段：" + text
                + "\n请输出这一幕的图层参数 JSON。";
    }

    private static final class Palette {
        final int skyTop, skyBottom, far, near, dark, accent, glow, frame;
        Palette(int skyTop, int skyBottom, int far, int near, int dark, int accent, int glow, int frame) {
            this.skyTop = skyTop; this.skyBottom = skyBottom; this.far = far; this.near = near;
            this.dark = dark; this.accent = accent; this.glow = glow; this.frame = frame;
        }
    }

    private static Palette palette(String key) {
        switch (key) {
            case "deep_night":
                return new Palette(0xFF060D18, 0xFF17293C, 0xFF1E3246, 0xFF101A25,
                        0xFF070C13, 0xFFFFC47A, 0xFFFFE0AD, 0xFF0A121C);
            case "cold_dawn":
                return new Palette(0xFF16283D, 0xFF5A7C8A, 0xFF3B5668, 0xFF1B2A35,
                        0xFF101A22, 0xFFFFD0A0, 0xFFFFE6C4, 0xFF13202C);
            case "dusk_amber":
                return new Palette(0xFF1B2238, 0xFF7A5A52, 0xFF3C3A4E, 0xFF1A1A26,
                        0xFF0F0F18, 0xFFFFB866, 0xFFFFD9A6, 0xFF151521);
            default:
                return new Palette(0xFF0C1A2B, 0xFF2C5068, 0xFF2A4257, 0xFF16242F,
                        0xFF0A1119, 0xFFF2B366, 0xFFFFD79A, 0xFF121D2B);
        }
    }

    private static Paint fill(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        return paint;
    }

    private static String option(JSONObject raw, String field, String fallback, String... allowed)
            throws JSONException {
        String value = raw.optString(field, fallback).trim().toLowerCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw new JSONException("场景参数 " + field + " 超出允许取值");
    }

    private static float number(JSONObject raw, String field, float fallback, float min, float max)
            throws JSONException {
        if (!raw.has(field) || raw.isNull(field)) {
            return fallback;
        }
        double value = raw.optDouble(field, Double.NaN);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new JSONException("场景参数 " + field + " 不是有效数值");
        }
        if (value < min || value > max) {
            throw new JSONException("场景参数 " + field + " 超出允许范围");
        }
        return (float) value;
    }

    /** 严格校验模型返回的图层参数；任何越界都抛出，让调用方回退到统一风格兜底图。 */
    public static byte[] render(JSONObject raw) throws JSONException {
        if (raw == null) {
            throw new JSONException("场景参数为空");
        }
        Palette palette = palette(option(raw, "palette", "night_rain",
                "night_rain", "deep_night", "cold_dawn", "dusk_amber"));
        String scene = option(raw, "scene", "city",
                "city", "interior", "rooftop", "station", "road", "field");
        String weather = option(raw, "weather", "rain", "rain", "snow", "fog", "clear");
        String foreground = option(raw, "foreground", "none",
                "window", "desk", "railing", "pillar", "none");
        float horizon = number(raw, "horizon", 0.74f, 0.45f, 0.85f);
        float density = number(raw, "weather_density", 0.5f, 0f, 1f);
        float angle = number(raw, "weather_angle", 10f, -30f, 30f);
        float moonX = number(raw, "moon_x", 0.55f, 0f, 1f);
        float moonY = number(raw, "moon_y", 0.2f, 0.05f, 0.45f);
        float lightX = number(raw, "light_x", 0.78f, 0f, 1f);
        float lightY = number(raw, "light_y", 0.7f, 0.4f, 0.95f);
        float lightStrength = number(raw, "light_strength", 0.6f, 0f, 1f);
        boolean moon = raw.optBoolean("moon", true);
        int seed = (int) number(raw, "seed", 2026f, 0f, 99999f);

        JSONArray figures = raw.optJSONArray("figures");
        if (figures != null && figures.length() > 3) {
            throw new JSONException("场景人物数量超过 3 个");
        }

        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            Random random = new Random(seed);
            int horizonY = (int) (HEIGHT * horizon);

            sky(canvas, palette, horizonY);
            if (moon) {
                moon(canvas, palette, moonX * WIDTH, moonY * HEIGHT);
            }
            backdrop(canvas, random, palette, scene, horizonY);
            ground(canvas, palette, scene, horizonY);
            if (lightStrength > 0.02f) {
                glow(canvas, palette, lightX * WIDTH, lightY * HEIGHT, lightStrength);
            }
            if (figures != null) {
                for (int i = 0; i < figures.length(); i++) {
                    JSONObject figure = figures.optJSONObject(i);
                    if (figure == null) {
                        throw new JSONException("场景人物参数格式错误");
                    }
                    figure(canvas, palette,
                            number(figure, "x", 0.5f, 0f, 1f) * WIDTH,
                            horizonY + 8,
                            number(figure, "height", 0.4f, 0.2f, 0.6f) * HEIGHT,
                            option(figure, "pose", "standing", "standing", "walking", "sitting"));
                }
            }
            foreground(canvas, palette, foreground, horizonY);
            weather(canvas, random, weather, density, angle);
            vignette(canvas, palette);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new JSONException("场景图编码失败");
            }
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static void sky(Canvas canvas, Palette palette, int horizonY) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, horizonY,
                palette.skyTop, palette.skyBottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, horizonY, paint);
    }

    private static void moon(Canvas canvas, Palette palette, float x, float y) {
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setShader(new RadialGradient(x, y, 150,
                new int[]{0x55FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, 150, halo);
        canvas.drawCircle(x, y, 44, fill(0xFFE8F0EC));
        Paint band = fill(0x33203040);
        canvas.drawRect(x - 44, y - 6, x + 44, y + 4, band);
        canvas.drawRect(x - 44, y + 14, x + 44, y + 20, band);
    }

    private static void backdrop(Canvas canvas, Random random, Palette palette,
                                 String scene, int horizonY) {
        switch (scene) {
            case "field":
                hills(canvas, palette, horizonY);
                break;
            case "road":
                hills(canvas, palette, horizonY);
                poles(canvas, random, palette, horizonY);
                break;
            case "rooftop":
                skyline(canvas, random, palette.far, horizonY - 18, 0.12f, 0.26f, palette.accent, 0.1f);
                skyline(canvas, random, palette.near, horizonY, 0.2f, 0.42f, palette.accent, 0.16f);
                break;
            case "station":
                skyline(canvas, random, palette.far, horizonY - 24, 0.08f, 0.2f, palette.accent, 0.08f);
                pillars(canvas, palette, horizonY);
                break;
            default:
                skyline(canvas, random, palette.far, horizonY - 20, 0.16f, 0.34f, palette.accent, 0.12f);
                skyline(canvas, random, palette.near, horizonY, 0.24f, 0.5f, palette.accent, 0.2f);
                break;
        }
    }

    private static void skyline(Canvas canvas, Random random, int color, int baseY,
                                float minHeight, float maxHeight, int windowColor, float windowChance) {
        Paint body = fill(color);
        Paint window = fill(windowColor);
        int x = -48;
        while (x < WIDTH + 48) {
            int width = 58 + random.nextInt(92);
            int height = (int) ((minHeight + random.nextFloat() * (maxHeight - minHeight)) * HEIGHT);
            int top = baseY - height;
            canvas.drawRect(x, top, x + width, baseY, body);
            if (random.nextFloat() < 0.28f) {
                float antenna = x + width / 2f;
                canvas.drawRect(antenna - 2.5f, top - 16 - random.nextInt(24), antenna + 2.5f, top, body);
            }
            for (int wy = top + 16; wy < baseY - 14; wy += 24) {
                for (int wx = x + 12; wx < x + width - 14; wx += 21) {
                    if (random.nextFloat() < windowChance) {
                        canvas.drawRect(wx, wy, wx + 8, wy + 11, window);
                    }
                }
            }
            x += width + 6 + random.nextInt(16);
        }
    }

    private static void hills(Canvas canvas, Palette palette, int horizonY) {
        Path far = new Path();
        far.moveTo(0, horizonY);
        far.lineTo(0, horizonY - 70);
        far.quadTo(WIDTH * 0.28f, horizonY - 160, WIDTH * 0.56f, horizonY - 60);
        far.quadTo(WIDTH * 0.8f, horizonY - 10, WIDTH, horizonY - 90);
        far.lineTo(WIDTH, horizonY);
        far.close();
        canvas.drawPath(far, fill(palette.far));
        Path near = new Path();
        near.moveTo(0, horizonY);
        near.lineTo(0, horizonY - 30);
        near.quadTo(WIDTH * 0.45f, horizonY - 110, WIDTH, horizonY - 24);
        near.lineTo(WIDTH, horizonY);
        near.close();
        canvas.drawPath(near, fill(palette.near));
    }

    private static void poles(Canvas canvas, Random random, Palette palette, int horizonY) {
        Paint paint = fill(palette.dark);
        for (int i = 0; i < 5; i++) {
            float x = 80 + i * 210 + random.nextInt(40);
            float height = 120 + i * 18;
            canvas.drawRect(x - 4, horizonY - height, x + 4, horizonY, paint);
            canvas.drawRect(x - 34, horizonY - height, x + 34, horizonY - height + 8, paint);
        }
    }

    private static void pillars(Canvas canvas, Palette palette, int horizonY) {
        Paint paint = fill(palette.near);
        for (int i = 0; i < 4; i++) {
            float x = 70 + i * 300;
            canvas.drawRect(x, horizonY - 320, x + 30, horizonY, paint);
        }
        canvas.drawRect(0, horizonY - 330, WIDTH, horizonY - 300, paint);
    }

    private static void ground(Canvas canvas, Palette palette, String scene, int horizonY) {
        canvas.drawRect(0, horizonY, WIDTH, HEIGHT, fill(palette.dark));
        Paint line = fill(0x22FFFFFF);
        canvas.drawRect(0, horizonY, WIDTH, horizonY + 3, line);
        if (scene.equals("road")) {
            Paint mark = fill(0x33FFFFFF);
            for (int i = 0; i < 6; i++) {
                float y = horizonY + 20 + i * i * 7f;
                float half = 18 + i * 26f;
                canvas.drawRect(WIDTH / 2f - half * 0.08f - 4, y, WIDTH / 2f + half * 0.08f + 4, y + 6 + i * 2, mark);
            }
        }
        if (scene.equals("station")) {
            canvas.drawRect(0, horizonY + 42, WIDTH, horizonY + 48, fill(0x2AFFFFFF));
        }
    }

    private static void glow(Canvas canvas, Palette palette, float x, float y, float strength) {
        int alpha = (int) (120 * strength);
        int center = (palette.glow & 0x00FFFFFF) | (alpha << 24);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(x, y, 260,
                new int[]{center, (palette.glow & 0x00FFFFFF) | ((alpha / 3) << 24), palette.glow & 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, 260, paint);
    }

    private static void figure(Canvas canvas, Palette palette, float x, float baseY,
                               float height, String pose) {
        Paint paint = fill(palette.dark);
        float unit = height / 8f;
        float top = baseY - height;
        canvas.drawCircle(x, top + unit * 0.9f, unit * 0.78f, paint);
        canvas.drawRect(x - unit * 1.05f, top + unit * 0.3f, x + unit * 1.05f, top + unit * 0.55f, paint);
        float shoulder = top + unit * 1.7f;
        float hip = pose.equals("sitting") ? baseY - unit * 2.6f : baseY - unit * 2.9f;
        Path body = new Path();
        body.moveTo(x - unit * 1.1f, shoulder);
        body.lineTo(x + unit * 1.1f, shoulder);
        body.lineTo(x + unit * 1.45f, hip);
        body.lineTo(x - unit * 1.45f, hip);
        body.close();
        canvas.drawPath(body, paint);
        if (pose.equals("sitting")) {
            canvas.drawRect(x - unit * 0.3f, hip, x + unit * 2.3f, hip + unit * 0.85f, paint);
            canvas.drawRect(x + unit * 1.45f, hip, x + unit * 2.3f, baseY, paint);
        } else if (pose.equals("walking")) {
            Path legs = new Path();
            legs.moveTo(x - unit * 1.0f, hip);
            legs.lineTo(x - unit * 0.2f, hip);
            legs.lineTo(x - unit * 1.1f, baseY);
            legs.lineTo(x - unit * 1.9f, baseY);
            legs.close();
            canvas.drawPath(legs, paint);
            Path front = new Path();
            front.moveTo(x + unit * 0.2f, hip);
            front.lineTo(x + unit * 1.0f, hip);
            front.lineTo(x + unit * 1.6f, baseY);
            front.lineTo(x + unit * 0.8f, baseY);
            front.close();
            canvas.drawPath(front, paint);
        } else {
            canvas.drawRect(x - unit * 1.0f, hip, x - unit * 0.2f, baseY, paint);
            canvas.drawRect(x + unit * 0.2f, hip, x + unit * 1.0f, baseY, paint);
        }
    }

    private static void foreground(Canvas canvas, Palette palette, String kind, int horizonY) {
        Paint frame = fill(palette.frame);
        switch (kind) {
            case "window": {
                canvas.drawRect(0, 0, WIDTH, 18, frame);
                canvas.drawRect(0, HEIGHT - 70, WIDTH, HEIGHT, frame);
                canvas.drawRect(0, 0, 30, HEIGHT, frame);
                canvas.drawRect(WIDTH - 30, 0, WIDTH, HEIGHT, frame);
                canvas.drawRect(WIDTH / 3f - 8, 0, WIDTH / 3f + 8, HEIGHT - 70, frame);
                canvas.drawRect(WIDTH * 2 / 3f - 8, 0, WIDTH * 2 / 3f + 8, HEIGHT - 70, frame);
                canvas.drawRect(0, HEIGHT * 0.5f - 7, WIDTH, HEIGHT * 0.5f + 7, frame);
                break;
            }
            case "desk": {
                Path desk = new Path();
                desk.moveTo(WIDTH * 0.58f, HEIGHT * 0.84f);
                desk.lineTo(WIDTH, HEIGHT * 0.76f);
                desk.lineTo(WIDTH, HEIGHT);
                desk.lineTo(WIDTH * 0.6f, HEIGHT);
                desk.close();
                canvas.drawPath(desk, fill(0xFF4A3A2C));
                canvas.drawRect(WIDTH * 0.8f, HEIGHT * 0.62f, WIDTH * 0.81f, HEIGHT * 0.84f, fill(0xFF6B4F38));
                Path shade = new Path();
                shade.moveTo(WIDTH * 0.76f, HEIGHT * 0.68f);
                shade.lineTo(WIDTH * 0.86f, HEIGHT * 0.68f);
                shade.lineTo(WIDTH * 0.84f, HEIGHT * 0.62f);
                shade.lineTo(WIDTH * 0.78f, HEIGHT * 0.62f);
                shade.close();
                canvas.drawPath(shade, fill(0xFF2F5A4C));
                canvas.drawRect(WIDTH * 0.74f, HEIGHT * 0.68f, WIDTH * 0.88f, HEIGHT * 0.70f, fill(palette.glow));
                break;
            }
            case "railing": {
                canvas.drawRect(0, HEIGHT - 110, WIDTH, HEIGHT - 96, frame);
                canvas.drawRect(0, HEIGHT - 40, WIDTH, HEIGHT, frame);
                for (int i = 0; i < 10; i++) {
                    float x = 40 + i * 110;
                    canvas.drawRect(x, HEIGHT - 110, x + 10, HEIGHT - 40, frame);
                }
                break;
            }
            case "pillar": {
                canvas.drawRect(0, 0, 90, HEIGHT, frame);
                canvas.drawRect(WIDTH - 70, 0, WIDTH, HEIGHT, frame);
                break;
            }
            default:
                break;
        }
    }

    private static void weather(Canvas canvas, Random random, String kind, float density, float angle) {
        if (kind.equals("clear")) {
            return;
        }
        if (kind.equals("fog")) {
            Paint fog = new Paint(Paint.ANTI_ALIAS_FLAG);
            fog.setShader(new LinearGradient(0, HEIGHT * 0.35f, 0, HEIGHT,
                    0x00AFC6D6, (int) (0x66 * Math.max(0.3f, density)) << 24 | 0x00AFC6D6,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, HEIGHT * 0.35f, WIDTH, HEIGHT, fog);
            return;
        }
        int count = (int) (40 + density * 320);
        double radians = Math.toRadians(angle);
        if (kind.equals("snow")) {
            Paint flake = fill(0x99FFFFFF);
            for (int i = 0; i < count; i++) {
                canvas.drawCircle(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        1.5f + random.nextFloat() * 2.5f, flake);
            }
            return;
        }
        Paint drop = new Paint(Paint.ANTI_ALIAS_FLAG);
        drop.setColor(0x66C9DCEA);
        drop.setStrokeWidth(1.8f);
        for (int i = 0; i < count; i++) {
            float x = random.nextInt(WIDTH);
            float y = random.nextInt(HEIGHT);
            float length = 16 + random.nextFloat() * 26;
            canvas.drawLine(x, y, x + (float) (Math.sin(radians) * length),
                    y + (float) (Math.cos(radians) * length), drop);
        }
    }

    private static void vignette(Canvas canvas, Palette palette) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(WIDTH / 2f, HEIGHT / 2f, WIDTH * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x55000000},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, paint);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(16);
        border.setColor(Color.argb(255, Color.red(palette.frame), Color.green(palette.frame),
                Color.blue(palette.frame)));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, border);
    }
}
