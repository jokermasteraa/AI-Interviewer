package com.axle.service.RAG;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG系统评估服务
 * 用于评估RAG检索的准确率、命中率等指标
 */
@Service
@Slf4j
public class RAGEvaluationService {

    private final VectorStore vectorStore;

    public RAGEvaluationService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * RAG评估结果
     */
    public static class RAGEvaluationResult {
        private double hitRate;              // 命中率：topK结果中包含正确答案的比例
        private double averagePrecision;     // 平均精确率
        private double mrr;                  // 平均倒数排名（Mean Reciprocal Rank）
        private double ndcg;                 // 归一化折扣累积增益
        private int totalQueries;            // 总查询数
        private int relevantRetrieved;      // 检索到的相关文档数
        private int totalRelevant;           // 总相关文档数
        private Map<String, Double> metrics; // 详细指标

        // Getters and Setters
        public double getHitRate() { return hitRate; }
        public void setHitRate(double hitRate) { this.hitRate = hitRate; }
        
        public double getAveragePrecision() { return averagePrecision; }
        public void setAveragePrecision(double averagePrecision) { this.averagePrecision = averagePrecision; }
        
        public double getMrr() { return mrr; }
        public void setMrr(double mrr) { this.mrr = mrr; }
        
        public double getNdcg() { return ndcg; }
        public void setNdcg(double ndcg) { this.ndcg = ndcg; }
        
        public int getTotalQueries() { return totalQueries; }
        public void setTotalQueries(int totalQueries) { this.totalQueries = totalQueries; }
        
        public int getRelevantRetrieved() { return relevantRetrieved; }
        public void setRelevantRetrieved(int relevantRetrieved) { this.relevantRetrieved = relevantRetrieved; }
        
        public int getTotalRelevant() { return totalRelevant; }
        public void setTotalRelevant(int totalRelevant) { this.totalRelevant = totalRelevant; }
        
        public Map<String, Double> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Double> metrics) { this.metrics = metrics; }
    }

    /**
     * 测试用例：包含查询和期望的相关文档ID
     */
    public static class TestCase {
        private String query;                    // 查询文本
        private Set<String> relevantDocIds;     // 期望的相关文档ID集合
        private String docType;                 // 文档类型过滤（可选）

        public TestCase(String query, Set<String> relevantDocIds) {
            this.query = query;
            this.relevantDocIds = relevantDocIds;
        }

        public TestCase(String query, Set<String> relevantDocIds, String docType) {
            this.query = query;
            this.relevantDocIds = relevantDocIds;
            this.docType = docType;
        }

        // Getters and Setters
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        
        public Set<String> getRelevantDocIds() { return relevantDocIds; }
        public void setRelevantDocIds(Set<String> relevantDocIds) { this.relevantDocIds = relevantDocIds; }
        
        public String getDocType() { return docType; }
        public void setDocType(String docType) { this.docType = docType; }
    }

    /**
     * 评估RAG检索准确率
     * 
     * @param testCases 测试用例列表
     * @param topK 检索的topK数量
     * @return 评估结果
     */
    public RAGEvaluationResult evaluate(List<TestCase> testCases, int topK) {
        log.info("【RAG评估】开始评估，测试用例数: {}, topK: {}", testCases.size(), topK);
        
        int totalQueries = testCases.size();
        int hits = 0;  // 命中数（topK中包含至少一个相关文档）
        double totalPrecision = 0.0;
        double totalMRR = 0.0;
        double totalNDCG = 0.0;
        int totalRelevant = 0;
        int totalRetrievedRelevant = 0;

        for (TestCase testCase : testCases) {
            try {
                // 执行检索
                SearchRequest.Builder requestBuilder = SearchRequest.builder()
                        .query(testCase.getQuery())
                        .topK(topK);
                
                if (testCase.getDocType() != null) {
                    requestBuilder.filterExpression("doc_type == '" + testCase.getDocType() + "'");
                }
                
                SearchRequest searchRequest = requestBuilder.build();
                List<Document> retrievedDocs = vectorStore.similaritySearch(searchRequest);
                
                if (retrievedDocs == null) {
                    retrievedDocs = new ArrayList<>();
                }
                
                // 提取检索到的文档ID
                Set<String> retrievedDocIds = retrievedDocs.stream()
                        .map(Document::getId)
                        .collect(Collectors.toSet());
                
                // 计算指标
                Set<String> relevantDocIds = testCase.getRelevantDocIds();
                totalRelevant += relevantDocIds.size();
                
                // 1. 命中率（Hit Rate）：topK中是否包含至少一个相关文档
                boolean hasHit = retrievedDocIds.stream()
                        .anyMatch(relevantDocIds::contains);
                if (hasHit) {
                    hits++;
                }
                
                // 2. 精确率（Precision）：检索到的相关文档数 / 检索到的总文档数
                int relevantRetrieved = (int) retrievedDocIds.stream()
                        .filter(relevantDocIds::contains)
                        .count();
                totalRetrievedRelevant += relevantRetrieved;
                
                double precision = retrievedDocs.isEmpty() ? 0.0 
                        : (double) relevantRetrieved / retrievedDocs.size();
                totalPrecision += precision;
                
                // 3. MRR（Mean Reciprocal Rank）：第一个相关文档的排名倒数
                double mrr = 0.0;
                for (int i = 0; i < retrievedDocs.size(); i++) {
                    if (relevantDocIds.contains(retrievedDocs.get(i).getId())) {
                        mrr = 1.0 / (i + 1);
                        break;
                    }
                }
                totalMRR += mrr;
                
                // 4. NDCG（归一化折扣累积增益）
                double ndcg = calculateNDCG(retrievedDocs, relevantDocIds, topK);
                totalNDCG += ndcg;
                
                log.debug("【RAG评估】查询: {}, 命中: {}, 精确率: {:.4f}, MRR: {:.4f}, NDCG: {:.4f}", 
                        testCase.getQuery(), hasHit, precision, mrr, ndcg);
                
            } catch (Exception e) {
                log.error("【RAG评估】评估测试用例失败: {}", testCase.getQuery(), e);
            }
        }

        // 计算平均指标
        RAGEvaluationResult result = new RAGEvaluationResult();
        result.setTotalQueries(totalQueries);
        result.setHitRate(totalQueries > 0 ? (double) hits / totalQueries : 0.0);
        result.setAveragePrecision(totalQueries > 0 ? totalPrecision / totalQueries : 0.0);
        result.setMrr(totalQueries > 0 ? totalMRR / totalQueries : 0.0);
        result.setNdcg(totalQueries > 0 ? totalNDCG / totalQueries : 0.0);
        result.setRelevantRetrieved(totalRetrievedRelevant);
        result.setTotalRelevant(totalRelevant);
        
        // 计算召回率（Recall）
        double recall = totalRelevant > 0 
                ? (double) totalRetrievedRelevant / totalRelevant 
                : 0.0;
        
        // 计算F1分数
        double f1 = (result.getAveragePrecision() + recall > 0) 
                ? 2 * result.getAveragePrecision() * recall / (result.getAveragePrecision() + recall)
                : 0.0;
        
        // 设置详细指标
        Map<String, Double> metrics = new HashMap<>();
        metrics.put("hitRate", result.getHitRate());
        metrics.put("precision", result.getAveragePrecision());
        metrics.put("recall", recall);
        metrics.put("f1", f1);
        metrics.put("mrr", result.getMrr());
        metrics.put("ndcg", result.getNdcg());
        result.setMetrics(metrics);
        
        log.info("【RAG评估】评估完成 - 命中率: {:.4f}, 精确率: {:.4f}, 召回率: {:.4f}, F1: {:.4f}, MRR: {:.4f}, NDCG: {:.4f}", 
                result.getHitRate(), result.getAveragePrecision(), recall, f1, result.getMrr(), result.getNdcg());
        
        return result;
    }

    /**
     * 计算NDCG（归一化折扣累积增益）
     */
    private double calculateNDCG(List<Document> retrievedDocs, Set<String> relevantDocIds, int topK) {
        if (retrievedDocs.isEmpty()) {
            return 0.0;
        }
        
            // 计算DCG（折扣累积增益）
            double dcg = 0.0;
            for (int i = 0; i < Math.min(retrievedDocs.size(), topK); i++) {
                Document doc = retrievedDocs.get(i);
                if (relevantDocIds.contains(doc.getId())) {
                    // 相关文档的增益为1，位置i的折扣为1/log2(i+2)
                    // Java没有Math.log2，使用Math.log(x) / Math.log(2)代替
                    dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                }
            }
            
            // 计算IDCG（理想折扣累积增益）
            double idcg = 0.0;
            int relevantCount = Math.min(relevantDocIds.size(), topK);
            for (int i = 0; i < relevantCount; i++) {
                // Java没有Math.log2，使用Math.log(x) / Math.log(2)代替
                idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        
        // NDCG = DCG / IDCG
        return idcg > 0 ? dcg / idcg : 0.0;
    }

    /**
     * 评估单个查询的检索质量
     * 
     * @param query 查询文本
     * @param expectedRelevantDocIds 期望的相关文档ID集合
     * @param topK 检索的topK数量
     * @param docType 文档类型过滤（可选）
     * @return 评估结果
     */
    public Map<String, Object> evaluateSingleQuery(String query, Set<String> expectedRelevantDocIds, 
                                                     int topK, String docType) {
        log.info("【RAG评估】评估单个查询: {}, topK: {}", query, topK);
        
        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);
            
            if (docType != null) {
                requestBuilder.filterExpression("doc_type == '" + docType + "'");
            }
            
            SearchRequest searchRequest = requestBuilder.build();
            List<Document> retrievedDocs = vectorStore.similaritySearch(searchRequest);
            
            if (retrievedDocs == null) {
                retrievedDocs = new ArrayList<>();
            }
            
            // 提取检索到的文档ID
            List<String> retrievedDocIds = retrievedDocs.stream()
                    .map(Document::getId)
                    .collect(Collectors.toList());
            
            // 计算指标
            boolean hasHit = retrievedDocIds.stream()
                    .anyMatch(expectedRelevantDocIds::contains);
            
            int relevantRetrieved = (int) retrievedDocIds.stream()
                    .filter(expectedRelevantDocIds::contains)
                    .count();
            
            double precision = retrievedDocs.isEmpty() ? 0.0 
                    : (double) relevantRetrieved / retrievedDocs.size();
            
            double recall = expectedRelevantDocIds.isEmpty() ? 0.0
                    : (double) relevantRetrieved / expectedRelevantDocIds.size();
            
            double f1 = (precision + recall > 0) 
                    ? 2 * precision * recall / (precision + recall)
                    : 0.0;
            
            // 计算MRR
            double mrr = 0.0;
            for (int i = 0; i < retrievedDocIds.size(); i++) {
                if (expectedRelevantDocIds.contains(retrievedDocIds.get(i))) {
                    mrr = 1.0 / (i + 1);
                    break;
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("query", query);
            result.put("hit", hasHit);
            result.put("precision", precision);
            result.put("recall", recall);
            result.put("f1", f1);
            result.put("mrr", mrr);
            result.put("retrievedCount", retrievedDocs.size());
            result.put("relevantRetrieved", relevantRetrieved);
            result.put("expectedRelevant", expectedRelevantDocIds.size());
            result.put("retrievedDocIds", retrievedDocIds);
            
            log.info("【RAG评估】单个查询评估完成 - 命中: {}, 精确率: {:.4f}, 召回率: {:.4f}, F1: {:.4f}, MRR: {:.4f}", 
                    hasHit, precision, recall, f1, mrr);
            
            return result;
            
        } catch (Exception e) {
            log.error("【RAG评估】评估单个查询失败: {}", query, e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", e.getMessage());
            return errorResult;
        }
    }
}

