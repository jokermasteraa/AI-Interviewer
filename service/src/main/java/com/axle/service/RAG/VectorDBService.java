package com.axle.service.RAG; // 确保包名正确

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class VectorDBService {

    // 使用 ConcurrentHashMap 来为每次面试会话存储一个独立的向量数据库
    private final Map<String, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();

    /**
     * 为一次新的面试会话创建一个内存向量数据库实例
     */
    public void createStore(String indexName) {
        stores.put(indexName, new InMemoryEmbeddingStore<>());
    }

    /**
     * 添加文档（文本及其向量）到指定的存储中
     */
    public void addDocument(String indexName, String text, float[] vector) {
        InMemoryEmbeddingStore<TextSegment> store = stores.get(indexName);
        if (store != null) {
            TextSegment segment = TextSegment.from(text);
            Embedding embedding = Embedding.from(vector);
            store.add(embedding, segment);
        }
    }

    /**
     * 执行相似度搜索
     */
    public List<String> search(String indexName, float[] queryVector, int topK) {
        InMemoryEmbeddingStore<TextSegment> store = stores.get(indexName);
        if (store == null) {
            return List.of(); // 返回一个不可变的空列表，更安全
        }

        // 1. 将查询向量包装成 Embedding 对象
        Embedding queryEmbedding = Embedding.from(queryVector);

        // 2. 调用 langchain4j 提供的高级API findRelevant
        //    这个API非常稳定，参数清晰：查询向量, 返回数量, 最低得分阈值(我们设为0)
        List<EmbeddingMatch<TextSegment>> results = store.findRelevant(queryEmbedding, topK, 0);

        // 3. 从结果中提取原始文本并返回
        return results.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());
    }
}