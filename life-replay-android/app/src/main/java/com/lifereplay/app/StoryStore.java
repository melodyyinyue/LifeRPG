package com.lifereplay.app;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StoryStore {
    private final File file;
    private final AtomicFile atomicFile;

    public StoryStore(Context context) {
        this(new File(context.getFilesDir(), "story.json"));
    }

    public StoryStore(File file) {
        this.file = file;
        atomicFile = new AtomicFile(file);
    }

    public synchronized JSONObject load() throws Exception {
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = atomicFile.readFully();
        } catch (IOException error) {
            throw new IOException("无法读取故事存档；未清除存档");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JSONObject story = parse(text);
            validateStory(story);
            return story;
        } catch (Exception error) {
            throw new IOException("故事存档损坏或格式不兼容；未清除存档");
        }
    }

    public synchronized void save(JSONObject story) throws Exception {
        byte[] bytes;
        try {
            requireObject(story);
            JSONObject snapshot = parse(story.toString());
            validateStory(snapshot);
            bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new JSONException("故事数据无效，未覆盖存档");
        }
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(bytes);
            output.getFD().sync();
            atomicFile.finishWrite(output);
        } catch (Exception error) {
            atomicFile.failWrite(output);
            throw new IOException("故事存档写入失败");
        }
    }

    public synchronized void delete() throws Exception {
        atomicFile.delete();
        if (file.exists() || new File(file.getPath() + ".bak").exists()
                || new File(file.getPath() + ".new").exists()) {
            throw new IOException("故事存档删除失败");
        }
    }

    public static JSONObject newStory(String premise, boolean demo) throws JSONException {
        if (premise == null) {
            throw new JSONException("故事前提必须为字符串");
        }
        return new JSONObject().put("id", UUID.randomUUID().toString())
                .put("premise", premise).put("demo", demo).put("current", "")
                .put("nodes", new JSONArray()).put("revision", 0)
                .put("correction", "").put("exploring", false);
    }

    /** 返回仅包含允许字段的新节点，忽略模型提供的标识和媒体字段。 */
    public static JSONObject validateNode(JSONObject raw) throws JSONException {
        requireObject(raw);
        String title = string(raw, "title", 100, false);
        String text = string(raw, "text", 6000, false);
        String scene = string(raw, "scene_type", Integer.MAX_VALUE, false);
        if (!scene.equals("opening") && !scene.equals("conflict")
                && !scene.equals("consequence") && !scene.equals("transition")
                && !scene.equals("ending")) {
            throw new JSONException("scene_type 无效");
        }
        String imagePrompt = string(raw, "image_prompt", 2000, true);
        JSONArray rawRoles = array(raw, "roles");
        if (rawRoles.length() > 4) {
            throw new JSONException("角色不得超过 4 个");
        }
        JSONArray roles = new JSONArray();
        Set<String> names = new HashSet<>();
        int roleLength = 0;
        for (int i = 0; i < rawRoles.length(); i++) {
            JSONObject role = objectAt(rawRoles, i);
            String name = string(role, "name", 500, false).trim();
            String description = string(role, "description", 500, true);
            roleLength += string(role, "name", 500, false).length() + description.length();
            if (!names.add(name) || roleLength > 500) {
                throw new JSONException("角色名称重复或角色总长度超过 500 字符");
            }
            roles.put(new JSONObject().put("name", name).put("description", description));
        }
        JSONArray rawScripts = array(raw, "scripts");
        if (rawScripts.length() == 0 && !scene.equals("transition")) {
            throw new JSONException("非过渡场景必须包含台词");
        }
        // 统计完整输入数组的长度，包括 JSON 键名、标点和额外字段。
        String serializedScripts = rawScripts.toString();
        if (serializedScripts == null || serializedScripts.length() > 1000) {
            throw new JSONException("完整台词 JSON 不得超过 1000 字符");
        }
        JSONArray scripts = new JSONArray();
        for (int i = 0; i < rawScripts.length(); i++) {
            JSONObject script = objectAt(rawScripts, i);
            String line = string(script, "text", 1000, false);
            JSONObject validatedScript = new JSONObject();
            if (script.has("speaker")) {
                String speaker = string(script, "speaker", 500, false).trim();
                if (!names.contains(speaker)) {
                    throw new JSONException("台词 speaker 必须对应已声明角色");
                }
                validatedScript.put("speaker", speaker);
            } else {
                String sound = line.trim();
                if (!sound.startsWith("[") || !sound.endsWith("]")
                        || sound.substring(1, sound.length() - 1).trim().isEmpty()) {
                    throw new JSONException("无 speaker 的音效必须为非空方括号环境描述");
                }
            }
            scripts.put(validatedScript.put("text", line));
        }
        JSONArray rawOptions = array(raw, "options");
        if (scene.equals("ending") ? rawOptions.length() != 0
                : rawOptions.length() < 2 || rawOptions.length() > 3) {
            throw new JSONException("结局必须没有选项，其他场景须有 2 至 3 个选项");
        }
        Set<String> choices = new HashSet<>();
        JSONArray options = new JSONArray();
        for (int i = 0; i < rawOptions.length(); i++) {
            Object value = rawOptions.get(i);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()
                    || !choices.add(((String) value).trim())) {
                throw new JSONException("选项必须是互不重复的非空字符串");
            }
            options.put(((String) value).trim());
        }
        return new JSONObject().put("title", title).put("text", text)
                .put("scene_type", scene).put("image_prompt", imagePrompt)
                .put("roles", roles).put("scripts", scripts).put("options", options);
    }

    public static JSONObject current(JSONObject story) throws JSONException {
        String id = string(story, "current", Integer.MAX_VALUE, true);
        return id.isEmpty() ? null : node(story, id);
    }

    public static JSONObject node(JSONObject story, String id) throws JSONException {
        if (id == null || id.isEmpty()) {
            throw new JSONException("节点不存在");
        }
        JSONArray nodes = array(story, "nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject candidate = objectAt(nodes, i);
            if (id.equals(string(candidate, "id", Integer.MAX_VALUE, false))) {
                return candidate;
            }
        }
        throw new JSONException("节点不存在");
    }

    public static JSONObject existingChild(JSONObject story, String parentId, int option)
            throws JSONException {
        String parent = parentId == null ? "" : parentId;
        if (parent.isEmpty()) {
            if (option != -1) {
                throw new JSONException("根节点选项必须为 -1");
            }
        } else {
            checkOption(node(story, parent), option);
        }
        JSONArray nodes = array(story, "nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject candidate = objectAt(nodes, i);
            if (parent.equals(string(candidate, "parent", Integer.MAX_VALUE, true))
                    && option == integer(candidate, "option")) {
                return candidate;
            }
        }
        return null;
    }

    public static JSONObject existingCustomChild(JSONObject story, String parentId, String action)
            throws JSONException {
        validateStory(story);
        String choice = customAction(action);
        JSONObject parent = node(story, parentId);
        if ("ending".equals(parent.getString("scene_type"))) {
            throw new JSONException("结局节点不能继续自由行动");
        }
        JSONArray nodes = array(story, "nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject candidate = objectAt(nodes, i);
            if (parentId.equals(candidate.getString("parent"))
                    && integer(candidate, "option") == -2
                    && choice.equals(candidate.getString("customAction"))) {
                return candidate;
            }
        }
        return null;
    }

    static String customAction(String action) throws JSONException {
        if (action == null || action.trim().isEmpty() || action.trim().length() > 500) {
            throw new JSONException("自由行动须为 1 至 500 字符");
        }
        return action.trim();
    }

    public static JSONObject commitCustom(JSONObject story, String parentId, String action,
                                          String reason, JSONObject raw) throws JSONException {
        return commitNode(story, parentId, -2, customAction(action), reason, raw);
    }

    public static JSONObject commit(JSONObject story, String parentId, int option,
                                    String reason, JSONObject raw) throws JSONException {
        return commitNode(story, parentId, option, null, reason, raw);
    }

    private static JSONObject commitNode(JSONObject story, String parentId, int option,
                                         String action, String reason, JSONObject raw) throws JSONException {
        validateStory(story);
        String parentKey = parentId == null ? "" : parentId;
        JSONArray nodes = array(story, "nodes");
        JSONObject parent = null;
        String choice = "";
        int depth = 0;
        boolean exploration = bool(story, "exploring");
        if (parentKey.isEmpty()) {
            if (nodes.length() != 0 || option != -1) {
                throw new JSONException("只能为空故事创建根节点，且选项必须为 -1");
            }
        } else {
            parent = node(story, parentKey);
            exploration = exploration || bool(parent, "exploration");
            JSONObject existing;
            if (action != null) {
                existing = existingCustomChild(story, parentKey, action);
                choice = action;
            } else {
                checkOption(parent, option);
                existing = existingChild(story, parentKey, option);
                choice = array(parent, "options").getString(option);
            }
            if (existing != null) {
                goTo(story, existing.getString("id"));
                return existing;
            }
            int parentDepth = integer(parent, "depth");
            if (parentDepth == Integer.MAX_VALUE) {
                throw new JSONException("故事深度超过限制");
            }
            depth = parentDepth + 1;
            for (int i = 0; i < nodes.length(); i++) {
                if (parentKey.equals(objectAt(nodes, i).getString("parent"))) {
                    exploration = true;
                    break;
                }
            }
        }
        String actualReason = reason == null ? "" : reason;
        if (actualReason.length() > 2000) {
            throw new JSONException("选择理由不得超过 2000 字符");
        }
        int revision = integer(story, "revision");
        if (revision == Integer.MAX_VALUE) {
            throw new JSONException("故事版本超过限制");
        }
        if (nodes.length() >= 1000) {
            throw new JSONException("故事节点已达安全容量上限（1000），不能继续新增");
        }
        JSONObject next = validateNode(raw);
        boolean aiBranch = action != null || parent != null && parent.optBoolean("aiBranch", false);
        boolean demo = bool(story, "demo") && !aiBranch;
        if (aiBranch) {
            next.put("aiBranch", true);
        }
        if (action != null) {
            next.put("customAction", action);
        }
        next.put("id", UUID.randomUUID().toString()).put("parent", parentKey)
                .put("option", option).put("choice", choice).put("reason", actualReason)
                .put("exploration", exploration).put("depth", depth)
                .put("imageStatus", demo || next.getString("scene_type").equals("transition")
                        ? "skipped" : "pending")
                .put("audioStatus", demo && "mbti".equals(story.optString("projectTemplate"))
                        ? "failed" : demo || next.getJSONArray("scripts").length() == 0
                        ? "skipped" : "pending")
                .put("imagePath", "").put("audioPath", "");
        JSONArray updated = new JSONArray();
        for (int i = 0; i < nodes.length(); i++) {
            updated.put(nodes.get(i));
        }
        updated.put(next);
        JSONObject review = staleReview(story);
        JSONObject snapshot = parse(story.toString());
        snapshot.put("nodes", new JSONArray(updated.toString()))
                .put("current", next.getString("id")).put("revision", revision + 1);
        if (review != null) {
            snapshot.put("review", review);
        }
        validateStory(snapshot);
        // 所有校验和可能失败的构造都在修改调用方快照之前完成。
        story.put("nodes", updated);
        story.put("current", next.getString("id"));
        story.put("revision", revision + 1);
        if (review != null) {
            story.put("review", review);
        }
        return next;
    }

    /** 按根节点到目标节点的顺序返回节点对象，而非仅返回节点标识。 */
    public static JSONArray path(JSONObject story, String id) throws JSONException {
        JSONArray result = new JSONArray();
        if (id != null && id.isEmpty() && array(story, "nodes").length() == 0) {
            return result;
        }
        Map<String, JSONObject> indexed = index(array(story, "nodes"));
        List<JSONObject> reversed = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String cursor = id;
        while (cursor != null && !cursor.isEmpty()) {
            if (!visited.add(cursor)) {
                throw new JSONException("故事路径存在循环");
            }
            JSONObject entry = indexed.get(cursor);
            if (entry == null) {
                throw new JSONException("故事路径包含不存在的节点");
            }
            reversed.add(entry);
            cursor = string(entry, "parent", Integer.MAX_VALUE, true);
        }
        if (reversed.isEmpty()) {
            throw new JSONException("节点不存在");
        }
        for (int i = reversed.size() - 1; i >= 0; i--) {
            result.put(reversed.get(i));
        }
        return result;
    }

    public static JSONArray evidence(JSONObject story, boolean allBranches) throws JSONException {
        validateStory(story);
        JSONArray selected = allBranches ? array(story, "nodes")
                : path(story, story.getString("current"));
        Map<String, JSONObject> indexed = index(array(story, "nodes"));
        JSONArray result = new JSONArray();
        for (int i = 0; i < selected.length(); i++) {
            JSONObject destination = objectAt(selected, i);
            String parentId = destination.getString("parent");
            if (parentId.isEmpty()) {
                continue;
            }
            JSONObject source = indexed.get(parentId);
            result.put(new JSONObject().put("id", destination.getString("id"))
                    .put("source", parentId).put("choice", destination.getString("choice"))
                    .put("reason", destination.getString("reason"))
                    .put("exploration", destination.getBoolean("exploration"))
                    .put("context", source.getString("text"))
                    .put("outcome", destination.getString("text")));
        }
        return result;
    }

    public static void goTo(JSONObject story, String nodeId) throws JSONException {
        validateStory(story);
        JSONObject target = node(story, nodeId);
        path(story, nodeId);
        JSONObject review = staleReview(story);
        story.put("current", target.getString("id"));
        story.put("exploring", true);
        if (review != null) {
            story.put("review", review);
        }
    }

    private static JSONObject staleReview(JSONObject story) throws JSONException {
        if (!story.has("review")) {
            return null;
        }
        Object value = story.get("review");
        if (!(value instanceof JSONObject)) {
            throw new JSONException("review 必须是对象");
        }
        return parse(value.toString()).put("stale", true);
    }

    private static void validateStory(JSONObject story) throws JSONException {
        requireObject(story);
        uuid(string(story, "id", 36, false));
        string(story, "premise", Integer.MAX_VALUE, true);
        bool(story, "demo");
        bool(story, "exploring");
        string(story, "correction", Integer.MAX_VALUE, true);
        if (integer(story, "revision") < 0) {
            throw new JSONException("revision 不得为负数");
        }
        if (story.has("review")) {
            Object review = story.get("review");
            if (!(review instanceof JSONObject)) {
                throw new JSONException("review 必须是对象");
            }
            if (((JSONObject) review).has("stale")) {
                bool((JSONObject) review, "stale");
            }
        }
        JSONArray nodes = array(story, "nodes");
        Map<String, JSONObject> indexed = index(nodes);
        String currentId = string(story, "current", 36, true);
        if (nodes.length() == 0 ? !currentId.isEmpty() : !indexed.containsKey(currentId)) {
            throw new JSONException("当前节点无效");
        }
        int roots = 0;
        Set<String> edges = new HashSet<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject entry = objectAt(nodes, i);
            uuid(entry.getString("id"));
            validateNode(entry);
            String parentId = string(entry, "parent", 36, true);
            int option = integer(entry, "option");
            int depth = integer(entry, "depth");
            String choice = string(entry, "choice", Integer.MAX_VALUE, true);
            string(entry, "reason", 2000, true);
            bool(entry, "exploration");
            boolean aiBranch = entry.has("aiBranch") && bool(entry, "aiBranch");
            if (option == -2) {
                String action = string(entry, "customAction", 500, false);
                if (!action.equals(customAction(action)) || !choice.equals(action) || !aiBranch) {
                    throw new JSONException("自由行动节点元数据无效");
                }
            } else if (entry.has("customAction")) {
                throw new JSONException("普通节点不得包含自由行动字段");
            }
            string(entry, "imageStatus", Integer.MAX_VALUE, false);
            string(entry, "audioStatus", Integer.MAX_VALUE, false);
            string(entry, "imagePath", Integer.MAX_VALUE, true);
            string(entry, "audioPath", Integer.MAX_VALUE, true);
            if (parentId.isEmpty()) {
                roots++;
                if (option != -1 || depth != 0 || !choice.isEmpty() || aiBranch) {
                    throw new JSONException("根节点元数据无效");
                }
            } else {
                JSONObject parent = indexed.get(parentId);
                if (parent == null || parentId.equals(entry.getString("id"))) {
                    throw new JSONException("父节点无效");
                }
                String edge = parentId + ":" + option;
                if (option == -2) {
                    if ("ending".equals(parent.getString("scene_type"))) {
                        throw new JSONException("结局节点不能继续自由行动");
                    }
                    edge += ":" + choice;
                } else {
                    checkOption(parent, option);
                    if (!choice.equals(array(parent, "options").getString(option))
                            || aiBranch != parent.optBoolean("aiBranch", false)) {
                        throw new JSONException("普通选项或 AI 分支继承无效");
                    }
                }
                if (depth < 1 || (long) integer(parent, "depth") + 1 != depth
                        || !edges.add(edge)) {
                    throw new JSONException("父子关系、选项或深度无效");
                }
            }
        }
        // 唯一根节点与严格递增的非负深度也能排除不相连的循环。
        if (nodes.length() != 0 && roots != 1) {
            throw new JSONException("故事必须恰有一个根节点");
        }
    }

    private static Map<String, JSONObject> index(JSONArray nodes) throws JSONException {
        Map<String, JSONObject> result = new HashMap<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject entry = objectAt(nodes, i);
            String id = string(entry, "id", Integer.MAX_VALUE, false);
            if (result.put(id, entry) != null) {
                throw new JSONException("节点 ID 重复");
            }
        }
        return result;
    }

    private static void checkOption(JSONObject parent, int option) throws JSONException {
        JSONArray options = array(parent, "options");
        if (option < 0 || option >= options.length()) {
            throw new JSONException("选择的选项无效");
        }
    }

    private static JSONObject parse(String text) throws JSONException {
        if (text == null) {
            throw new JSONException("无法序列化 JSON 对象");
        }
        JSONTokener tokener = new JSONTokener(text);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
            throw new JSONException("必须为单个 JSON 对象");
        }
        return (JSONObject) value;
    }

    private static void requireObject(JSONObject object) throws JSONException {
        if (object == null) {
            throw new JSONException("缺少 JSON 对象");
        }
    }

    private static String string(JSONObject object, String key, int max, boolean allowEmpty)
            throws JSONException {
        requireObject(object);
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).length() > max
                || (!allowEmpty && ((String) value).trim().isEmpty())) {
            throw new JSONException(key + " 必须为有效字符串且不超过长度限制");
        }
        return (String) value;
    }

    private static boolean bool(JSONObject object, String key) throws JSONException {
        requireObject(object);
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) {
            throw new JSONException(key + " 必须为布尔值");
        }
        return (Boolean) value;
    }

    private static int integer(JSONObject object, String key) throws JSONException {
        requireObject(object);
        Object value = object.opt(key);
        if (!(value instanceof Number)) {
            throw new JSONException(key + " 必须为整数");
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                || number != Math.rint(number)) {
            throw new JSONException(key + " 必须为有效整数");
        }
        return (int) number;
    }

    private static JSONArray array(JSONObject object, String key) throws JSONException {
        requireObject(object);
        JSONArray value = object.optJSONArray(key);
        if (value == null) {
            throw new JSONException(key + " 必须为数组");
        }
        return value;
    }

    private static JSONObject objectAt(JSONArray array, int index) throws JSONException {
        JSONObject value = array.optJSONObject(index);
        if (value == null) {
            throw new JSONException("数组元素必须为对象");
        }
        return value;
    }

    private static void uuid(String value) throws JSONException {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new JSONException("存档 ID 格式无效");
        }
    }
}
