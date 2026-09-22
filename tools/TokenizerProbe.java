package ai.jev.assist;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * 桌面探针：用同一份 vocab.txt 跑出 token id，写进文件与 HuggingFace 对拍。
 * 不参与 APK 打包。
 */
public class TokenizerProbe {

    private static final String[] TESTS = {
            "你好世界",
            "对方：你这两天怎么回事，消息也不回",
            "我：在忙",
            "Hello, world! 测试一下 mix 中英 123",
            "I don't know... really?!",
            "  multiple   spaces\tand\nnewlines  ",
            "标点，。！？；：（）【】《》",
            "数字1234和English words混排",
            "emoji 😀 和符号 @#￥%……&*",
    };

    public static void main(String[] args) throws Exception {
        String vocabPath = args[0];
        String outPath = args[1];

        Charset utf8 = Charset.forName("UTF-8");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(vocabPath), utf8));
        BertTokenizer tokenizer = BertTokenizer.fromReader(reader);
        reader.close();

        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outPath), utf8));
        try {
            for (String text : TESTS) {
                int[] ids = tokenizer.encode(text, 128);
                StringBuilder sb = new StringBuilder();
                for (int id : ids) {
                    sb.append(id).append(' ');
                }
                writer.write(text);
                writer.newLine();
                writer.write(sb.toString().trim());
                writer.newLine();
            }
        } finally {
            writer.close();
        }
        System.out.println("vocab=" + tokenizer.vocabSize() + " wrote " + outPath);
    }
}
