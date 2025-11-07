package com.axle.service.RAG;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*; // [FIX] 导入正确的 Embedding
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Primary
public class EmbeddingService implements EmbeddingModel {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int dimensions;

    public EmbeddingService() throws Exception {
        this.environment = OrtEnvironment.getEnvironment();

        Path modelPath = loadResource("/models/model.onnx");
        Path tokenizerPath = loadResource("/models/tokenizer.json");

        this.session = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
        this.dimensions = calculateDimensions();
    }

    private Path loadResource(String path) throws Exception {
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            throw new RuntimeException("Resource not found: " + path);
        }
        Path tempFile = Files.createTempFile("model-", ".tmp");
        Files.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    // [FIX] 核心逻辑: 变为私有方法，始终返回 float[]
    private float[] embedTextInternal(String text) {
        try {
            long[] tokens = tokenizer.encode(text).getIds();
            long[][] inputIds = new long[1][tokens.length];
            inputIds[0] = tokens;

            long[][] attentionMask = new long[1][tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                attentionMask[0][i] = 1;
            }

            long[][] tokenTypeIds = new long[1][tokens.length];

            OnnxTensor inputTensor = OnnxTensor.createTensor(environment, inputIds);
            OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(environment, attentionMask);
            OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(environment, tokenTypeIds);

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            inputs.put("token_type_ids", tokenTypeIdsTensor);

            OrtSession.Result result = session.run(inputs);

            float[][][] embeddings = (float[][][]) result.get(0).getValue();
            result.close();
            inputTensor.close();
            attentionMaskTensor.close();
            tokenTypeIdsTensor.close();

            return averagePool(embeddings[0]);
        } catch (OrtException e) {
            throw new RuntimeException("Failed to embed text: " + text, e);
        }
    }

    private float[] averagePool(float[][] tokenEmbeddings) {
        int numTokens = tokenEmbeddings.length;
        if (numTokens == 0) return new float[0];
        int embeddingDim = tokenEmbeddings[0].length;
        float[] pooledEmbedding = new float[embeddingDim];

        for (int i = 0; i < embeddingDim; i++) {
            float sum = 0.0f;
            for (int j = 0; j < numTokens; j++) {
                sum += tokenEmbeddings[j][i];
            }
            pooledEmbedding[i] = sum / numTokens;
        }

        float norm = 0.0f;
        for (float v : pooledEmbedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);

        if (norm == 0.0f) return pooledEmbedding;

        for (int i = 0; i < pooledEmbedding.length; i++) {
            pooledEmbedding[i] /= norm;
        }

        return pooledEmbedding;
    }

    private int calculateDimensions() {
        float[] embedding = embedTextInternal("test"); // [FIX] 调用内部方法
        return embedding.length;
    }

    // [FIX] 辅助方法：将 float[] 转换为 List<Double> 以满足接口要求
    private List<Double> convertFloatsToDoubles(float[] floats) {
        if (floats == null) {
            return Collections.emptyList();
        }
        return java.util.stream.IntStream.range(0, floats.length)
                .mapToDouble(i -> floats[i])
                .boxed()
                .collect(Collectors.toList());
    }
    // =================================================================
    // = 实现 Spring AI EmbeddingClient 接口 (所有必需的方法)
    // =================================================================

    // [FIX] 1. 实现 float[] embed(String text)
    @Override
    public float[] embed(String text) {
        return embedTextInternal(text);
    }

    // [FIX] 2. 实现 float[] embed(Document document)
    @Override
    public float[] embed(Document document) {
        return embedTextInternal(document.getContent());
    }


    // [FIX] 5. 实现 call(EmbeddingRequest request)
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddingList = request.getInstructions().stream()
                .map(text -> {
                    // Embedding 构造函数接受 float[]
                    float[] vector = embedTextInternal(text);
                    return new Embedding(vector, -1);
                })
                .toList();

        return new EmbeddingResponse(embeddingList);
    }

    // [NO CHANGE] 6. 实现 dimensions()
    @Override
    public int dimensions() {
        return this.dimensions;
    }
}