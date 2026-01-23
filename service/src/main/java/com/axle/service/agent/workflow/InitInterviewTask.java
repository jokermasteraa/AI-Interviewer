package com.axle.service.agent.workflow;

import com.axle.service.JobService;
import com.axle.service.ResumeService;
import com.axle.pojo.Job;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 初始化面试任务
 * 加载简历和JD，解析关键技术点
 */
@Component("initInterviewTask")
@Slf4j
public class InitInterviewTask implements JavaDelegate, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    private ResumeService getResumeService() {
        return applicationContext.getBean(ResumeService.class);
    }

    private JobService getJobService() {
        return applicationContext.getBean(JobService.class);
    }

    @Override
    public void execute(DelegateExecution execution) {
        String candidateId = (String) execution.getVariable("candidateId");
        String jobId = (String) execution.getVariable("jobId");
        
        log.info("【初始化任务】开始初始化，候选人ID: {}, 职位ID: {}", candidateId, jobId);

        // 获取简历
        ResumeService resumeService = getResumeService();
        String resume = resumeService.getResume(candidateId);
        if (resume == null || resume.isEmpty()) {
            log.warn("【初始化任务】简历为空，使用默认提示");
            resume = "候选人未提供简历，请基于通用技术面试提问。";
        }

        // 获取JD
        String jd = "";
        String jobName = "";
        try {
            JobService jobService = getJobService();
            Job job = jobService.getDetail(jobId);
            if (job != null) {
                jd = job.getJobDesc() != null ? job.getJobDesc() : "";
                jobName = job.getJobName() != null ? job.getJobName() : "";
            }
        } catch (Exception e) {
            log.warn("【初始化任务】获取JD失败: {}", e.getMessage());
        }

        // 提取JD中的技术维度
        List<String> jdTechDimensions = extractJDTechDimensions(jd);

        // 设置流程变量
        execution.setVariable("resume", resume);
        execution.setVariable("jd", jd);
        execution.setVariable("jobName", jobName);
        execution.setVariable("jdTechDimensions", jdTechDimensions);
        execution.setVariable("coveredTechDimensions", new ArrayList<String>());
        execution.setVariable("qaHistory", new ArrayList<>());
        execution.setVariable("questionCount", 0);

        log.info("【初始化任务】初始化完成，技术维度数: {}", jdTechDimensions.size());
    }

    /**
     * 提取JD中的技术维度
     */
    private List<String> extractJDTechDimensions(String jd) {
        List<String> dimensions = new ArrayList<>();
        if (jd == null || jd.trim().isEmpty()) {
            return dimensions;
        }
        
        // 常见技术关键词
        String[] techKeywords = {
            "Java", "Spring", "Spring Boot", "MyBatis", "MySQL", "Redis", 
            "Kafka", "RabbitMQ", "Docker", "Kubernetes", "微服务", "分布式",
            "Vue", "React", "前端", "后端", "Python", "Go", "算法", "数据结构",
            "高并发", "性能优化", "缓存", "消息队列", "数据库", "事务", "锁"
        };
        
        String jdLower = jd.toLowerCase();
        for (String keyword : techKeywords) {
            if (jdLower.contains(keyword.toLowerCase())) {
                dimensions.add(keyword);
            }
        }
        
        // 如果没找到，至少添加一些通用维度
        if (dimensions.isEmpty()) {
            dimensions.add("技术基础");
            dimensions.add("项目经验");
        }
        
        return dimensions;
    }
}
