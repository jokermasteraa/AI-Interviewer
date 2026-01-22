package com.axle.controller;

import com.axle.graceresult.GraceJSONResult;
import com.axle.graceresult.ResponseStatusEnum;
import com.axle.service.ZhipuAiSpeechService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 智谱AI语音识别控制器
 * 使用智谱AI GLM-ASR-2512模型进行语音识别
 */
@RestController
@RequestMapping("speech")
@Slf4j
public class AliyunSpeechController {

    @Resource
    private ZhipuAiSpeechService zhipuAiSpeechService;

    @PostMapping("uploadVoice")
    public GraceJSONResult uploadVoice(@RequestParam("file") MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            if (StringUtils.isBlank(filename)) {
                return GraceJSONResult.errorCustom(ResponseStatusEnum.FILE_UPLOAD_NULL_ERROR);
            }

            log.info("【语音识别】收到文件: {}, 大小: {} bytes", filename, file.getSize());

            // 获取文件格式
            String format = "mp3";
            if (filename.contains(".")) {
                format = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
            }
            
            // 使用智谱AI GLM-ASR-2512进行识别（默认非流式模式）
            byte[] audioData = file.getBytes();
            String result = zhipuAiSpeechService.recognize(audioData, format);
            
            log.info("【语音识别】识别结果: {}", result);
            return GraceJSONResult.ok(result);
            
        } catch (Exception e) {
            log.error("【语音识别】失败", e);
            return GraceJSONResult.errorMsg("语音识别失败: " + e.getMessage());
        }
    }
}

