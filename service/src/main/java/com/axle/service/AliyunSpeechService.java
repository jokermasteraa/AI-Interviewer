package com.axle.service;

import com.alibaba.dashscope.audio.asr.transcription.Transcription;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionParam;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionQueryParam;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.Collections;

/**
 * 阿里云DashScope语音识别服务（录音文件转写）
 * 使用paraformer模型，与Qwen共用同一个API Key
 * 
 * 【已禁用】系统已统一使用智谱AI进行语音识别，此服务不再使用
 */
// @Service  // 已禁用，不再使用阿里云服务
@Slf4j
public class AliyunSpeechService {

    // @Value("${spring.ai.dashscope.api-key}")  // 已禁用，配置已注释
    private String apiKey;

    /**
     * 语音识别（通过音频数据，使用data URI）
     * @param audioData 音频数据
     * @param format 音频格式
     * @return 识别结果文本
     */
    public String recognize(byte[] audioData, String format) throws Exception {
        long startTime = System.currentTimeMillis();
        log.info("【DashScope语音识别】开始转写，格式: {}, 大小: {} bytes", format, audioData.length);

        // 将音频数据转为data URI格式
        String base64Audio = Base64.getEncoder().encodeToString(audioData);
        String mimeType = getMimeType(format);
        String dataUri = "data:" + mimeType + ";base64," + base64Audio;

        TranscriptionParam param = TranscriptionParam.builder()
                .apiKey(apiKey)
                .model("paraformer-v2")
                .fileUrls(Collections.singletonList(dataUri))
                .parameter("language_hints", new String[]{"zh", "en"})
                .build();

        Transcription transcription = new Transcription();
        
        // 提交转写请求
        TranscriptionResult result = transcription.asyncCall(param);
        log.info("【DashScope语音识别】任务已提交，TaskId: {}", result.getTaskId());

        // 等待任务完成
        result = transcription.wait(
                TranscriptionQueryParam.FromTranscriptionParam(param, result.getTaskId()));

        // 解析结果
        JsonObject output = result.getOutput();
        String text = parseTranscriptionResult(output);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("【DashScope语音识别】完成，耗时: {}ms，结果: {}", duration, text);
        
        return text;
    }

    private String getMimeType(String format) {
        return switch (format.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "pcm" -> "audio/pcm";
            case "ogg" -> "audio/ogg";
            case "m4a" -> "audio/mp4";
            default -> "audio/" + format;
        };
    }

    /**
     * 解析转写结果
     */
    private String parseTranscriptionResult(JsonObject output) throws Exception {
        if (output == null) {
            throw new RuntimeException("转写结果为空");
        }

        JsonArray results = output.getAsJsonArray("results");
        if (results == null || results.size() == 0) {
            throw new RuntimeException("转写结果为空");
        }

        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < results.size(); i++) {
            JsonObject taskResult = results.get(i).getAsJsonObject();
            if (taskResult.has("transcription_url")) {
                String transcriptionUrl = taskResult.get("transcription_url").getAsString();
                String jsonResult = fetchUrl(transcriptionUrl);
                String text = extractTextFromJson(jsonResult);
                sb.append(text);
            }
        }

        return sb.toString().trim();
    }

    private String extractTextFromJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray transcripts = root.getAsJsonArray("transcripts");
            if (transcripts != null && transcripts.size() > 0) {
                JsonObject transcript = transcripts.get(0).getAsJsonObject();
                if (transcript.has("text")) {
                    return transcript.get("text").getAsString();
                }
            }
        } catch (Exception e) {
            log.error("【DashScope语音识别】解析JSON失败", e);
        }
        return "";
    }

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}

