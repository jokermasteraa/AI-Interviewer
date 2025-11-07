package com.axle.service.RAG;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
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
@Primary // 标记为首选的 EmbeddingClient Bean
public class EmbeddingService implements EmbeddingModel {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int dimensions;

    public EmbeddingService() throws Exception {
        this.environment = OrtEnvironment.getEnvironment();

        // 加载模型和分词器
        Path modelPath = loadResource("/models/model.onnx");
        Path tokenizerPath = loadResource("/models/tokenizer.json");

        this.session = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

        // 初始化时, 获取一次模型维度
        this.dimensions = calculateDimensions();
    }

    private Path loadResource(String path) throws Exception {
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            throw new RuntimeException("Resource not found: " + path);
        }
        Path tempFile = Files.createTempFile("model-", ".tmp");
        Files.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        tempFile.toFile().deleteOnExit(); // 确保临时文件被删除
        return tempFile;
    }

    // [重命名] 您原始的 embed 方法
    private float[] embedText(String text) throws OrtException {
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
        result.close(); // 及时释放资源
        inputTensor.close();
        attentionMaskTensor.close();
        tokenTypeIdsTensor.close();

        // 8. 平均池化 (Mean Pooling)
        return averagePool(embeddings[0]);
    }

    private float[] averagePool(float[][] tokenEmbeddings) {
        int numTokens = tokenEmbeddings.length;
        if (numTokens == 0) {
            return new float[0]; // 处理空输入
        }
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

        if (norm == 0.0f) { // 防止除以零
            return pooledEmbedding;
        }

        for (int i = 0; i < pooledEmbedding.length; i++) {
            pooledEmbedding[i] /= norm;
        }

        return pooledEmbedding;
    }

    // [新增] 辅助方法：计算模型维度
    private int calculateDimensions() throws OrtException {
        // 运行一个虚拟输入来获取维度
        float[] embedding = embedText("test");
        return embedding.length;
    }

    // =================================================================
    // = 实现 Spring AI EmbeddingClient 接口
    // =================================================================

    // 移除 @Override 注解，改为 private 访问修饰符
    private float[] convertFloatsToArray(float[] floats) {
        return floats;
    }

    // 添加缺失的 embed(String text) 方法实现
    @Override
    public float[] embed(Document document) {
        return this.embed(document.getContent());
    }

//    @Override
//    public List<float[]> embed(List<String> texts) {
//        return texts.stream().map(this::embed).collect(Collectors.toList());
//    }
//
//    @Override
//    public List<float[]> embed(List<Document> documents) {
//        return documents.stream().map(this::embed).collect(Collectors.toList());
//    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        // 批量处理输入
        List<float[]> embeddings = request.getInstructions().stream()
                .map(this::embed)
                .toList();

        // 构建 Embedding 列表
        List<Embedding> embeddingList = embeddings.stream()
                .map(e -> new Embedding(e, -1)) // -1 表示索引（我们在这里不关心）
                .toList();

        return new EmbeddingResponse(embeddingList);
    }




    // [新增] Spring AI 1.0.0 需要知道向量维度
    @Override
    public int dimensions() {
        return this.dimensions;
    }

    // [新增] 辅助方法：Spring AI 内部使用 List<Double>
    private List<Double> convertFloatsToDoubles(float[] floats) {
        if (floats == null) {
            return Collections.emptyList();
        }
        List<Double> doubles = new java.util.ArrayList<>(floats.length);
        for (float f : floats) {
            doubles.add((double) f);
        }
        return doubles;
    }
}
