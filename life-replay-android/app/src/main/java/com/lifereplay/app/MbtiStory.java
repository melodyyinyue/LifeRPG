package com.lifereplay.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MbtiStory {
    private static final String[] TITLES = {"月亮替你回答", "窗边的一条消息", "马与马提尼", "把周末交给远方"};
    private static final String[][] OPTIONS = {
            {"回家后依然轻松，想再参加这样的晚宴", "回家后有些疲惫，需要独处休息"},
            {"啊？为什么？", "我也想你啊"},
            {"马不会说话", "给我来杯马提尼"},
            {"提前做好出游计划", "不做详细计划，随兴出发"}
    };
    private static final String[][] EFFECTS = {
            {"你把晚宴上的笑话又回味了一遍，心里仍亮堂堂的，还给林舟发去一句：下回记得叫我。", "你把外套搭在椅背，调暗灯光，终于舒舒服服地松了口气。晚宴很美好，但此刻安静更合心意。"},
            {"你发出“啊？为什么？”，没有急着猜那句话的意思。阿月认真解释：你上次说过，月亮像一盏不催人回家的灯，我记住了。你看着屏幕，慢慢笑了。", "你回了“我也想你啊”，没有让那份惦念在输入框里等太久。阿月发来一个小月亮，说：那今晚我们就算隔着窗见过面了。你把这句话悄悄收藏。"},
            {"你说“马不会说话”，指了指马头面具下露出的衣领。酒保笑着承认这是旅行主题夜的即兴表演，你看清了机关，也安心地跟着大家笑起来。", "你顺着表演接了一句“给我来杯马提尼”。酒保立刻举起摇壶回应这个谐音梗，林舟笑得伏在吧台上。你们约定只点无酒精版本。"},
            {"你提前查好车次、天气和步行路线，把午餐地点也写进了备忘录。周末出发时，林舟照着你的清单带来雨伞，两个人一路都很从容。", "你没有列详细行程，只和林舟约好碰面的时间与大致方向。周末走到岔路口，你们闻见面包香，就临时拐进了一条漂亮的小街。"}
    };
    private static final String[] SCENES = {
            "晚宴的最后一道甜点是桂花布丁，杯沿映着细碎的金光。朋友林舟介绍你认识了几位有趣的人，你聊得开心，也带回了一张写着书名的小纸条。告别后，你独自回到家，关门声把热闹轻轻留在了外面。此刻的你更像哪一种？",
            "窗外刚下过小雨，月亮从云缝里露出来。你喜欢的阿月发来一张月亮照片，附着一句：“当我看到月亮就想起了你”。屏幕的光落在指尖，你停了一会儿，会怎样回复？",
            "第二天晚上，你和朋友林舟走进一家旅行主题酒吧。墙上挂着旧地图，门口的小黑板写着“今晚即兴表演”。暖黄灯下，一匹戴着礼帽的“马”正和酒保说话——近看才发现是演员戴着马头面具。林舟碰碰你的胳膊：快，你会怎么接这一幕？",
            "离开酒吧时，雨已经停了。林舟指着橱窗里一张海边小城的明信片，提议周末一起去走走。你给阿月拍下明信片，答应到时寄一张。海风还很远，出发的念头却已经落在眼前：这次你想怎么安排？"
    };
    private static final String[] SOUNDS = {
            "[杯盏轻碰与晚宴低语渐远，钥匙转动，关门后室内安静]",
            "[窗外细雨余滴，手机轻响，窗帘微动]",
            "[酒吧人群低语，冰块轻碰，摇壶轻响，零星笑声]",
            "[雨后街道脚步，远处车声，明信片纸张轻响]",
            "[海风与轻柔浪声，纸张翻动，远处海鸥一声]"
    };
    private static final String[][] ROLE_DATA = {
            {"旁白", "温暖中性的声音，亲切自然，语速舒缓，不入镜。"},
            {"林舟", "爽朗的年轻男声，明亮放松，笑意自然，朋友般亲近。"},
            {"阿月", "轻柔的年轻女声，清澈细腻，略带羞涩，不刻意撒娇。"},
            {"酒保", "低沉带幽默的男声，松弛沉稳，接梗时略带笑意。"}
    };
    private static final String[] TYPES = {
            "ESTJ", "ESTP", "ESFJ", "ESFP", "ENTJ", "ENTP", "ENFJ", "ENFP",
            "ISTJ", "ISTP", "ISFJ", "ISFP", "INTJ", "INTP", "INFJ", "INFP"
    };
    private static final String[] END_TITLES = {
            "把远方安排妥当", "在街角发现晴天", "替大家留一盏灯", "把笑声装进行囊",
            "给月光画一张地图", "让旅途多一个可能", "约好一起看海", "追着灵感去远方",
            "口袋里的安心清单", "安静地走进风里", "把惦念仔细收好", "收藏一刻的温柔",
            "为想象留好坐标", "在月下追问为什么", "通往心意的小径", "寄给月亮的明信片"
    };
    private static final String[] END_DESCRIPTIONS = {
            "在这条路线里，你从相聚中获得活力，重视事实和缘由，也愿意把下一步安排妥当。",
            "这次你享受相聚，留意眼前的真实，也会追问缘由，再把旅程交给临场发现。",
            "在这几次选择里，你喜欢相聚的温度，关注眼前细节，直接回应心意，也用准备照顾旅程。",
            "这条路线上的你接住了热闹与心意，眼睛看着真实的风景，脚步则愿意随兴转弯。",
            "这次你带着相聚后的活力追问缘由，也乐于接住想象，最后把远方写成可行的安排。",
            "在这条路线里，你享受交流，想弄清缘由，也愿意玩一个新点子，让出发保留弹性。",
            "这次你从人群中带回温暖，回应惦念，接住玩笑，还为共同的远方认真做了准备。",
            "这几次选择让你像旅途中的点灯人：享受相聚、回应心意，也给想象和偶遇留下位置。",
            "在这条路线里，你给自己留了安静，核对事实与缘由，再用一份计划让出发更安心。",
            "这次你先在独处里休息，愿意看清事实、问明缘由，出发后则把发现留给脚步。",
            "在这几次选择里，你珍惜安静与具体的小事，轻轻接住心意，也把准备做得周到。",
            "这条路线上的你在安静中恢复精神，回应温柔，留意真实的风景，也愿意随兴停留。",
            "这次你先给自己留白，追问缘由、接住想象，再为想去的地方标好坐标。",
            "在这条路线里，安静让你好好思考；你问清缘由，也欢迎奇思妙想和未定的旅程。",
            "这几次选择里，你在独处后接住惦念，愿意让想象靠近生活，也认真准备下一次相逢。",
            "这次你在安静里蓄满温柔，回应心意、接住想象，把下一段风景留给偶然。"
    };
    private static final String[][] BASIS = {
            {"E：这次更偏向从相聚中获得能量", "I：这次更偏向用独处恢复能量"},
            {"T：这次先询问消息背后的缘由", "F：这次先回应对方表达的心意"},
            {"S：这次先关注眼前事实与表演机关", "N：这次先接住想象和谐音联想"},
            {"J：这次倾向提前安排", "P：这次倾向保留临场弹性"}
    };
    private static final String DISCLAIMER = "这是根据本故事四次二选一生成的娱乐向、非正式人格测评，不是标准 MBTI 测验，也不构成心理或医学诊断，不能准确判定人格。情境和心情都会影响选择；四个字母只是这段旅程的纪念，不是对你的定义。";

    private MbtiStory() { }

    public static boolean isStory(JSONObject story) {
        return story != null && story.optBoolean("demo", false)
                && "mbti".equals(story.optString("projectTemplate"));
    }

    public static JSONObject create() throws JSONException {
        JSONObject story = StoryStore.newStory("从一场晚宴、一条月亮消息，到与朋友出游；四个小选择，留一张自己的旅途侧写。", true)
                .put("projectTitle", "月亮替你回答").put("projectTemplate", "mbti")
                .put("coverArt", "rain_city");
        JSONObject root = StoryStore.commit(story, null, -1, "", node(story, null, -1));
        root.put("demoArt", "rain_city");
        return story;
    }

    public static JSONObject node(JSONObject story, String parentId, int option) throws JSONException {
        if (!isStory(story)) {
            throw new JSONException("不是月亮故事项目");
        }
        boolean root = parentId == null || parentId.isEmpty();
        if (root && (option != -1 || story.getJSONArray("nodes").length() != 0)) {
            throw new JSONException("只能为空故事创建开场");
        }
        JSONArray path = StoryStore.path(story, root ? "" : parentId);
        int depth = path.length();
        if (depth > 4 || (!root && (option < 0 || option > 1))) {
            throw new JSONException("月亮故事只有四轮二选一");
        }
        int[] choices = new int[depth];
        for (int round = 0; round < depth; round++) {
            JSONObject source = path.getJSONObject(round);
            JSONArray offered = source.getJSONArray("options");
            if (source.getInt("depth") != round || offered.length() != 2
                    || !OPTIONS[round][0].equals(offered.getString(0))
                    || !OPTIONS[round][1].equals(offered.getString(1))) {
                throw new JSONException("月亮故事只能续接本幕预制选项");
            }
            int chosen = round == depth - 1 ? option : path.getJSONObject(round + 1).getInt("option");
            if (chosen < 0 || chosen > 1 || (round < depth - 1
                    && !OPTIONS[round][chosen].equals(path.getJSONObject(round + 1).getString("choice")))) {
                throw new JSONException("月亮故事路径选择无效");
            }
            choices[round] = chosen;
        }
        JSONArray roles = new JSONArray();
        for (String[] role : ROLE_DATA) {
            roles.put(new JSONObject().put("name", role[0]).put("description", role[1]));
        }
        JSONArray scripts = new JSONArray().put(new JSONObject().put("text", SOUNDS[depth]));
        StringBuilder text = new StringBuilder();
        if (depth > 0) {
            text.append(EFFECTS[depth - 1][choices[depth - 1]]).append("\n\n");
            say(scripts, "旁白", "（停顿）" + EFFECTS[depth - 1][choices[depth - 1]]);
        }
        String title;
        JSONArray options = new JSONArray();
        if (depth == 4) {
            int result = choices[0] * 8 + choices[2] * 4 + choices[1] * 2 + choices[3];
            title = TYPES[result] + " · " + END_TITLES[result];
            text.append("海边的邮筒被夕阳晒得暖暖的。你把给阿月的明信片投进去，林舟在路边等你。月亮还没升起，这一趟已经有了自己的答案。\n\n")
                    .append("这条路线的四字母纪念：").append(TYPES[result]).append("\n")
                    .append(END_DESCRIPTIONS[result]).append("\n\n实际选择依据（仅当前路线，按答题顺序）：\n");
            for (int round = 0; round < 4; round++) {
                text.append(round + 1).append(". “").append(OPTIONS[round][choices[round]])
                        .append("” → ").append(BASIS[round][choices[round]]).append("。\n");
            }
            text.append("\n结果按 E/I、S/N、T/F、J/P 的顺序排列。\n").append(DISCLAIMER);
            say(scripts, "林舟", choices[3] == 0 ? "（轻笑）有你这份计划，连看海都不慌了。" : "（轻笑）没写进计划的这条小街，也太值得了。" );
            say(scripts, "旁白", "（呼气）你的旅途纪念是“" + TYPES[result] + "”。" + END_DESCRIPTIONS[result]
                    + "这只是四个选择组成的娱乐侧写，不是正式测评或诊断，也不能定义你。月亮会变，你也可以。");
        } else {
            title = TITLES[depth];
            text.append(SCENES[depth]);
            for (String choice : OPTIONS[depth]) {
                options.put(choice);
            }
            switch (depth) {
                case 0:
                    say(scripts, "林舟", "（轻笑）今晚聊得真开心，回家慢点，下次还叫你。" );
                    say(scripts, "旁白", "（呼气）桂花的甜还留在舌尖。晚宴收获满满，关上家门，你还想再聚一次，还是想安静休息？");
                    break;
                case 1:
                    say(scripts, "林舟", choices[0] == 0 ? "（轻笑）收到，下回聚会给你留座！" : "（压低声音）到家就好，好好休息，不用急着回我。" );
                    say(scripts, "阿月", "（停顿）（轻柔）当我看到月亮就想起了你。");
                    say(scripts, "旁白", "照片里的月亮靠着云边。是你喜欢的阿月，你会怎么回复？");
                    break;
                case 2:
                    say(scripts, "阿月", choices[1] == 0 ? "（轻笑）因为你说月亮是不催人回家的灯，我一直记着。" : "（压低声音）我也把这句话，偷偷念了一遍。" );
                    say(scripts, "旁白", "第二天晚上，你和林舟走进旅行主题酒吧。即兴表演里，戴马头面具的演员正和酒保说话。");
                    say(scripts, "酒保", "（轻笑）这位马先生，今晚想来点什么？");
                    say(scripts, "林舟", "（停顿）轮到你接话了！");
                    break;
                default:
                    say(scripts, "酒保", choices[2] == 0 ? "（轻笑）被你看穿了，是演员，马可不会真开口。" : "（轻笑）好一个马提尼！给你调杯无酒精的。" );
                    say(scripts, "林舟", "（呼气）刚才笑得肚子疼。你看这张海边明信片，周末一起去？");
                    say(scripts, "旁白", "雨停了，你答应给阿月寄张明信片。这趟旅行，是提前做好计划，还是不排详细行程、随兴出发？");
                    break;
            }
        }
        return StoryStore.validateNode(new JSONObject().put("title", title).put("text", text.toString())
                .put("scene_type", depth == 0 ? "opening" : depth == 4 ? "ending" : "conflict")
                .put("image_prompt", "").put("roles", roles).put("scripts", scripts).put("options", options));
    }

    public static String instruction(JSONObject node) {
        String title = node.optString("title");
        int depth = node.optInt("depth", 0);
        for (int i = 0; i < TITLES.length; i++) {
            if (TITLES[i].equals(title)) {
                depth = i;
                break;
            }
        }
        if ("ending".equals(node.optString("scene_type"))) {
            depth = 4;
        }
        String[] ambience = {
                "从晚宴杯盏与低语淡出到家中安静，关门后留出一次呼吸的空白。",
                "窗外雨滴很轻，手机提示音只响一次；阿月像隔着屏幕读来信，停顿柔软。",
                "酒吧低语、冰块与摇壶做低音量底色，笑声短促；马是即兴表演，轻松而不惊悚。",
                "用雨后脚步、远处车流及纸张声衔接街边，提起海边时语气明亮。",
                "海风与浪声轻铺底，翻动明信片后逐渐淡出；结尾像朋友道别，不用宣判式口吻。"
        };
        return "中文温暖生活短剧。旁白温暖中性；林舟爽朗年轻男声；阿月轻柔年轻女声；酒保低沉幽默男声，全程声线固定。"
                + "括号内为表演提示，不朗读提示文字；无 speaker 的方括号条目只制作环境声，不作人声朗读。"
                + "人声清晰居前，环境声低于对白，转场平滑，不添加台词、角色或提前透露测评字母。"
                + ambience[Math.max(0, Math.min(depth, 4))];
    }

    private static void say(JSONArray scripts, String speaker, String text) throws JSONException {
        scripts.put(new JSONObject().put("speaker", speaker).put("text", text));
    }
}
