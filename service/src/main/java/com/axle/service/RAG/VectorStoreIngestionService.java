package com.axle.service.RAG;

import com.axle.mapper.QuestionLibMapper;
import com.axle.pojo.QuestionLib;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 知识库加载服务
 * 在应用启动时，将所有持久化知识加载到 VectorStore (Redis) 中。
 */
@Service
@Slf4j
public class VectorStoreIngestionService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private QuestionLibMapper questionLibMapper; //

    /**
     * 应用启动时，自动执行所有知识库的加载
     */
    @PostConstruct
    public void ingestAllKnowledgeBases() {
        // 注意：为防止重复加载，生产环境应增加 "如果已存在则跳过" 的逻辑
        ingestQuestionCriteria();
        ingestCourseCatalog();
        ingestCompanyDocs();
    }

    /**
     * RAG 目标 1: 加载所有 "面试题评分标准"
     * 从 question_lib 表加载
     */
    public void ingestQuestionCriteria() {
        log.info("【RAG知识库】开始加载... 目标1: 面试题评分标准");
        List<QuestionLib> allQuestions = questionLibMapper.selectList(null);

        List<Document> documents = allQuestions.stream()
                // 只加载填写了 "评分标准" 的题目
                .filter(q -> q.getScoringCriteria() != null && !q.getScoringCriteria().isEmpty())
                .map(q -> {
                    // 知识库文档内容，包含问题、答案和标准
                    String content = String.format(
                            "问题：%s\n参考答案：%s\n评分标准：%s",
                            q.getQuestion(),
                            q.getReferenceAnswer(),
                            q.getScoringCriteria()
                    );

                    // 关键：使用元数据来区分知识库类型
                    Map<String, Object> metadata = Map.of(
                            "doc_type", "scoring_criteria", // 知识类型：评分标准
                            "question_id", q.getId()
                    );

                    // 我们使用 "问题" 文本本身作为文档ID，便于检索
                    return new Document(q.getQuestion(), content, metadata);
                })
                .collect(Collectors.toList());

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            log.info("【RAG知识库】加载完成。 目标1: 成功加载 {} 条评分标准。", documents.size());
        } else {
            log.warn("【RAG知识库】加载警告。 目标1: 未在数据库中找到任何 'scoringCriteria' 不为空的面试题。");
        }
    }

    /**
     * RAG 目标 2: 加载 "课程信息"
     * TODO: 你需要从文件或数据库加载你的课程
     */
    public void ingestCourseCatalog() {
        log.info("【RAG知识库】开始加载... 目标2: 课程与服务");
        // 示例：你应该从文件或数据库加载
        var course1 = new Document(
                "《MySQL高性能索引实战课》", // 课程名作为ID
                "本课程深入讲解MySQL索引底层原理，包括B+树、聚簇索引、覆盖索引等，" +
                        "并通过实际案例分析慢查询，教会你如何写出高性能SQL。适合对数据库索引理解不深的开发者。",
                Map.of("doc_type", "course", "keywords", "MySQL, 索引, 数据库, 慢查询") // 知识类型：课程
        );

        var course2 = new Document(
                "《Java并发编程大师班》", // 课程名作为ID
                "全面覆盖Java并发编程核心，从synchronized、ReentrantLock到CAS和JMM内存模型，" +
                        "解决你在多线程面试中的所有痛点。适合并发编程经验不足的候选人。",
                Map.of("doc_type", "course", "keywords", "Java, 并发, 多线程, ReentrantLock, CAS") // 知识类型：课程
        );

        vectorStore.add(List.of(course1, course2));
        log.info("【RAG知识库】加载完成。 目标2: 成功加载 2 门示例课程。");
    }

    /**
     * RAG 目标 3: 加载 "公司文档" (防幻觉)
     * TODO: 你需要从Markdown/PDF/TXT文件加载你的文档
     */
    public void ingestCompanyDocs() {
        log.info("【RAG知识库】开始加载... 目标3: 公司HR与面试指南");
        // 示例：你应该从文件系统加载
        var doc1 = new Document(
                "《前端岗面试指南 v2.3》- 流程篇", // 文档标题作为ID
                "我们的标准面试流程分为三轮：第一轮为技术基础面试，时长1小时；" +
                        "第二轮为项目深挖与场景设计，时长1.5小时；" +
                        "第三轮为HR综合面试，时长30分钟。所有结果通常在3个工作日内通知。",
                Map.of("doc_type", "company_policy", "category", "interview_flow") // 知识类型：公司政策
        );

        vectorStore.add(List.of(doc1));
        log.info("【RAG知识库】加载完成。 目标3: 成功加载 1 篇公司文档。");
    }
}