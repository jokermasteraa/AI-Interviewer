# RAG评估服务使用说明

## 概述

`RAGEvaluationService` 是用于评估RAG（检索增强生成）系统准确率的服务类。它可以帮助你测试RAG检索的质量，包括命中率、精确率、召回率、MRR、NDCG等指标。

## 重要说明

### 关于"Evaluation"的澄清

你代码中的 `InterviewEvaluation` 是**业务对象**，用于存储AI评估的结果（如面试评分），**不是**用于评估RAG系统准确率的工具。

`RAGEvaluationService` 才是专门用于**评估RAG系统性能**的服务类。

## 评估指标说明

### 1. 命中率 (Hit Rate)
- **定义**：在topK检索结果中，至少包含一个相关文档的查询比例
- **范围**：0.0 - 1.0，越高越好
- **意义**：衡量系统是否能找到至少一个正确答案

### 2. 精确率 (Precision)
- **定义**：检索到的相关文档数 / 检索到的总文档数
- **范围**：0.0 - 1.0，越高越好
- **意义**：衡量检索结果的准确性

### 3. 召回率 (Recall)
- **定义**：检索到的相关文档数 / 总相关文档数
- **范围**：0.0 - 1.0，越高越好
- **意义**：衡量系统能找到多少相关文档

### 4. F1分数 (F1 Score)
- **定义**：精确率和召回率的调和平均数
- **范围**：0.0 - 1.0，越高越好
- **意义**：综合衡量精确率和召回率

### 5. MRR (Mean Reciprocal Rank)
- **定义**：第一个相关文档排名的倒数平均值
- **范围**：0.0 - 1.0，越高越好
- **意义**：衡量相关文档在结果中的位置（越靠前越好）

### 6. NDCG (Normalized Discounted Cumulative Gain)
- **定义**：归一化折扣累积增益
- **范围**：0.0 - 1.0，越高越好
- **意义**：考虑文档位置权重的相关性评估

## 使用方法

### 方法1：批量评估（推荐）

```java
@Autowired
private RAGEvaluationService ragEvaluationService;

public void evaluateRAG() {
    // 1. 准备测试用例
    List<RAGEvaluationService.TestCase> testCases = new ArrayList<>();
    
    // 测试用例：查询文本 + 期望的相关文档ID集合 + 文档类型过滤（可选）
    testCases.add(new RAGEvaluationService.TestCase(
            "什么是Java的垃圾回收机制？",
            Set.of("doc_java_gc_001", "doc_java_jvm_002"),  // 这些ID需要是向量库中实际存在的
            "scoring_criteria"  // 可选：过滤文档类型
    ));
    
    testCases.add(new RAGEvaluationService.TestCase(
            "Spring Boot的核心特性有哪些？",
            Set.of("doc_spring_boot_001"),
            "scoring_criteria"
    ));
    
    // 2. 执行评估（topK=5表示检索前5个最相关的文档）
    RAGEvaluationService.RAGEvaluationResult result = 
            ragEvaluationService.evaluate(testCases, 5);
    
    // 3. 查看结果
    System.out.println("命中率: " + result.getHitRate());
    System.out.println("精确率: " + result.getAveragePrecision());
    System.out.println("MRR: " + result.getMrr());
    System.out.println("NDCG: " + result.getNdcg());
    
    // 查看详细指标
    result.getMetrics().forEach((key, value) -> 
            System.out.println(key + ": " + value));
}
```

### 方法2：单个查询评估

```java
@Autowired
private RAGEvaluationService ragEvaluationService;

public void evaluateSingleQuery() {
    String query = "什么是Java的垃圾回收机制？";
    Set<String> expectedRelevantDocIds = Set.of("doc_java_gc_001", "doc_java_jvm_002");
    
    Map<String, Object> result = ragEvaluationService.evaluateSingleQuery(
            query, 
            expectedRelevantDocIds, 
            5,  // topK
            "scoring_criteria"  // 文档类型过滤（可选，传null表示不过滤）
    );
    
    // 查看结果
    System.out.println("命中: " + result.get("hit"));
    System.out.println("精确率: " + result.get("precision"));
    System.out.println("召回率: " + result.get("recall"));
    System.out.println("F1: " + result.get("f1"));
    System.out.println("MRR: " + result.get("mrr"));
}
```

## 如何准备测试用例

### 步骤1：了解你的向量库文档

首先，你需要知道向量库中有哪些文档，以及它们的ID。可以通过以下方式获取：

1. 查看文档导入日志
2. 查询向量数据库
3. 在代码中记录文档ID

### 步骤2：创建测试用例

对于每个测试用例，你需要：

1. **查询文本**：模拟真实的用户查询
2. **期望的相关文档ID**：这些文档应该与查询相关
3. **文档类型**（可选）：如果需要过滤特定类型的文档

### 示例：创建测试数据集

```java
private List<RAGEvaluationService.TestCase> createTestCases() {
    List<RAGEvaluationService.TestCase> testCases = new ArrayList<>();
    
    // Java相关测试
    testCases.add(new RAGEvaluationService.TestCase(
            "Java的垃圾回收机制原理",
            Set.of("doc_java_gc_001", "doc_java_jvm_002"),
            "scoring_criteria"
    ));
    
    // Spring相关测试
    testCases.add(new RAGEvaluationService.TestCase(
            "Spring Boot自动配置原理",
            Set.of("doc_spring_boot_001", "doc_spring_config_002"),
            "scoring_criteria"
    ));
    
    // 数据库相关测试
    testCases.add(new RAGEvaluationService.TestCase(
            "MySQL索引优化",
            Set.of("doc_mysql_index_001"),
            "course"
    ));
    
    return testCases;
}
```

## 评估结果解读

### 好的评估结果示例

```
命中率 (Hit Rate): 0.9500      # 95%的查询能找到至少一个相关文档
精确率 (Precision): 0.8000      # 80%的检索结果是相关的
召回率 (Recall): 0.7500         # 能找到75%的相关文档
F1: 0.7742                      # 综合指标
MRR: 0.8500                     # 相关文档平均排在第1.18位
NDCG: 0.8200                    # 考虑位置的加权相关性
```

### 需要改进的情况

- **命中率 < 0.7**：说明很多查询找不到相关文档，可能需要：
  - 增加向量库的文档数量
  - 优化文档的向量化方式
  - 调整检索策略

- **精确率 < 0.6**：说明检索结果中不相关的文档太多，可能需要：
  - 提高topK的阈值
  - 优化文档的元数据过滤
  - 改进查询文本的处理

- **召回率 < 0.5**：说明很多相关文档没有被检索到，可能需要：
  - 增加topK的数量
  - 优化向量相似度计算
  - 改进文档分块策略

## 注意事项

1. **文档ID必须真实存在**：测试用例中的文档ID必须是向量库中实际存在的，否则评估结果不准确。

2. **相关文档的判断**：你需要人工判断哪些文档与查询相关，这是评估的基础。

3. **测试用例的代表性**：测试用例应该覆盖你的主要使用场景，包括：
   - 常见查询
   - 边缘情况
   - 不同类型的文档

4. **定期评估**：建议在以下情况执行评估：
   - 向量库更新后
   - 检索策略调整后
   - 文档处理流程变更后

## 与业务评估的区别

| 项目 | InterviewEvaluation | RAGEvaluationService |
|------|---------------------|----------------------|
| 用途 | 存储AI对面试答案的评估结果 | 评估RAG检索系统的性能 |
| 输入 | 候选人的答案 | 测试用例（查询+期望文档） |
| 输出 | 面试评分、评语 | 检索准确率、命中率等指标 |
| 使用场景 | 业务逻辑中 | 系统测试和优化中 |

## 总结

`RAGEvaluationService` 是用于**评估RAG系统性能**的工具，而不是业务对象。使用它可以：

1. ✅ 测试RAG检索的准确率
2. ✅ 发现检索系统的问题
3. ✅ 优化检索策略
4. ✅ 监控系统性能

而 `InterviewEvaluation` 是用于**存储业务评估结果**的对象，两者用途不同。

