package com.axle.service.RAG;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import org.springframework.stereotype.Service;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmbeddingService {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public EmbeddingService() throws Exception {
        this.environment = OrtEnvironment.getEnvironment();

        // 加载模型和分词器
        Path modelPath = loadResource("/models/model.onnx");
        Path tokenizerPath = loadResource("/models/tokenizer.json");

        this.session = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
    }

    private Path loadResource(String path) throws Exception {
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            throw new RuntimeException("Resource not found: " + path);
        }
        Path tempFile = Files.createTempFile("model-", ".tmp");
        Files.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        return tempFile;
    }

    public float[] embed(String text) throws OrtException {
        // 1. 文本编码 (Text -> Token IDs)
        long[] tokens = tokenizer.encode(text).getIds();
        long[][] inputIds = new long[1][tokens.length];
        inputIds[0] = tokens;

        // 2. 创建注意力掩码
        long[][] attentionMask = new long[1][tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            attentionMask[0][i] = 1; // 所有token都有效
        }

        // 3. 创建token type ids
        long[][] tokenTypeIds = new long[1][tokens.length];
        // 通常全部为0，除非处理句子对

        // 4. 创建模型输入
        OnnxTensor inputTensor = OnnxTensor.createTensor(environment, inputIds);
        OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(environment, attentionMask);
        OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(environment, tokenTypeIds);

        // 5. 构造完整的输入映射
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", inputTensor);
        inputs.put("attention_mask", attentionMaskTensor);
        inputs.put("token_type_ids", tokenTypeIdsTensor);

        // 6. 模型推理
        OrtSession.Result result = session.run(inputs);

        // 7. 提取 Embedding 结果
        float[][][] embeddings = (float[][][]) result.get(0).getValue();

        // 8. 平均池化 (Mean Pooling)
        return averagePool(embeddings[0]);
    }


    private float[] averagePool(float[][] tokenEmbeddings) {
        int numTokens = tokenEmbeddings.length;
        int embeddingDim = tokenEmbeddings[0].length;
        float[] pooledEmbedding = new float[embeddingDim];

        for (int i = 0; i < embeddingDim; i++) {
            float sum = 0.0f;
            for (int j = 0; j < numTokens; j++) {
                sum += tokenEmbeddings[j][i];
            }
            pooledEmbedding[i] = sum / numTokens;
        }
        
        // 归一化 (Normalization)
        float norm = 0.0f;
        for (float v : pooledEmbedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        
        for (int i = 0; i < pooledEmbedding.length; i++) {
            pooledEmbedding[i] /= norm;
        }
        
        return pooledEmbedding;
    }
}