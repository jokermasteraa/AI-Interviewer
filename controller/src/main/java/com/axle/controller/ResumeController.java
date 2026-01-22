package com.axle.controller;

import com.axle.graceresult.GraceJSONResult;
import com.axle.graceresult.ResponseStatusEnum;
import com.axle.service.ResumeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.Map;

/**
 * 简历上传控制器
 */
@RestController
@RequestMapping("/resume")
@Slf4j
public class ResumeController {

    @Resource
    private ResumeService resumeService;

    /**
     * 上传简历
     * @param candidateId 候选人ID
     * @param file 简历文件
     * @return 处理结果
     */
    @PostMapping("/upload")
    public GraceJSONResult uploadResume(@RequestParam("candidateId") String candidateId,
                                        @RequestParam("file") MultipartFile file) {
        if (StringUtils.isBlank(candidateId)) {
            return GraceJSONResult.errorMsg("候选人ID不能为空");
        }

        if (file == null || file.isEmpty()) {
            return GraceJSONResult.errorCustom(ResponseStatusEnum.FILE_UPLOAD_FAILD);
        }

        try {
            String processedResume = resumeService.uploadAndProcessResume(candidateId, file);
            log.info("【简历上传】简历上传成功，候选人ID: {}", candidateId);
            return GraceJSONResult.ok(processedResume);
        } catch (IOException e) {
            log.error("【简历上传】文件处理失败", e);
            return GraceJSONResult.errorMsg("文件处理失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("【简历上传】处理失败", e);
            return GraceJSONResult.errorMsg("处理失败: " + e.getMessage());
        }
    }

    /**
     * 获取简历
     * @param candidateId 候选人ID
     * @return 简历内容
     */
    @GetMapping("/get")
    public GraceJSONResult getResume(@RequestParam("candidateId") String candidateId) {
        if (StringUtils.isBlank(candidateId)) {
            return GraceJSONResult.errorMsg("候选人ID不能为空");
        }

        String resume = resumeService.getResume(candidateId);
        if (resume == null) {
            return GraceJSONResult.errorMsg("简历不存在或已过期");
        }
        return GraceJSONResult.ok(resume);
    }

    /**
     * 删除简历
     * @param candidateId 候选人ID
     * @return 删除结果
     */
    @DeleteMapping("/delete")
    public GraceJSONResult deleteResume(@RequestParam("candidateId") String candidateId) {
        if (StringUtils.isBlank(candidateId)) {
            return GraceJSONResult.errorMsg("候选人ID不能为空");
        }

        resumeService.deleteResume(candidateId);
        return GraceJSONResult.ok("简历已删除");
    }

    /**
     * 上传文本简历（APP平台使用）
     * @param params 包含candidateId和resumeContent的Map
     * @return 处理结果
     */
    @PostMapping("/uploadText")
    public GraceJSONResult uploadResumeText(@RequestBody Map<String, String> params) {
        String candidateId = params.get("candidateId");
        String resumeText = params.get("resumeContent");
        if (StringUtils.isBlank(candidateId)) {
            return GraceJSONResult.errorMsg("候选人ID不能为空");
        }

        if (StringUtils.isBlank(resumeText)) {
            return GraceJSONResult.errorMsg("简历内容不能为空");
        }

        try {
            String processedResume = resumeService.processResumeText(candidateId, resumeText);
            log.info("【简历上传-文本】简历上传成功，候选人ID: {}", candidateId);
            return GraceJSONResult.ok(processedResume);
        } catch (Exception e) {
            log.error("【简历上传-文本】处理失败", e);
            return GraceJSONResult.errorMsg("处理失败: " + e.getMessage());
        }
    }
}

