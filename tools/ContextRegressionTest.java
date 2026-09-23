package ai.jev.assist;

import java.io.StringReader;
import java.util.Arrays;

/** Run with tools/test.ps1; no Android runtime or model download required. */
public final class ContextRegressionTest {
    public static void main(String[] args) throws Exception {
        BertTokenizer tokenizer = BertTokenizer.fromReader(new StringReader(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\n旧\n好\n怒\n问\n我\n：\n对\n方\n"));
        String old = repeat("旧", 160);
        int[] calm = conversation(tokenizer, old + "\n对方：好", "问", 128);
        int[] angry = conversation(tokenizer, old + "\n对方：怒", "问", 128);
        require(!Arrays.equals(calm, angry), "latest message must affect model input");
        require(contains(angry, 6), "latest message must survive truncation");
        require(contains(angry, 7), "question must survive truncation");
        require(angry.length <= 128 && angry[0] == 2 && angry[angry.length - 1] == 3,
                "model token budget and special tokens");
        int[] shortInput = conversation(tokenizer, "对方：好", "问", 128);
        require(contains(shortInput, 5) && contains(shortInput, 7), "short input stays intact");
        int[] complete = conversation(tokenizer, "旧旧旧旧\n对方：怒", "问", 10);
        require(!contains(complete, 4) && contains(complete, 6),
                "omit older message instead of including an orphaned fragment");
        System.out.println("PASS: latest message, question, budget, short input, whole messages");
    }

    private static int[] conversation(BertTokenizer tokenizer, String context, String question, int limit) {
        return tokenizer.encodeConversation(context, question, limit);
    }

    private static String repeat(String s, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(s);
        return out.toString();
    }

    private static boolean contains(int[] values, int expected) {
        for (int value : values) if (value == expected) return true;
        return false;
    }

    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
