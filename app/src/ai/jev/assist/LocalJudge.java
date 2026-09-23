package ai.jev.assist;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 纯手机本地判定引擎：不联网、不要 key、数据不出机器。
 *
 * <p>用 bge-small-zh-v1.5 把文本编码成 512 维句向量，取 [CLS] 位置并做 L2 归一化；
 * 每个问题的候选描述预先编码并缓存，判定时只算上下文向量与候选向量的余弦相似度，
 * 再用带温度的 softmax 转成概率。方法是 Jev「读 logits 不生成文本」的句向量近似，
 * 不是生成式回答。
 */
public final class LocalJudge {

    private static final String MODEL_ASSET = "models/bge-small-zh/model_quantized.onnx";
    private static final String VOCAB_ASSET = "models/bge-small-zh/vocab.txt";

    private static final int MAX_LEN = 128;
    private static final float TEMPERATURE = 0.05f;

    private static volatile LocalJudge instance;
    private static volatile String loadError;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final BertTokenizer tokenizer;

    /** 候选描述的向量缓存，键为「问题键|候选键」。 */
    private final Map<String, float[]> optionVectors = new HashMap<>();

    private LocalJudge(OrtEnvironment env, OrtSession session, BertTokenizer tokenizer) {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
    }

    /** 已加载好的实例；未加载或加载失败返回 null。 */
    public static LocalJudge peek() {
        return instance;
    }

    public static String loadError() {
        return loadError;
    }

    /**
     * 取（必要时加载）单例。加载 23MB 量化模型，首次会花几秒，调用方应在后台线程。
     */
    public static LocalJudge get(Context ctx) throws Exception {
        LocalJudge local = instance;
        if (local != null) {
            return local;
        }
        synchronized (LocalJudge.class) {
            if (instance != null) {
                return instance;
            }
            try {
                byte[] model = readAsset(ctx, MODEL_ASSET);
                BertTokenizer tk;
                InputStream vocab = ctx.getAssets().open(VOCAB_ASSET);
                try {
                    tk = BertTokenizer.fromReader(new InputStreamReader(vocab, Charset.forName("UTF-8")));
                } finally {
                    vocab.close();
                }

                OrtEnvironment env = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                options.setIntraOpNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

                OrtSession session = env.createSession(model, options);
                LocalJudge judge = new LocalJudge(env, session, tk);
                judge.warmOptions();
                instance = judge;
                return judge;
            } catch (Throwable t) {
                loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
                throw new Exception(loadError, t);
            }
        }
    }

    /**
     * 整块读取 asset。刻意不用 available() 分配缓冲区：未压缩的 asset 上它不可靠，
     * 而且 aapt2 不压缩时 open() 本身在部分 Android 版本会失败，所以模型保持压缩存放。
     */
    private static byte[] readAsset(Context ctx, String path) throws IOException {
        InputStream in = ctx.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ---- 推理 ----

    /** 句向量：取 [CLS] 位置，L2 归一化。 */
    private float[] embed(String text) throws Exception {
        return embed(tokenizer.encode(text, MAX_LEN));
    }

    private float[] embed(int[] ids) throws Exception {
        int len = ids.length;

        long[] inputIds = new long[len];
        long[] attention = new long[len];
        long[] typeIds = new long[len];
        for (int i = 0; i < len; i++) {
            inputIds[i] = ids[i];
            attention[i] = 1L;
            typeIds[i] = 0L;
        }

        long[] shape = new long[]{1, len};
        OnnxTensor tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
        OnnxTensor tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(attention), shape);
        OnnxTensor tTypes = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape);

        OrtSession.Result result = null;
        try {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", tIds);
            inputs.put("attention_mask", tMask);
            inputs.put("token_type_ids", tTypes);
            result = session.run(inputs);

            Object value = result.get(0).getValue();
            float[][][] hidden = (float[][][]) value;
            float[] cls = hidden[0][0];

            float norm = 0f;
            for (float v : cls) {
                norm += v * v;
            }
            norm = (float) Math.sqrt(norm);
            float[] out = new float[cls.length];
            if (norm > 1e-9f) {
                for (int i = 0; i < cls.length; i++) {
                    out[i] = cls[i] / norm;
                }
            } else {
                System.arraycopy(cls, 0, out, 0, cls.length);
            }
            return out;
        } finally {
            tIds.close();
            tMask.close();
            tTypes.close();
            if (result != null) {
                result.close();
            }
        }
    }

    /** 预编码所有候选描述，之后每次判定不再重复计算。 */
    private void warmOptions() {
        for (LocalDecisionSpec.Question q : LocalDecisionSpec.QUESTIONS) {
            for (int i = 0; i < q.options.length; i++) {
                String key = q.key + "|" + q.optionKeys[i];
                if (!optionVectors.containsKey(key)) {
                    try {
                        optionVectors.put(key, embed(LocalDecisionSpec.optionText(q, i)));
                    } catch (Exception ignored) {
                        // 单个候选失败不阻塞整体，判定时按缺失处理
                    }
                }
            }
        }
    }

    private static float dot(float[] a, float[] b) {
        float s = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** 把一组相似度转成概率。 */
    private static double[] softmax(float[] scores) {
        double max = Double.NEGATIVE_INFINITY;
        for (float s : scores) {
            double v = s / TEMPERATURE;
            if (v > max) {
                max = v;
            }
        }
        double sum = 0;
        double[] out = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            out[i] = Math.exp(scores[i] / TEMPERATURE - max);
            sum += out[i];
        }
        if (sum <= 0) {
            for (int i = 0; i < out.length; i++) {
                out[i] = 1.0 / out.length;
            }
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    /**
     * 对一段聊天上下文跑完整判定，返回与 Jev 响应同形的 answers 对象。
     */
    public JSONObject judge(String context) throws Exception {
        JSONObject answers = new JSONObject();
        for (LocalDecisionSpec.Question q : LocalDecisionSpec.QUESTIONS) {
            float[] ctxVec = embed(tokenizer.encodeConversation(context, q.ask, MAX_LEN));

            float[] scores = new float[q.options.length];
            boolean missing = false;
            for (int i = 0; i < q.options.length; i++) {
                float[] vec = optionVectors.get(q.key + "|" + q.optionKeys[i]);
                if (vec == null) {
                    missing = true;
                    scores[i] = -1f;
                } else {
                    scores[i] = dot(ctxVec, vec);
                }
            }
            if (missing) {
                continue;
            }

            double[] probs = softmax(scores);
            int best = 0;
            for (int i = 1; i < probs.length; i++) {
                if (probs[i] > probs[best]) {
                    best = i;
                }
            }

            JSONObject answer = new JSONObject();
            answer.put("type", q.type);
            JSONObject probJson = new JSONObject();
            for (int i = 0; i < q.options.length; i++) {
                probJson.put(q.optionKeys[i], round(probs[i]));
            }
            answer.put("probabilities", probJson);
            answer.put("confidence", round(probs[best]));

            if ("noul".equals(q.type)) {
                answer.put("noul", round(probs[0]));
            } else if ("choice".equals(q.type)) {
                answer.put("choice", q.optionKeys[best]);
            } else if ("score".equals(q.type)) {
                double expected = 0;
                for (int i = 0; i < probs.length; i++) {
                    expected += i * probs[i];
                }
                answer.put("score", round(expected));
                JSONObject legend = new JSONObject();
                for (int i = 0; i < q.options.length; i++) {
                    legend.put(String.valueOf(i), q.options[i]);
                }
                answer.put("legend", legend);
            }
            answers.put(q.key, answer);
        }
        return answers;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
