package ai.jev.assist;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 聊天场景的判定规格。
 *
 * <p>Jev 的 instructions 与 criteria 以英文填写：官方明确英文是主要训练语言、
 * 准确度最好，CJK 属于「handled but not equally well」。被判定内容（state）仍是中文原文，
 * 这里只把「问题本身」写成英文。
 */
public final class DecisionSpec {

    private DecisionSpec() {
    }

    /** 与判定键一一对应的中文标签，按显示顺序排列。 */
    static final String[][] LABELS = {
            {"awaiting_reply", "是否在等回复"},
            {"intent", "对方意图"},
            {"tension", "情绪强度"},
            {"risk", "说错话风险"},
            {"strategy", "推荐回复策略"},
    };

    /** 候选值英文键 -> 中文显示名。 */
    private static final String[][] CHOICE_ZH = {
            {"question", "提问待答"},
            {"invitation", "邀约/见面"},
            {"venting", "倾诉情绪"},
            {"complaint", "表达不满"},
            {"smalltalk", "闲聊搭话"},
            {"probing", "试探态度"},
            {"closing", "想结束对话"},
            {"warm_comfort", "共情安抚"},
            {"explain_facts", "说明情况"},
            {"playful", "幽默化解"},
            {"direct_answer", "直接回应"},
            {"defer", "稍后再说"},
            {"hold_distance", "保持距离"},
    };

    private static final String[] SCORE_LEGEND = {
            "平静", "略有情绪", "明显不快", "已经很生气",
    };

    /** 转录少于这么多行就认为上下文不足。 */
    private static final int MIN_CONTEXT_LINES = 4;

    /**
     * 组装一次判定的请求体。
     *
     * @param model     模型名，服务端通常忽略
     * @param transcript 已排版好的对话文本，每行形如「对方：...」或「我：...」
     */
    public static JSONObject build(String model, String transcript) {
        JSONObject body = new JSONObject();
        JSONObject questions = new JSONObject();
        try {
            body.put("model", model);
            body.put("state", transcript);

            JSONObject awaiting = new JSONObject();
            awaiting.put("type", "noul");
            awaiting.put("instructions",
                    "From the other person's last message, are they waiting for a reply right now? "
                            + "True if ignoring it would read as being left on read.");
            awaiting.put("criteria", new JSONObject()
                    .put("true", "they are waiting for an answer")
                    .put("false", "no reply is expected, it is settled or one-way"));
            questions.put("awaiting_reply", awaiting);

            JSONObject intent = new JSONObject();
            intent.put("type", "choice");
            intent.put("instructions", "What does the other person most want from this exchange?");
            intent.put("criteria", new JSONObject()
                    .put("question", "an answer to a concrete question")
                    .put("invitation", "to meet up or make a plan")
                    .put("venting", "to be heard, not fixed")
                    .put("complaint", "to register dissatisfaction with me")
                    .put("smalltalk", "casual contact, no agenda")
                    .put("probing", "testing my attitude or availability")
                    .put("closing", "winding the conversation down"));
            questions.put("intent", intent);

            JSONObject tension = new JSONObject();
            tension.put("type", "score");
            tension.put("instructions", "How tense or upset is the other person right now?");
            JSONArray legend = new JSONArray();
            for (String level : new String[]{"calm", "mildly unhappy", "clearly upset", "very angry"}) {
                legend.put(level);
            }
            tension.put("criteria", legend);
            questions.put("tension", tension);

            JSONObject risk = new JSONObject();
            risk.put("type", "noul");
            risk.put("instructions",
                    "Is there a real risk that a careless or casual reply makes this worse?");
            risk.put("criteria", new JSONObject()
                    .put("true", "words could easily land wrong here")
                    .put("false", "this is low stakes, any friendly reply is fine"));
            questions.put("risk", risk);

            JSONObject strategy = new JSONObject();
            strategy.put("type", "choice");
            strategy.put("instructions",
                    "Which reply strategy fits this conversation best? Pick the single best one.");
            strategy.put("criteria", new JSONObject()
                    .put("warm_comfort", "lead with empathy, acknowledge the feeling first")
                    .put("explain_facts", "give the reason or the facts plainly")
                    .put("playful", "lighten it with humour")
                    .put("direct_answer", "just answer the question")
                    .put("defer", "acknowledge now, answer properly later")
                    .put("hold_distance", "keep it brief and non-committal"));
            questions.put("strategy", strategy);

            body.put("questions", questions);
        } catch (Exception e) {
            throw new IllegalStateException("组装判定请求失败", e);
        }
        return body;
    }

    /** 把一条判定渲染成「标签：结论 (置信度)」的单行文本。 */
    static String renderOne(String key, JSONObject answer) {        if (answer == null) {
            return zh(key) + "：无返回";
        }
        String type = answer.optString("type", "");
        if (type.length() == 0) {
            // 服务端未回填 type 时按字段反推
            if (answer.has("noul")) {
                type = "noul";
            } else if (answer.has("choice")) {
                type = "choice";
            } else if (answer.has("score")) {
                type = "score";
            }
        }
        if ("noul".equals(type)) {
            double p = answer.optDouble("noul", Double.NaN);
            if (Double.isNaN(p)) {
                return zh(key) + "：无返回";
            }
            return zh(key) + "：" + (p >= 0.5 ? "是" : "否") + "  " + pct(p);
        }
        if ("choice".equals(type)) {
            String choice = answer.optString("choice", "?");
            double conf = answer.optDouble("confidence", Double.NaN);
            String tail = Double.isNaN(conf) ? "" : "  " + pct(conf);
            return zh(key) + "：" + zhValue(choice) + tail;
        }
        if ("score".equals(type)) {
            double score = answer.optDouble("score", Double.NaN);
            if (Double.isNaN(score)) {
                return zh(key) + "：无返回";
            }
            int idx = (int) Math.round(score);
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= SCORE_LEGEND.length) {
                idx = SCORE_LEGEND.length - 1;
            }
            return zh(key) + "：" + SCORE_LEGEND[idx] + "  (" + trimNum(score) + "/"
                    + (SCORE_LEGEND.length - 1) + ")";
        }
        return zh(key) + "：无法识别的类型";
    }

    /** 组装整块结果文本，顺序遵循 LABELS。 */
    static String renderAll(JSONObject answers) {
        StringBuilder sb = new StringBuilder();
        for (String[] pair : LABELS) {
            String line = renderOne(pair[0], answers.optJSONObject(pair[0]));
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** 挑出一句最该先看的结论，用于悬浮球震动前的提示。 */
    static String headline(JSONObject answers) {
        JSONObject strategy = answers.optJSONObject("strategy");
        JSONObject tension = answers.optJSONObject("tension");
        String s = strategy == null ? "?" : zhValue(strategy.optString("choice", "?"));
        double score = tension == null ? -1 : tension.optDouble("score", -1);
        String mood = "";
        if (score >= 0) {
            int idx = (int) Math.round(score);
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= SCORE_LEGEND.length) {
                idx = SCORE_LEGEND.length - 1;
            }
            mood = " · 情绪 " + SCORE_LEGEND[idx];
        }
        return "建议：" + s + mood;
    }

    /**
     * 胶囊上那一行摘要：策略 + 情绪 + 风险百分比。
     * 比 headline 再短一档，因为它要贴在悬浮球旁边，占屏越小越好。
     */
    static String pillSummary(JSONObject answers) {
        StringBuilder sb = new StringBuilder();

        JSONObject strategy = answers.optJSONObject("strategy");
        if (strategy != null) {
            String choice = strategy.optString("choice", "");
            if (choice.length() > 0) {
                sb.append(zhValue(choice));
            }
        }

        JSONObject tension = answers.optJSONObject("tension");
        if (tension != null) {
            double score = tension.optDouble("score", -1);
            if (score >= 0) {
                int idx = (int) Math.round(score);
                idx = Math.max(0, Math.min(SCORE_LEGEND.length - 1, idx));
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(SCORE_LEGEND[idx]);
            }
        }

        JSONObject risk = answers.optJSONObject("risk");
        if (risk != null) {
            double p = risk.optDouble("noul", -1);
            if (p >= 0) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append("风险 ").append(Math.round(p * 100)).append('%');
            }
        }

        return sb.length() > 0 ? sb.toString() : "判定完成";
    }

    /** 语气重不重：决定胶囊上那个点是青的还是橙的。 */
    static boolean isRisky(JSONObject answers) {
        JSONObject risk = answers.optJSONObject("risk");
        JSONObject tension = answers.optJSONObject("tension");
        double r = risk == null ? 0 : risk.optDouble("noul", 0);
        double t = tension == null ? 0 : tension.optDouble("score", 0);
        return r >= 0.6 || t >= 2.0;
    }

    /**
     * 悬浮球该染哪一档：0 绿、1 黄、2 红。
     *
     * <p>两条线是从 36 段真实对话的分布上标出来的。risk 呈双峰：低峰 0.2-0.5（13 段），
     * 高峰 0.7-0.9（21 段），谷底落在 0.5-0.6 之间。沿用胶囊那个 risk >= 0.6 会让
     * 三分之二的对话都亮灯，一个大多数时候都亮的警告等于没有警告；取 0.85 当红线，
     * 亮灯比例降到 22%，剩下的正好是"抱怨升级 / 当面难堪 / 直接发火"那一类。
     *
     * <p>tension 不单独参与分档。实测 tension >= 1.5 的 11 段里 risk 全部 >= 0.70，
     * 它给不出额外信息；两个条件一起判只会互相打架，让颜色更难解释。
     */
    static int colorBand(JSONObject answers) {
        double risk = noul(answers, "risk");
        if (risk < 0) {
            return 0;
        }
        if (risk >= 0.85) {
            return 2;
        }
        return risk >= 0.6 ? 1 : 0;
    }

    // ---- 人话层 ----
    //
    // 原始的 noul/choice/score 是给机器看的：一个「表达不满 25%」摆在用户面前，
    // 他既不知道 25% 是程度还是把握，也不知道该拿它做什么。所以对外只暴露两样东西：
    // 一句能直接照着做的结论，和两句解释。原始判定降级成一行小字依据。

    /** 结论：一句话，直接告诉用户该怎么做。胶囊和卡片标题都用它。 */
    static String plainHeadline(JSONObject answers) {
        double tension = score(answers, "tension");
        double risk = noul(answers, "risk");
        double awaiting = noul(answers, "awaiting_reply");
        String intent = choice(answers, "intent");
        String strategy = choice(answers, "strategy");

        if (tension >= 2 && risk >= 0.6) {
            return "先别急着回";
        }
        if ("complaint".equals(intent) && tension >= 1.5) {
            return "别解释，先认下来";
        }
        if ("venting".equals(intent)) {
            return "先听完，别讲道理";
        }
        if ("invitation".equals(intent)) {
            return "给个准话，别拖着";
        }
        // 策略放在风险前面：风险只说明"这句容易踩雷"，策略才说得出该怎么回。
        // 反过来会让标题说"小心点"、正文却说"简短回一句"，两句话打架。
        if ("warm_comfort".equals(strategy)) {
            return tension >= 1.5 ? "先别急着回" : "先把情绪接住";
        }
        if ("defer".equals(strategy)) {
            return "先应一声，稍后细说";
        }
        if ("hold_distance".equals(strategy)) {
            return "简短回一句就好";
        }
        if ("playful".equals(strategy)) {
            return "可以开个玩笑";
        }
        if ("direct_answer".equals(strategy)) {
            return "直接回答就行";
        }
        if ("explain_facts".equals(strategy)) {
            return "把话说清楚就行";
        }
        if (tension >= 2) {
            return "语气放软一点";
        }
        if (awaiting < 0.5 && tension < 0.5) {
            return "这条可以不用回";
        }
        if (risk >= 0.6) {
            return "想清楚再发";
        }
        return "正常回就行";
    }

    /** 解释：第一句说对方现在什么状态，第二句说具体怎么回。 */
    static String plainAdvice(JSONObject answers) {
        double tension = score(answers, "tension");
        String intent = choice(answers, "intent");
        String strategy = choice(answers, "strategy");

        StringBuilder sb = new StringBuilder();

        // 第一句：对方的处境
        String mood;
        if (tension >= 2.5) {
            mood = "对方正在气头上";
        } else if (tension >= 1.5) {
            mood = "对方已经不太高兴了";
        } else if (tension >= 0.5) {
            mood = "对方有点在意";
        } else {
            mood = "对方情绪正常";
        }
        sb.append(mood);

        String tail;
        if ("complaint".equals(intent)) {
            tail = "，觉得你哪里没做好";
        } else if ("venting".equals(intent)) {
            tail = "，只是想找个人说说";
        } else if ("probing".equals(intent)) {
            tail = "，在试探你的态度";
        } else if ("invitation".equals(intent)) {
            tail = "，在等你答复";
        } else if ("closing".equals(intent)) {
            tail = "，想把话收尾了";
        } else if ("question".equals(intent)) {
            tail = "，在等你给个答案";
        } else if ("smalltalk".equals(intent)) {
            tail = "，没什么正事";
        } else {
            tail = "";
        }
        sb.append(tail).append('。').append('\n');

        // 第二句：怎么做。措辞要和标题岔开——标题已经把结论说了，
        // 这里再复述一遍"简短回一句"就只剩啰嗦。
        if ("warm_comfort".equals(strategy)) {
            sb.append("先说一句安抚的话，让他知道你听进去了。");
        } else if ("explain_facts".equals(strategy)) {
            sb.append("把原因或实情讲明白，不用绕。");
        } else if ("playful".equals(strategy)) {
            sb.append("轻松带过去，别接那股劲。");
        } else if ("direct_answer".equals(strategy)) {
            sb.append("对方问什么就答什么，别扯别的。");
        } else if ("defer".equals(strategy)) {
            sb.append("说一句收到了，并给出你什么时候会认真回。");
        } else if ("hold_distance".equals(strategy)) {
            sb.append("不用展开，也不用解释。");
        } else {
            sb.append("照平常的方式回就可以。");
        }
        return sb.toString();
    }

    /**
     * 依据行：这一行的意义是把"结论是从概率里读出来的"摆出来。
     *
     * <p>所以每一项都带 Jev 给的数：noul 给概率，score 给量表分。没有这些数，卡片就退化
     * 成一段普通的沟通建议——「先听完，别讲道理」跟网上任何一篇技巧文章都分不出彼此。
     * 有数字才看得出它是模型算的，而不是谁写的经验之谈。
     *
     * <p>但数得跟人话一起出现：只说「踩雷风险 88%」没人知道踩什么雷，只说「这句回不好
     * 容易变味」又丢了 88%。两者并排，用户既能懂，又能看出模型有多确定。原来的
     * 「对方意图 倾诉情绪」是纯术语，既没有数也没说人话，那种才该去。
     */
    static String evidence(JSONObject answers) {
        StringBuilder sb = new StringBuilder();

        double awaiting = noul(answers, "awaiting_reply");
        if (awaiting >= 0) {
            sb.append(awaiting >= 0.5 ? "对方在等你回" : "对方没在等");
            sb.append(' ').append(pct(awaiting));
        }

        double risk = noul(answers, "risk");
        if (risk >= 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(risk >= 0.6 ? "这句回不好容易变味" : "随便回一句也没关系");
            sb.append(' ').append(pct(risk));
        }

        double tension = score(answers, "tension");
        if (tension >= 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("情绪 ").append(trimNum(tension)).append('/').append(SCORE_LEGEND.length - 1);
        }

        return sb.toString();
    }

    /**
     * 转录太短时，结论不该照常展示。
     *
     * <p>实测（见 docs/jev-behavior-notes.md）：只喂最后一句时，Jev 会把一段有争执的
     * 对话判成「平静、闲聊」，置信度却高达 0.92；喂到 5 句才收敛到与完整上下文一致的
     * 读数。上下文不足时它的错法是「自信地错」，用户从那个百分比上根本看不出来。
     *
     * <p>阈值取 4 是保守外推：实测点只有 1 / 2 / 5 三条，5 条已经稳定，3 和 4 没测过。
     *
     * @return 警示语；上下文够长时返回 null，调用方照常展示结论
     */
    static String thinContextWarning(int lines) {
        if (lines <= 0 || lines >= MIN_CONTEXT_LINES) {
            return null;
        }
        return "只读到 " + lines + " 条，判定不可靠";
    }

    /** 上面那句警示的展开，放在正文开头。 */
    static String thinContextNote() {
        return "上下文太短时它会给出一个自信但并不对的答案（实测只喂一句时置信度 0.92）。"
                + "等对方把话说完再点一次。下面是它这次的判断，仅供参考：";
    }

    private static double noul(JSONObject answers, String key) {
        JSONObject o = answers.optJSONObject(key);
        return o == null ? -1 : o.optDouble("noul", -1);
    }

    private static double score(JSONObject answers, String key) {
        JSONObject o = answers.optJSONObject(key);
        return o == null ? -1 : o.optDouble("score", -1);
    }

    private static String choice(JSONObject answers, String key) {
        JSONObject o = answers.optJSONObject(key);
        return o == null ? "?" : o.optString("choice", "?");
    }

    private static String zh(String key) {
        for (String[] pair : LABELS) {
            if (pair[0].equals(key)) {
                return pair[1];
            }
        }
        return key;
    }

    private static String zhValue(String value) {
        for (String[] pair : CHOICE_ZH) {
            if (pair[0].equals(value)) {
                return pair[1];
            }
        }
        return value;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String trimNum(double v) {
        String s = String.valueOf(Math.round(v * 100.0) / 100.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
