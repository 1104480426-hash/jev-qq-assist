package ai.jev.assist;

/**
 * 本地模式的判定规格。
 *
 * <p>远端模式用英文 questions，因为 TypeSafe 官方明确英文准确度最好；
 * 本地模式换成中文：句向量模型是中文优化的，且候选描述越具体，余弦相似度的区分度越好。
 * 键名与 {@link DecisionSpec} 保持一致，两种模式的渲染层可以共用。
 */
public final class LocalDecisionSpec {

    /** 一个判定问题：问什么、答案有哪些候选。 */
    public static final class Question {
        public final String key;
        public final String type;
        public final String ask;
        public final String[] optionKeys;
        public final String[] options;

        Question(String key, String type, String ask, String[] optionKeys, String[] options) {
            this.key = key;
            this.type = type;
            this.ask = ask;
            this.optionKeys = optionKeys;
            this.options = options;
        }
    }

    private LocalDecisionSpec() {
    }

    public static final Question[] QUESTIONS = {

            new Question("awaiting_reply", "noul",
                    "对方现在是不是在等我的回复？",
                    new String[]{"true", "false"},
                    new String[]{
                            "对方发完消息在等我回，我一直不回会让对方觉得被冷落或者故意不理人",
                            "对方没有在等我回复，这个话题已经聊完了或者并不需要我做出回应",
                    }),

            new Question("intent", "choice",
                    "对方说这些话，最想要的到底是什么？",
                    new String[]{"question", "invitation", "venting", "complaint",
                            "smalltalk", "probing", "closing"},
                    new String[]{
                            "对方想让我回答一个具体的问题，等着我给一个明确的答案",
                            "对方想约我见面或者一起安排做一件什么事，在等我答应",
                            "对方心里难受，只是想找人倾诉一下，需要被听见而不是被讲道理",
                            "对方在表达对我的不满，觉得我哪里做得不好，希望我认错或者改正",
                            "对方只是随便找我聊聊天，没有什么明确的目的",
                            "对方在试探我的态度，想知道我的想法或者我最近有没有空",
                            "对方想把这次对话结束掉，不想继续聊下去了",
                    }),

            new Question("tension", "score",
                    "对方现在的情绪有多激烈？",
                    new String[]{"0", "1", "2", "3"},
                    new String[]{
                            "对方很平静，情绪稳定，只是在正常地说话",
                            "对方有一点点情绪，微微不太高兴或者有点着急",
                            "对方明显不高兴了，情绪已经写在了字里行间",
                            "对方非常生气，正在发火，或者已经忍无可忍快要爆发",
                    }),

            new Question("risk", "noul",
                    "这时候我随便回一句，会不会把事情弄糟？",
                    new String[]{"true", "false"},
                    new String[]{
                            "这句话很容易说错，随便回一句很有可能让对方更不高兴，需要谨慎措辞",
                            "这没什么风险，随便友好地回一句都可以，不会出问题",
                    }),

            new Question("strategy", "choice",
                    "我现在应该用哪种方式回复对方？",
                    new String[]{"warm_comfort", "explain_facts", "playful",
                            "direct_answer", "defer", "hold_distance"},
                    new String[]{
                            "先安抚对方的情绪，表达理解和共情，把对方的感受放在第一位",
                            "平实地解释清楚原因或者说明事情的实际情况，把事实讲明白",
                            "用轻松幽默的方式化解，把紧张的气氛缓和下来",
                            "直接正面回答对方问的问题，不多说别的",
                            "先跟对方说一声收到了，说明稍后再认真回复，不要让对方干等",
                            "简短地回应一下，保持距离，不过多展开这个话题",
                    }),
    };

    /** 候选向量用的文本。描述本身就是完整句子，直接编码。 */
    static String optionText(Question q, int index) {
        return q.options[index];
    }
}
