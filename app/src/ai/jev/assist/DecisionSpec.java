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
    static String renderOne(String key, JSONObject answer) {
        if (answer == null) {
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
