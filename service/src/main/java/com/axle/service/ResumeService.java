package com.axle.service;

import com.axle.service.RAG.TextProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 简历处理服务
 * 使用标准的 Spring AI ETL (TikaDocumentReader) 进行文档提取
 * 使用 Spring AI DocumentTransformer 进行文本清洗
 */
@Service
@Slf4j
public class ResumeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TextProcessingService textProcessingService;

    private static final String REDIS_RESUME_PREFIX = "resume:";
    private static final int RESUME_EXPIRE_HOURS = 24;
    private static final int MAX_TOKEN_LENGTH = 8000;

    // 学校/教育经历匹配（用于排除教育相关内容）
    private static final Pattern EDUCATION_PATTERN = Pattern.compile(".*(大学|学院|学校|中学|高中|本科|硕士|博士|研究生|专科|大专).*");

    // ========== 以下为备用代码：正则表达式清洗方式（已由 Spring AI DocumentTransformer 替代）==========
    // 如需切换回正则表达式方式，可以使用以下Pattern进行文本清洗
    // 预编译的正则表达式（ETL Transform 阶段备用方案）
    @SuppressWarnings("unused")
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    @SuppressWarnings("unused")
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", Pattern.CASE_INSENSITIVE);
    @SuppressWarnings("unused")
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");
    @SuppressWarnings("unused")
    private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\\n{3,}");
    @SuppressWarnings("unused")
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("[ \\t]{2,}");
    @SuppressWarnings("unused")
    private static final Pattern BRACKET_LABEL_PATTERN = Pattern.compile("【[^】]{0,15}：?】");
    @SuppressWarnings("unused")
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[(邮箱|电话|姓名|年龄|性别|地址)\\]");
    // ========== 备用代码结束 ==========

    /**
     * 上传并处理简历（标准的 Spring AI ETL 流程）
     */
    public String uploadAndProcessResume(String candidateId, MultipartFile file) throws IOException {
        log.info("【Spring AI ETL】开始处理简历，候选人ID: {}", candidateId);
        long startTime = System.currentTimeMillis();

        // 1. Extract: 使用 Spring AI TikaDocumentReader 自动解析 PDF/Word/TXT
        TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(file.getInputStream()));
        List<Document> documents = reader.get();
        
        String rawText = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
        
        // 2. Transform: 使用 Spring AI DocumentTransformer 进行文本清洗
        List<DocumentTransformer> transformers = textProcessingService.createResumeCleaningTransformers();
        String cleanedText = textProcessingService.cleanText(rawText, transformers);

        // 3. 业务逻辑处理：白名单过滤（保留有价值的内容）
        cleanedText = filterValuableContent(cleanedText);

        // 4. Load: 存储到 Redis
        String redisKey = REDIS_RESUME_PREFIX + candidateId;
        stringRedisTemplate.opsForValue().set(redisKey, cleanedText, RESUME_EXPIRE_HOURS, TimeUnit.HOURS);

        long duration = System.currentTimeMillis() - startTime;
        log.info("【Spring AI ETL】完成！耗时: {}ms", duration);

        return "简历上传成功";
    }

    /**
     * 业务逻辑处理：白名单过滤
     * 只保留有价值的内容（项目经历、工作经历、实习经历、技术栈）
     * 注意：基础清洗（隐私脱敏、格式规范化）已由 Spring AI DocumentTransformer 完成
     */
    private String filterValuableContent(String cleanedText) {
        if (cleanedText == null || cleanedText.isEmpty()) return "";

        // 按行处理，只保留有价值的内容
        String[] lines = cleanedText.split("\n");
        StringBuilder result = new StringBuilder();
        
        java.util.Set<String> seenTechStacks = new java.util.HashSet<>();
        boolean inValueSection = false;  // 是否在有价值的段落中
        String currentSectionType = null;  // 当前分类：实习经历、项目经验、技能清单
        boolean hasAddedInternshipHeader = false;  // 是否已添加"## 实习经历"标题
        boolean hasAddedProjectHeader = false;  // 是否已添加"## 项目经验"标题
        boolean hasAddedSkillHeader = false;  // 是否已添加"## 技能清单"标题
        
        for (String line : lines) {
            String trimLine = line.trim();
            if (trimLine.isEmpty() || trimLine.length() < 3) continue;
            
            // 检测是否已经是 Markdown 二级标题（## 标题）
            if (trimLine.startsWith("## ")) {
                String titleText = trimLine.substring(3).trim();
                // 标准化标题名称
                if (titleText.contains("实习") || titleText.equals("实习经历")) {
                    if (!hasAddedInternshipHeader) {
                        result.append("\n## 实习经历\n");
                        hasAddedInternshipHeader = true;
                        currentSectionType = "实习经历";
                        inValueSection = true;
                    }
                } else if (titleText.contains("项目") || titleText.equals("项目经验")) {
                    if (!hasAddedProjectHeader) {
                        result.append("\n## 项目经验\n");
                        hasAddedProjectHeader = true;
                        currentSectionType = "项目经验";
                        inValueSection = true;
                    }
                } else if (isSkillSectionTitle(titleText)) {
                    if (!hasAddedSkillHeader) {
                        result.append("\n## 技能清单\n");
                        hasAddedSkillHeader = true;
                        currentSectionType = "技能清单";
                        inValueSection = true;
                    }
                }
                continue;  // 跳过原始标题行，避免重复
            }
            
            // 检测段落标题（非Markdown格式）
            if (isSectionHeader(trimLine)) {
                if (isValuableSection(trimLine)) {
                    // 进入有价值的段落（项目/工作/实习/技能）
                    inValueSection = true;
                    String sectionTitle = cleanSectionTitle(trimLine);
                    // 标准化标题名称
                    if (sectionTitle.contains("实习")) {
                        sectionTitle = "实习经历";
                        currentSectionType = "实习经历";
                        if (!hasAddedInternshipHeader) {
                            result.append("\n## ").append(sectionTitle).append("\n");
                            hasAddedInternshipHeader = true;
                        }
                    } else if (sectionTitle.contains("项目")) {
                        sectionTitle = "项目经验";
                        currentSectionType = "项目经验";
                        if (!hasAddedProjectHeader) {
                            result.append("\n## ").append(sectionTitle).append("\n");
                            hasAddedProjectHeader = true;
                        }
                    } else if (isSkillSectionTitle(sectionTitle)) {
                        sectionTitle = "技能清单";
                        currentSectionType = "技能清单";
                        if (!hasAddedSkillHeader) {
                            result.append("\n## ").append(sectionTitle).append("\n");
                            hasAddedSkillHeader = true;
                        }
                    }
                } else {
                    // 进入无价值的段落（教育/个人信息等）
                    inValueSection = false;
                    currentSectionType = null;
                }
                continue;
            }
            
            // 技术栈行（任何位置都保留）
            if (isTechStackLine(trimLine)) {
                // 如果是第一个技术栈行，先添加"## 技能清单"标题
                if (!hasAddedSkillHeader) {
                    result.append("\n## 技能清单\n");
                    hasAddedSkillHeader = true;
                    currentSectionType = "技能清单";
                    inValueSection = true;
                }
                if (!seenTechStacks.contains(trimLine)) {
                    seenTechStacks.add(trimLine);
                    result.append(trimLine).append("\n");
                }
                continue;
            }
            
            // 项目/工作标题行（包含时间和职位）
            if (isWorkProjectTitle(trimLine)) {
                inValueSection = true;
                
                // 判断是实习还是项目
                boolean isInternship = trimLine.contains("实习") || trimLine.contains("实习生");
                
                // 如果是第一个工作/项目标题，先添加对应的二级标题
                if (isInternship && !hasAddedInternshipHeader) {
                    result.append("\n## 实习经历\n");
                    hasAddedInternshipHeader = true;
                    currentSectionType = "实习经历";
                } else if (!isInternship && !hasAddedProjectHeader) {
                    result.append("\n## 项目经验\n");
                    hasAddedProjectHeader = true;
                    currentSectionType = "项目经验";
                }
                
                result.append("\n### ").append(trimLine).append("\n");
                continue;
            }
            
            // 在有价值的段落中，保留工作内容描述
            if (inValueSection && isValuableContent(trimLine)) {
                result.append(trimLine).append("\n");
            }
        }

        // 去重：移除重复的标题
        String finalResult = result.toString();
        // 使用正则表达式移除重复的标题（相同标题只保留第一个）
        // 匹配 "## 实习经历" 或 "## 项目经验" 或 "## 技能清单"
        finalResult = finalResult.replaceAll("(## (实习经历|项目经验|技能清单)\\n)+", "## $2\n");
        // 规范化换行
        finalResult = finalResult.replaceAll("\n{3,}", "\n\n").trim();
        
        return finalResult.length() > MAX_TOKEN_LENGTH ? finalResult.substring(0, MAX_TOKEN_LENGTH) : finalResult;
    }
    
    /**
     * 判断是否为技能相关的标题（支持多种变体）
     * 支持：技能清单、技能栈、技术栈、专业技能、技术技能等
     */
    private boolean isSkillSectionTitle(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        String lowerTitle = title.toLowerCase();
        // 支持多种变体：技能清单、技能栈、技术栈、专业技能、技术技能等
        return lowerTitle.contains("技能清单") || 
               lowerTitle.contains("技能栈") || 
               lowerTitle.contains("技术栈") ||
               lowerTitle.contains("技能") || 
               lowerTitle.contains("技术") ||
               lowerTitle.equals("技能清单") ||
               lowerTitle.equals("技能栈") ||
               lowerTitle.equals("技术栈");
    }
    
    /**
     * 判断是否为段落标题
     */
    private boolean isSectionHeader(String line) {
        if (line.length() < 15) {
            return line.matches(".*(经历|经验|技能|评价|简介|信息|背景|意向|奖项|证书|荣誉).*");
        }
        return false;
    }
    
    /**
     * 判断是否为有价值的段落（项目/工作/实习/技能）
     */
    private boolean isValuableSection(String line) {
        return line.matches(".*(项目|工作|实习|技术|技能|专业技能).*") && 
               !line.contains("意向") && !line.contains("期望");
    }
    
    /**
     * 清理段落标题
     */
    private String cleanSectionTitle(String line) {
        return line.replaceAll("[【】\\[\\]：:]", "").trim();
    }
    
    /**
     * 判断是否为项目/工作标题行（公司名+时间+职位）
     */
    private boolean isWorkProjectTitle(String line) {
        // 排除教育相关
        if (EDUCATION_PATTERN.matcher(line).matches()) {
            return false;
        }
        
        // 包含时间（年月）
        boolean hasTime = line.matches(".*\\d{4}.*[年月.].*") || line.matches(".*\\d{4}\\.\\d{1,2}.*");
        // 包含职位或公司关键词
        boolean hasRole = line.matches(".*(工程师|开发|实习|负责人|经理|主管|专员|架构师|运维|测试|产品).*");
        boolean hasCompany = line.matches(".*(公司|有限|科技|集团|项目|平台|系统|网络|互联网|信息|软件).*");
        
        return hasTime && (hasRole || hasCompany);
    }
    
    /**
     * 判断是否为有价值的内容（工作描述、技术实现等）
     */
    private boolean isValuableContent(String line) {
        // 数字编号的内容（1. 2. 3.）
        if (line.matches("^\\d+[.、].*")) {
            return true;
        }
        // 包含工作相关动词的描述
        String workVerbs = ".*(负责|实现|开发|使用|采用|基于|通过|完成|优化|设计|搭建|维护|处理|解决|参与|独立|编写|部署|测试|集成|封装|调用|配置|实现了|完成了|负责了).*";
        if (line.length() > 15 && line.matches(workVerbs)) {
            return true;
        }
        // 包含技术关键词的内容（确保有足够长度，排除简短的无意义行）
        if (line.length() > 25 && line.matches(".*[A-Za-z].*")) {
            return true;
        }
        // 项目简介类内容
        if (line.contains("本项目") || line.contains("该项目") || line.contains("该系统")) {
            return true;
        }
        return false;
    }
    
    /**
     * 判断是否为技术栈行（如：SpringBoot、MySQL、Redis...）
     */
    private boolean isTechStackLine(String line) {
        if (line.contains("、") || line.contains(",")) {
            String[] techs = line.split("[、,，]");
            int techCount = 0;
            for (String tech : techs) {
                String t = tech.trim();
                if (t.matches(".*[A-Za-z].*") && t.length() < 30) {
                    techCount++;
                }
            }
            return techCount >= 3;
        }
        return false;
    }
    /**
     * 处理文本简历（APP平台使用）
     * @param candidateId 候选人ID
     * @param resumeText 简历文本内容
     * @return 处理结果
     */
    public String processResumeText(String candidateId, String resumeText) {
        log.info("【简历处理-文本】开始处理文本简历，候选人ID: {}", candidateId);
        
        // 使用 Spring AI DocumentTransformer 进行文本清洗
        List<DocumentTransformer> transformers = textProcessingService.createResumeCleaningTransformers();
        String cleanedText = textProcessingService.cleanText(resumeText, transformers);

        // 业务逻辑处理：白名单过滤
        cleanedText = filterValuableContent(cleanedText);

        // 存储到 Redis
        stringRedisTemplate.opsForValue().set(REDIS_RESUME_PREFIX + candidateId, cleanedText, RESUME_EXPIRE_HOURS, TimeUnit.HOURS);
        
        log.info("【简历处理-文本】简历处理成功，候选人ID: {}", candidateId);
        return "简历解析成功";
    }

    public String getResume(String candidateId) {
        return stringRedisTemplate.opsForValue().get(REDIS_RESUME_PREFIX + candidateId);
    }

    public void deleteResume(String candidateId) {
        stringRedisTemplate.delete(REDIS_RESUME_PREFIX + candidateId);
    }

    public boolean hasResume(String candidateId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(REDIS_RESUME_PREFIX + candidateId));
    }

    /**
     * 备用方法：使用正则表达式进行文本清洗（已废弃，由 Spring AI DocumentTransformer 替代）
     * 如需使用，可调用此方法替代 DocumentTransformer 方式
     */
//    @SuppressWarnings("unused")
//    private String transformResumeWithRegex(String rawText) {
//        if (rawText == null || rawText.isEmpty()) return "";
//
//        // 1. 移除敏感信息
//        String cleaned = PHONE_PATTERN.matcher(rawText).replaceAll("");
//        cleaned = EMAIL_PATTERN.matcher(cleaned).replaceAll("");
//        cleaned = ID_CARD_PATTERN.matcher(cleaned).replaceAll("");
//        cleaned = PLACEHOLDER_PATTERN.matcher(cleaned).replaceAll("");
//        cleaned = BRACKET_LABEL_PATTERN.matcher(cleaned).replaceAll("");
//
//        // 2. 规范化空白字符
//        cleaned = MULTI_NEWLINE_PATTERN.matcher(cleaned).replaceAll("\n");
//        cleaned = MULTI_SPACE_PATTERN.matcher(cleaned).replaceAll(" ");
//        cleaned = cleaned.replaceAll(" / ", " ").replaceAll(" /", " ").replaceAll("/ ", " ");
//
//        return cleaned;
//    }
}
