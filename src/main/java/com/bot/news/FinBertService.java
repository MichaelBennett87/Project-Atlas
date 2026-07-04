package com.bot.news;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.bot.sentiment.SentimentScore;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public class FinBertService {

    private static final int MAX_LENGTH = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public FinBertService() throws Exception {
        this.env = OrtEnvironment.getEnvironment();

        Path modelPath = extractResourceToTempFile(
                "/models/finbert/finbert.onnx",
                "finbert-",
                ".onnx"
        );

        this.session = env.createSession(
                modelPath.toString(),
                new OrtSession.SessionOptions()
        );

        Path tokenizerPath = extractResourceToTempFile(
                "/models/finbert/tokenizer.json",
                "finbert-tokenizer-",
                ".json"
        );

        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
    }

    public SentimentScore analyze(String text) throws Exception {

        var encoding = tokenizer.encode(text);

        long[] inputIds = padOrTruncate(encoding.getIds(), MAX_LENGTH);
        long[] attentionMask = padOrTruncate(encoding.getAttentionMask(), MAX_LENGTH);
        long[] tokenTypeIds = new long[MAX_LENGTH];

        Map<String, OnnxTensor> inputs = new HashMap<>();

        inputs.put(
                "input_ids",
                OnnxTensor.createTensor(env, new long[][]{inputIds})
        );

        inputs.put(
                "attention_mask",
                OnnxTensor.createTensor(env, new long[][]{attentionMask})
        );

        if (session.getInputNames().contains("token_type_ids")) {
            inputs.put(
                    "token_type_ids",
                    OnnxTensor.createTensor(env, new long[][]{tokenTypeIds})
            );
        }

        try (OrtSession.Result result = session.run(inputs)) {

            Object value = result.get(0).getValue();

            float[] logits;

            if (value instanceof float[][] output2d) {
                logits = output2d[0];
            } else if (value instanceof float[][][] output3d) {
                logits = output3d[0][0];
            } else {
                throw new IllegalStateException(
                        "Unexpected ONNX output type: " + value.getClass()
                );
            }

            double[] probabilities = softmax(logits);

            return new SentimentScore(
                    probabilities[0], // positive
                    probabilities[1], // negative
                    probabilities[2]  // neutral
            );
        } finally {
            for (OnnxTensor tensor : inputs.values()) {
                tensor.close();
            }
        }
    }

    private long[] padOrTruncate(long[] values, int maxLength) {
        long[] result = new long[maxLength];

        int copyLength = Math.min(values.length, maxLength);
        System.arraycopy(values, 0, result, 0, copyLength);

        return result;
    }

    private double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;

        for (float logit : logits) {
            max = Math.max(max, logit);
        }

        double sum = 0.0;
        double[] exp = new double[logits.length];

        for (int i = 0; i < logits.length; i++) {
            exp[i] = Math.exp(logits[i] - max);
            sum += exp[i];
        }

        for (int i = 0; i < exp.length; i++) {
            exp[i] = exp[i] / sum;
        }

        return exp;
    }

    private Path extractResourceToTempFile(
            String resourcePath,
            String prefix,
            String suffix
    ) throws Exception {

        InputStream inputStream =
                FinBertService.class.getResourceAsStream(resourcePath);

        if (inputStream == null) {
            throw new IllegalStateException(
                    "Could not find resource: " + resourcePath
            );
        }

        Path tempFile = Files.createTempFile(prefix, suffix);

        Files.copy(
                inputStream,
                tempFile,
                StandardCopyOption.REPLACE_EXISTING
        );

        return tempFile;
    }
}