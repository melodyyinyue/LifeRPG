package com.lifereplay.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class ProjectLibrary {
    public static final String RAIN_DEMO_ID = "1482cafe-1000-4000-8000-000000000001";
    public static final String TRAIN_DEMO_ID = "1482cafe-1000-4000-8000-000000000002";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String[] ARTS = {"rain_city", "rain_archive", "rain_rooftop", "rain_station"};
    private static final String[] TRAIN_TITLES = {"最后一班车", "站台上的决定", "车门关闭前", "沿线的灯火", "终点将至", "到站的安排", "晨光中的站台"};
    private static final String[][] TRAIN_OPTIONS = {
            {"帮旅客询问站务员", "先核对末班车时刻"},
            {"陪旅客走无障碍通道", "请站务员引导上车"},
            {"坐在车门附近", "走到安静车厢"},
            {"向乘务员确认换乘", "查看车厢线路图"},
            {"替旅客指明出口", "陪旅客走到服务台"},
            {"在站前道别", "留到接驳车到站"}
    };
    private static final String[][] TRAIN_EFFECTS = {
            {"站务员确认旅客也去终点站。", "你核对时刻，距发车还有五分钟。"},
            {"你陪旅客绕行缓坡，及时抵达车门。", "站务员引导旅客上车，你随后登车。"},
            {"你坐在门边，方便到站下车。", "你走进安静车厢，隔窗看着沿线灯火。"},
            {"乘务员确认终点还有一班接驳车。", "线路图标明接驳站位于东出口。"},
            {"你指明出口，旅客记下路线。", "你陪旅客到服务台，工作人员接手指引。"},
            {"你在站前道别，各自踏上归途。", "接驳车到站，旅客上车后你才离开。"}
    };
    private static final String[] TRAIN_SCENES = {
            "雨夜，最后一班列车即将出发。你在站厅遇见一位提着旧皮箱的旅客，对方正寻找终点方向。",
            "广播提醒检票。通往站台的楼梯旁还有无障碍通道，旅客提着箱子停在入口。",
            "车门即将关闭，你们已经安全登车。车内还有空位，可以决定坐在哪里。",
            "列车驶过夜色中的河桥。旅客担心终点的换乘，你可以协助核对公开信息。",
            "终点站的灯光渐渐清晰。旅客收好皮箱，准备下车寻找接驳站。",
            "列车抵达终点。旅客已知道接驳方向，你也完成了这段夜行，决定何时道别。",
            "末班车熄灯入库，天边泛白。这段本地预制旅程结束，只记录已发生的选择。"
    };

    private final File filesDir;
    private final SharedPreferences preferences;
    private final List<String> notices = new ArrayList<>();

    public ProjectLibrary(Context context) {
        filesDir = context.getFilesDir();
        preferences = context.getSharedPreferences("project_library", Context.MODE_PRIVATE);
    }

    /** 直接扫描完整存档；单个坏项目不阻止其他项目显示。 */
    public synchronized List<JSONObject> list() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (String id : projectIds()) {
            try {
                JSONObject story = load(id);
                if (story != null) {
                    result.add(story);
                }
            } catch (Exception error) {
                warn("部分项目无法读取，原存档已保留。");
            }
        }
        result.sort(Comparator.comparingLong((JSONObject story) -> story.optLong("updatedAt", 0))
                .reversed().thenComparing(story -> story.optString("id")));
        return result;
    }

    public synchronized JSONObject load(String id) throws Exception {
        JSONObject story = new StoryStore(projectFile(id)).load();
        if (story != null) {
            if (!id.equals(story.getString("id"))) {
                throw new IOException("项目标识与存档不一致，原存档已保留");
            }
            decorate(story);
        }
        return story;
    }

    public synchronized void save(JSONObject story) throws Exception {
        if (story == null || !(story.opt("id") instanceof String)) {
            throw new JSONException("项目数据无效，未覆盖存档");
        }
        File file = projectFile(story.getString("id"));
        JSONObject snapshot = new JSONObject(story.toString());
        decorate(snapshot);
        snapshot.put("updatedAt", System.currentTimeMillis());
        new StoryStore(file).save(snapshot);
        // 仅在原子保存成功后同步显示元数据，不替换调用方持有的节点对象。
        for (String key : new String[]{"projectTitle", "coverArt", "projectTemplate", "updatedAt"}) {
            story.put(key, snapshot.get(key));
        }
        JSONArray nodes = story.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            node.put("demoArt", story.getBoolean("demo") ? artFor(story, node) : "");
        }
    }

    public synchronized void delete(String id) throws Exception {
        File file = projectFile(id);
        JSONObject target = null;
        try {
            target = load(id);
        } catch (Exception error) {
            warn("项目无法读取，仅删除指定存档，未清理媒体。");
        }
        Set<String> media = target == null ? new HashSet<>() : referencedMedia(target);
        // 无法确认其他项目引用时宁可留下媒体，避免损伤其他项目。
        for (String otherId : projectIds()) {
            if (id.equals(otherId)) {
                continue;
            }
            try {
                JSONObject other = load(otherId);
                if (other != null) {
                    media.removeAll(referencedMedia(other));
                }
            } catch (Exception error) {
                media.clear();
                warn("部分项目无法读取，媒体清理已跳过。");
            }
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (id.equals(getSelectedId())) {
            editor.remove("selected_id");
        }
        if (RAIN_DEMO_ID.equals(id) || TRAIN_DEMO_ID.equals(id)) {
            editor.putBoolean("seeded_" + id, true);
        }
        commitPreferences(editor);
        new StoryStore(file).delete();
        for (String name : media) {
            try {
                File asset = safeChild(filesDir.getCanonicalFile(), name);
                if (asset.exists() && (!asset.isFile() || !asset.delete())) {
                    warn("项目已删除，部分媒体未能清理。");
                }
            } catch (IOException error) {
                warn("项目已删除，部分媒体未能清理。");
            }
        }
    }

    /** 旧存档保留作备份；成功标记独立于项目是否仍然存在。 */
    public synchronized void migrateLegacy() throws Exception {
        if (preferences.getBoolean("legacy_migrated", false)) {
            return;
        }
        File legacy = safeChild(filesDir.getCanonicalFile(), "story.json");
        checkSidecars(legacy);
        JSONObject story = new StoryStore(legacy).load();
        SharedPreferences.Editor editor = preferences.edit();
        if (story != null) {
            String id = story.getString("id");
            File destination = projectFile(id);
            if (!destination.exists() && !new File(destination.getPath() + ".bak").exists()) {
                save(story);
            } else {
                load(id);
            }
            if (getSelectedId().isEmpty()) {
                editor.putString("selected_id", id);
            }
        }
        commitPreferences(editor.putBoolean("legacy_migrated", true));
    }

    public synchronized void ensureDemos() throws Exception {
        if (preferences.getBoolean("demos_seeded", false)) {
            return;
        }
        seedDemo(RAIN_DEMO_ID, false);
        seedDemo(TRAIN_DEMO_ID, true);
        commitPreferences(preferences.edit().putBoolean("demos_seeded", true));
    }

    public synchronized String getSelectedId() {
        String id = preferences.getString("selected_id", "");
        return isUuid(id) ? id : "";
    }

    /** 空字符串或 null 清空选择；其他值必须对应可读取的项目。 */
    public synchronized void select(String id) throws Exception {
        SharedPreferences.Editor editor = preferences.edit();
        if (id == null || id.isEmpty()) {
            editor.remove("selected_id");
        } else {
            if (load(id) == null) {
                throw new IOException("所选项目不存在");
            }
            editor.putString("selected_id", id);
        }
        commitPreferences(editor);
    }

    public synchronized List<String> warnings() {
        return new ArrayList<>(notices);
    }

    public static JSONObject demoNode(JSONObject story, String parentId, int option)
            throws JSONException {
        if (!"train".equals(story.optString("projectTemplate"))) {
            return GameContent.demoNode(story, parentId, option);
        }
        JSONObject input = new JSONObject(GameContent.nodeInput(story, parentId, option));
        JSONArray path = input.getJSONArray("path");
        int depth = path.length();
        StringBuilder text = new StringBuilder("【本地固定示例，非模型生成】");
        for (int round = 0; round < depth; round++) {
            String choice = round == depth - 1 ? input.getString("pending_choice")
                    : path.getJSONObject(round + 1).getString("choice");
            int chosen = -1;
            for (int i = 0; i < TRAIN_OPTIONS[round].length; i++) {
                if (TRAIN_OPTIONS[round][i].equals(choice)) {
                    chosen = i;
                    break;
                }
            }
            if (chosen < 0) {
                throw new JSONException("列车示例只能续接预制选项");
            }
            if (depth == 6 || round == depth - 1) {
                text.append(TRAIN_EFFECTS[round][chosen]);
            }
        }
        text.append(TRAIN_SCENES[depth]);
        JSONArray options = new JSONArray();
        if (depth < 6) {
            for (String choice : TRAIN_OPTIONS[depth]) {
                options.put(choice);
            }
        }
        return StoryStore.validateNode(new JSONObject().put("title", TRAIN_TITLES[depth])
                .put("text", text.toString()).put("scene_type", depth == 0 ? "opening"
                        : depth == 6 ? "ending" : depth % 2 == 0 ? "consequence" : "conflict")
                .put("image_prompt", "")
                .put("roles", new JSONArray().put(new JSONObject().put("name", "旁白")
                        .put("description", "不入镜，声音平和清晰。")))
                .put("scripts", new JSONArray().put(new JSONObject().put("speaker", "旁白")
                        .put("text", depth == 6 ? "末班车的旅程到此结束。" : "这班列车上的选择，会留下不同的记录。")))
                .put("options", options));
    }

    public static String artFor(JSONObject story, JSONObject node) {
        if (story == null || node == null || !story.optBoolean("demo", false)) {
            return null;
        }
        String saved = node.optString("demoArt", "");
        if (knownArt(saved)) {
            return saved;
        }
        int depth = Math.max(0, node.optInt("depth", 0));
        int option = Math.max(0, node.optInt("option", 0));
        if ("train".equals(story.optString("projectTemplate"))) {
            return depth == 0 || depth >= 5 ? "rain_station"
                    : ARTS[(int) (((long) depth + option + 2) % ARTS.length)];
        }
        return depth == 0 ? "rain_city" : ARTS[(int) (((long) depth + option) % ARTS.length)];
    }

    private void seedDemo(String id, boolean train) throws Exception {
        if (preferences.getBoolean("seeded_" + id, false)) {
            return;
        }
        File file = projectFile(id);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
            JSONObject story = StoryStore.newStory(train ? "雨夜末班车上的一次相遇。" : "在雨城为一封旧信寻找收件人。", true)
                    .put("id", id).put("projectTitle", train ? "最后一班车" : "雨城来信")
                    .put("projectTemplate", train ? "train" : "rain")
                    .put("coverArt", train ? "rain_station" : "rain_city");
            JSONObject root = StoryStore.commit(story, null, -1, "", demoNode(story, null, -1));
            for (int option = 0; option < 2; option++) {
                String parentId = root.getString("id");
                JSONObject child = StoryStore.commit(story, parentId, option, "", demoNode(story, parentId, option));
                for (int next = 0; next < (train ? option == 0 ? 1 : 0 : 2); next++) {
                    String childId = child.getString("id");
                    StoryStore.commit(story, childId, next, "", demoNode(story, childId, next));
                }
            }
            story.put("current", root.getString("id")).put("exploring", false);
            save(story);
        }
        // 分项目提交播种标记，另一示例失败也不复活已删除的示例。
        commitPreferences(preferences.edit().putBoolean("seeded_" + id, true));
    }

    private static void decorate(JSONObject story) throws JSONException {
        JSONArray nodes = story.getJSONArray("nodes");
        String title = story.optString("projectTitle", "").trim();
        if (title.isEmpty()) {
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (node.optString("parent", "").isEmpty()) {
                    title = node.optString("title", "").trim();
                    break;
                }
            }
            if (title.isEmpty()) {
                title = story.optString("premise", "").trim();
            }
            if (title.isEmpty()) {
                title = "未命名故事";
            }
            int end = Math.min(title.length(), 40);
            if (end < title.length() && Character.isHighSurrogate(title.charAt(end - 1))) {
                end--;
            }
            title = title.substring(0, end);
        }
        boolean demo = story.getBoolean("demo");
        story.put("projectTitle", title);
        if (!(story.opt("projectTemplate") instanceof String)) {
            story.put("projectTemplate", demo ? "rain" : "");
        }
        if (!(story.opt("updatedAt") instanceof Number)) {
            story.put("updatedAt", 0L);
        }
        if (demo) {
            if (!knownArt(story.optString("coverArt", ""))) {
                story.put("coverArt", "train".equals(story.optString("projectTemplate")) ? "rain_station" : "rain_city");
            }
        } else if (!(story.opt("coverArt") instanceof String)) {
            story.put("coverArt", "");
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            node.put("demoArt", demo ? artFor(story, node) : "");
        }
    }

    private Set<String> projectIds() throws IOException {
        File[] files = directory().listFiles();
        if (files == null) {
            throw new IOException("无法读取项目目录，原存档已保留");
        }
        Set<String> ids = new TreeSet<>();
        for (File entry : files) {
            String name = entry.getName();
            String base = name.endsWith(".bak") ? name.substring(0, name.length() - 4) : name;
            if (base.endsWith(".json")) {
                String id = base.substring(0, base.length() - 5);
                if (isUuid(id)) {
                    ids.add(id);
                } else {
                    warn("部分项目文件名无效，原文件已保留。");
                }
            }
        }
        return ids;
    }

    private File projectFile(String id) throws IOException {
        if (!isUuid(id)) {
            throw new IOException("项目标识无效");
        }
        File file = safeChild(directory(), id + ".json");
        checkSidecars(file);
        return file;
    }

    private File directory() throws IOException {
        File directory = safeChild(filesDir.getCanonicalFile(), "projects");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建项目目录");
        }
        return directory;
    }

    private static File safeChild(File parent, String name) throws IOException {
        File file = new File(parent, name);
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) {
            throw new IOException("存储路径无效");
        }
        return file;
    }

    private static void checkSidecars(File file) throws IOException {
        safeChild(file.getParentFile(), file.getName() + ".bak");
        safeChild(file.getParentFile(), file.getName() + ".new");
    }

    private static boolean isUuid(String id) {
        if (id == null || !id.matches(UUID_PATTERN)) {
            return false;
        }
        return UUID.fromString(id).toString().equals(id);
    }

    private static boolean knownArt(String art) {
        for (String allowed : ARTS) {
            if (allowed.equals(art)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> referencedMedia(JSONObject story) throws JSONException {
        Set<String> result = new HashSet<>();
        JSONArray nodes = story.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            for (String field : new String[]{"imagePath", "audioPath"}) {
                String name = node.getString(field);
                // 兼容旧 UUID 文件名与现有的节点 UUID-随机 UUID 文件名；不扫描媒体目录。
                if (name.matches(UUID_PATTERN + "(?:-" + UUID_PATTERN + ")?\\.(img|mp3)")) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    private void warn(String message) {
        if (!notices.contains(message)) {
            notices.add(message);
        }
    }

    private static void commitPreferences(SharedPreferences.Editor editor) throws IOException {
        if (!editor.commit()) {
            throw new IOException("项目设置保存失败，请重试");
        }
    }
}
