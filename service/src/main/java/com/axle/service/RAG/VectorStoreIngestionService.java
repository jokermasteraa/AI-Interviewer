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
     * RAG 目标 2: 加载 "课程/学习资料"
     */
    public void ingestCourseCatalog() {
        log.info("【RAG知识库】开始加载... 目标2: 课程与学习资料");
        
        List<Document> courses = List.of(
            // ========== Java 基础 ==========
            new Document("《Java核心技术卷I》",
                "Java基础必读书籍，涵盖面向对象、集合框架、泛型、反射、注解、Lambda表达式等核心知识。" +
                "适合Java基础薄弱、对集合框架理解不深的候选人。",
                Map.of("doc_type", "course", "category", "Java基础", 
                       "keywords", "Java, 集合, 泛型, 反射, Lambda")),
            
            new Document("《Effective Java》",
                "Java进阶必读，讲解如何写出高质量Java代码。包括创建对象最佳实践、equals和hashCode实现、" +
                "异常处理、并发编程技巧等。适合想提升代码质量的开发者。",
                Map.of("doc_type", "course", "category", "Java进阶",
                       "keywords", "Java, 最佳实践, 代码质量, equals, hashCode")),
            
            // ========== 并发编程 ==========
            new Document("《Java并发编程实战》",
                "Java并发编程权威指南。深入讲解线程安全、锁机制、并发容器、线程池、Future、CompletableFuture等。" +
                "适合并发编程经验不足、对线程安全理解不深的候选人。",
                Map.of("doc_type", "course", "category", "并发编程",
                       "keywords", "并发, 多线程, 线程安全, 锁, 线程池, ThreadLocal")),
            
            new Document("《Java并发编程的艺术》",
                "深入JVM底层讲解并发原理，包括synchronized底层实现、AQS框架、CAS原理、内存模型JMM等。" +
                "适合想深入理解并发底层原理的开发者。",
                Map.of("doc_type", "course", "category", "并发编程",
                       "keywords", "synchronized, AQS, CAS, JMM, volatile, 内存屏障")),
            
            // ========== JVM ==========
            new Document("《深入理解Java虚拟机》",
                "JVM学习必读书籍。涵盖类加载机制、内存模型、垃圾回收算法（CMS/G1/ZGC）、JIT编译、性能调优等。" +
                "适合JVM知识薄弱、不会调优的候选人。",
                Map.of("doc_type", "course", "category", "JVM",
                       "keywords", "JVM, GC, 垃圾回收, 类加载, 内存模型, 调优")),
            
            // ========== Spring ==========
            new Document("《Spring实战》",
                "Spring框架入门到进阶。讲解IoC容器、AOP原理、Bean生命周期、事务管理、Spring MVC等核心概念。" +
                "适合Spring基础不牢、对IoC/AOP理解不深的候选人。",
                Map.of("doc_type", "course", "category", "Spring",
                       "keywords", "Spring, IoC, AOP, Bean, 事务, Spring MVC")),
            
            new Document("《Spring Boot实战》",
                "Spring Boot从入门到精通。涵盖自动配置原理、Starter机制、配置文件、Actuator监控、" +
                "与各种中间件整合（Redis/MQ/ES）。适合想掌握Spring Boot原理的开发者。",
                Map.of("doc_type", "course", "category", "Spring",
                       "keywords", "Spring Boot, 自动配置, Starter, Actuator")),
            
            new Document("《Spring Cloud微服务实战》",
                "微服务架构实践指南。讲解服务注册发现(Nacos)、负载均衡、服务熔断(Sentinel)、" +
                "分布式配置、API网关(Gateway)、链路追踪等。适合微服务架构经验不足的候选人。",
                Map.of("doc_type", "course", "category", "微服务",
                       "keywords", "微服务, Spring Cloud, Nacos, Sentinel, Gateway, 分布式")),
            
            // ========== 数据库 ==========
            new Document("《高性能MySQL》",
                "MySQL优化权威指南。深入讲解索引原理(B+树)、查询优化、锁机制、事务隔离级别、主从复制、" +
                "分库分表策略。适合SQL优化能力弱、对索引理解不深的候选人。",
                Map.of("doc_type", "course", "category", "数据库",
                       "keywords", "MySQL, 索引, B+树, SQL优化, 事务, 锁, 分库分表")),
            
            new Document("《Redis设计与实现》",
                "Redis深入解析。讲解数据结构底层实现、持久化(RDB/AOF)、主从复制、哨兵、集群、" +
                "缓存穿透/击穿/雪崩解决方案。适合Redis只会基本操作的候选人。",
                Map.of("doc_type", "course", "category", "缓存",
                       "keywords", "Redis, 缓存, 持久化, 集群, 缓存穿透, 分布式锁")),
            
            // ========== 消息队列 ==========
            new Document("《RabbitMQ实战》",
                "消息队列入门到精通。讲解消息模型、Exchange类型、消息确认机制、死信队列、延迟队列、" +
                "消息幂等性处理。适合消息队列使用经验不足的候选人。",
                Map.of("doc_type", "course", "category", "消息队列",
                       "keywords", "RabbitMQ, 消息队列, MQ, 死信队列, 延迟队列, 幂等")),
            
            new Document("《Kafka权威指南》",
                "Kafka深入学习。涵盖分区机制、副本同步、消费者组、Exactly-Once语义、性能调优。" +
                "适合大数据场景、高吞吐消息处理场景的开发者。",
                Map.of("doc_type", "course", "category", "消息队列",
                       "keywords", "Kafka, 消息队列, 分区, 高吞吐, 大数据")),
            
            // ========== 分布式 ==========
            new Document("《分布式系统设计》",
                "分布式系统核心概念。讲解CAP理论、分布式事务(2PC/TCC/Saga)、分布式锁、分布式ID生成、" +
                "一致性哈希、Raft协议。适合分布式理论基础薄弱的候选人。",
                Map.of("doc_type", "course", "category", "分布式",
                       "keywords", "分布式, CAP, 分布式事务, 分布式锁, 一致性")),
            
            // ========== 设计模式 ==========
            new Document("《设计模式：可复用面向对象软件的基础》",
                "设计模式经典书籍(GOF)。涵盖23种设计模式：工厂、单例、代理、策略、观察者、模板方法等。" +
                "适合设计模式理解不深、代码设计能力弱的候选人。",
                Map.of("doc_type", "course", "category", "设计模式",
                       "keywords", "设计模式, 工厂, 单例, 代理, 策略, 观察者")),
            
            // ========== 算法 ==========
            new Document("《算法导论》",
                "算法经典教材。涵盖排序算法、查找算法、动态规划、贪心算法、图论算法等。" +
                "适合算法基础薄弱、LeetCode刷题困难的候选人。",
                Map.of("doc_type", "course", "category", "算法",
                       "keywords", "算法, 数据结构, 排序, 动态规划, LeetCode")),
            
            // ========== 网络 ==========
            new Document("《图解HTTP》",
                "HTTP协议图解入门。讲解HTTP请求/响应、状态码、Cookie/Session、HTTPS原理、HTTP/2特性。" +
                "适合网络协议理解不深的候选人。",
                Map.of("doc_type", "course", "category", "网络",
                       "keywords", "HTTP, HTTPS, 网络协议, Cookie, Session, TCP"))
        );

        vectorStore.add(courses);
        log.info("【RAG知识库】加载完成。 目标2: 成功加载 {} 门学习资料。", courses.size());
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