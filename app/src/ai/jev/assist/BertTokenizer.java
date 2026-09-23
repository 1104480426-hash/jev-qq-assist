package ai.jev.assist;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT WordPiece 分词器的纯 Java 实现，不依赖 Android，便于在桌面上与
 * HuggingFace 的 tokenizer 对拍验证。
 *
 * <p>行为对齐下载到的 tokenizer.json：
 * BertNormalizer(clean_text=true, handle_chinese_chars=true, lowercase=false,
 * strip_accents=null) + BertPreTokenizer + WordPiece + [CLS]A[SEP] 模板。
 */
public final class BertTokenizer {

    private static final int MAX_INPUT_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int clsId;
    private final int sepId;
    private final int unkId;

    private BertTokenizer(Map<String, Integer> vocab, int clsId, int sepId, int unkId) {
        this.vocab = vocab;
        this.clsId = clsId;
        this.sepId = sepId;
        this.unkId = unkId;
    }

    /** 从任意 Reader 载入词表，一行一个 token，行号即 id。 */
    public static BertTokenizer fromReader(Reader source) throws IOException {
        Map<String, Integer> vocab = new HashMap<>(32768);
        BufferedReader reader = new BufferedReader(source);
        String line;
        int index = 0;
        while ((line = reader.readLine()) != null) {
            vocab.put(line, index);
            index++;
        }
        return new BertTokenizer(vocab, id(vocab, "[CLS]"), id(vocab, "[SEP]"), id(vocab, "[UNK]"));
    }

    private static int id(Map<String, Integer> vocab, String token) {
        Integer v = vocab.get(token);
        return v == null ? 0 : v;
    }

    public int vocabSize() {
        return vocab.size();
    }

    /** 把文本编码成 [CLS] ... [SEP]，超过 maxLen 从尾部截断并保住 [SEP]。 */
    public int[] encode(String text, int maxLen) {
        List<Integer> ids = new ArrayList<>();
        ids.add(clsId);
        for (String token : basicTokenize(text)) {
            for (String piece : wordpiece(token)) {
                Integer v = vocab.get(piece);
                ids.add(v == null ? unkId : v);
            }
        }
        ids.add(sepId);

        if (ids.size() > maxLen) {
            ids = new ArrayList<>(ids.subList(0, maxLen));
            ids.set(maxLen - 1, sepId);
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    /** Reserve the question first, then include complete messages from newest to oldest.
     * Only an oversized newest message is clipped, keeping its end instead of old history.
     */
    public int[] encodeConversation(String context, String question, int maxLen) {
        List<Integer> prefix = tokenIds("聊天记录：\n");
        List<Integer> suffix = tokenIds(question);
        int budget = maxLen - 2 - prefix.size() - suffix.size();
        if (budget < 1) {
            throw new IllegalArgumentException("Question leaves no room for conversation");
        }
        List<Integer> messages = new ArrayList<>();
        String[] lines = context.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].trim().isEmpty()) continue;
            List<Integer> line = tokenIds(lines[i]);
            if (line.size() > budget - messages.size()) {
                if (messages.isEmpty()) {
                    messages.addAll(line.subList(line.size() - budget, line.size()));
                }
                break;
            }
            messages.addAll(0, line);
        }
        int[] out = new int[2 + prefix.size() + messages.size() + suffix.size()];
        int at = 0;
        out[at++] = clsId;
        for (int id : prefix) out[at++] = id;
        for (int id : messages) out[at++] = id;
        for (int id : suffix) out[at++] = id;
        out[at] = sepId;
        return out;
    }

    private List<Integer> tokenIds(String text) {
        List<Integer> ids = new ArrayList<>();
        for (String token : basicTokenize(text)) {
            for (String piece : wordpiece(token)) {
                Integer id = vocab.get(piece);
                ids.add(id == null ? unkId : id);
            }
        }
        return ids;
    }

    // ---- BertNormalizer + BertPreTokenizer + WordPiece ----

    private List<String> basicTokenize(String text) {
        String spaced = handleChineseChars(cleanText(text));
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = spaced.length();
        while (i < n) {
            char c = spaced.charAt(i);
            if (isWhitespace(c)) {
                i++;
                continue;
            }
            if (isPunctuation(c)) {
                out.add(String.valueOf(c));
                i++;
                continue;
            }
            int start = i;
            while (i < n && !isWhitespace(spaced.charAt(i)) && !isPunctuation(spaced.charAt(i))) {
                i++;
            }
            if (i > start) {
                out.add(spaced.substring(start, i));
            }
        }
        return out;
    }

    /** clean_text：控制字符丢弃，空白统一成半角空格。 */
    private String cleanText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0 || c == 0xFFFD || isControl(c)) {
                continue;
            }
            sb.append(isWhitespace(c) ? ' ' : c);
        }
        return sb.toString();
    }

    /** handle_chinese_chars：CJK 字符两侧各补一个空格。 */
    private String handleChineseChars(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c)) {
                sb.append(' ').append(c).append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 贪心最长匹配；非首片段加 ## 前缀。 */
    private List<String> wordpiece(String word) {
        List<String> out = new ArrayList<>(4);
        if (word.length() > MAX_INPUT_CHARS_PER_WORD) {
            out.add("[UNK]");
            return out;
        }
        int start = 0;
        int n = word.length();
        while (start < n) {
            int end = n;
            String matched = null;
            while (start < end) {
                String sub = word.substring(start, end);
                if (start > 0) {
                    sub = "##" + sub;
                }
                if (vocab.containsKey(sub)) {
                    matched = sub;
                    break;
                }
                end--;
            }
            if (matched == null) {
                out.clear();
                out.add("[UNK]");
                return out;
            }
            out.add(matched);
            start = end;
        }
        return out;
    }

    private static boolean isWhitespace(char c) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            return true;
        }
        return Character.getType(c) == Character.SPACE_SEPARATOR;
    }

    private static boolean isControl(char c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    private static boolean isPunctuation(char c) {
        int type = Character.getType(c);
        if (type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION) {
            return true;
        }
        return (c >= 33 && c <= 47) || (c >= 58 && c <= 64)
                || (c >= 91 && c <= 96) || (c >= 123 && c <= 126);
    }

    /** 与 HuggingFace BasicTokenizer 的 _is_chinese_char 等价。 */
    private static boolean isChinese(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x20000 && c <= 0x2A6DF)
                || (c >= 0x2A700 && c <= 0x2B73F)
                || (c >= 0x2B740 && c <= 0x2B81F)
                || (c >= 0x2B820 && c <= 0x2CEAF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x2F800 && c <= 0x2FA1F);
    }
}
