package com.axle.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 智谱AI GLM-ASR-2512语音识别服务
 * 使用智谱AI的GLM-ASR-2512模型进行语音转文字
 * 支持流式和非流式两种模式
 */
@Service
@Slf4j
public class ZhipuAiSpeechService {

    @Value("${spring.ai.zhipuai.api-key}")
    private String apiKey;
    
    // 智谱AI GLM-ASR-2512 语音识别API端点
    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions";
    
    /**
     * 语音识别（非流式，使用智谱AI GLM-ASR-2512模型）
     * @param audioData 音频数据
     * @param format 音频格式
     * @return 识别结果文本
     */
    public String recognize(byte[] audioData, String format) throws Exception {
        return recognize(audioData, format, false);
    }

    /**
     * 语音识别（支持流式和非流式，仅使用智谱AI）
     * @param audioData 音频数据
     * @param format 音频格式
     * @param stream 是否使用流式模式
     * @return 识别结果文本（流式模式下返回完整文本）
     */
    public String recognize(byte[] audioData, String format, boolean stream) throws Exception {
        log.info("【语音识别】开始转写，格式: {}, 大小: {} bytes, 流式: {}", format, audioData.length, stream);
        
        if (stream) {
            // 流式模式：实时接收识别结果
            return recognizeStream(audioData, format);
        } else {
            // 非流式模式：等待完整结果
            return recognizeNonStream(audioData, format);
        }
    }
    

    /**
     * 非流式识别（仅使用智谱AI）
     */
    private String recognizeNonStream(byte[] audioData, String format) throws Exception {
        long startTime = System.currentTimeMillis();
        String responseJson = callApi(audioData, format, false);
        String text = parseResponse(responseJson);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("【智谱AI语音识别】完成，耗时: {}ms，结果: {}", duration, text);
        
        return text;
    }

    /**
     * 流式识别（实时接收识别结果）
     */
    private String recognizeStream(byte[] audioData, String format) throws Exception {
        StringBuilder fullText = new StringBuilder();
        
        // 调用流式API
        callStreamApi(audioData, format, chunk -> {
            if (chunk != null && !chunk.isEmpty()) {
                fullText.append(chunk);
                log.debug("【智谱AI语音识别-流式】接收到文本块: {}", chunk);
            }
        });
        
        String result = fullText.toString();
        log.info("【智谱AI语音识别-流式】完成，总长度: {}", result.length());
        return result;
    }

    /**
     * 调用智谱AI API（非流式）
     * 注意：智谱AI GLM-ASR-2512最多支持30秒音频
     */
    private String callApi(byte[] audioData, String format, boolean stream) throws Exception {
        // 检查音频时长（粗略估算：假设mp3格式约1MB=1分钟，wav格式约10MB=1分钟）
        // 实际应该解析音频文件头获取时长，这里先做简单检查
        if (audioData.length > 25 * 1024 * 1024) {
            throw new RuntimeException("音频文件过大，智谱AI GLM-ASR-2512最多支持25MB");
        }
        
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        
        // 使用multipart/form-data格式
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        
        try (OutputStream os = conn.getOutputStream()) {
            // 写入表单字段 - model
            writeFormField(os, boundary, "model", "glm-asr-2512");
            
            // 写入表单字段 - stream
            writeFormField(os, boundary, "stream", String.valueOf(stream));
            
            // 写入表单字段 - language
            writeFormField(os, boundary, "language", "zh");
            
            // 写入音频文件
            writeFileField(os, boundary, "file", "audio." + format, "audio/" + format, audioData);
            
            // 结束boundary
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        
        // 读取响应
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                throw new RuntimeException("API调用失败，状态码: " + responseCode + ", 错误信息: " + errorResponse.toString());
            }
        }
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    /**
     * 调用流式API（实时接收识别结果）
     */
    private void callStreamApi(byte[] audioData, String format, StreamCallback callback) throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(120000); // 流式模式可能需要更长的超时时间
        
        // 使用multipart/form-data格式
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        
        try (OutputStream os = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {
            
            // 写入表单字段
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            writer.append("glm-asr-2512").append("\r\n");
            
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"stream\"\r\n\r\n");
            writer.append("true").append("\r\n");
            
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
            writer.append("zh").append("\r\n");
            
            // 写入音频文件
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.").append(format).append("\"\r\n");
            writer.append("Content-Type: audio/").append(format).append("\r\n\r\n");
            writer.flush();
            
            os.write(audioData);
            os.flush();
            
            writer.append("\r\n");
            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();
        }
        
        // 读取流式响应
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            try (BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                throw new RuntimeException("API调用失败，状态码: " + responseCode + ", 错误信息: " + errorResponse.toString());
            }
        }
        
        // 流式读取响应（SSE格式）
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String jsonData = line.substring(6); // 去掉 "data: " 前缀
                    if (jsonData.trim().equals("[DONE]")) {
                        break; // 流式结束标记
                    }
                    String text = parseStreamResponse(jsonData);
                    if (text != null && !text.isEmpty()) {
                        callback.onChunk(text);
                    }
                }
            }
        }
    }
    
    /**
     * 解析API响应（非流式）
     */
    private String parseResponse(String responseJson) throws Exception {
        try {
            JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
            
            // 检查是否有错误
            if (root.has("error")) {
                JsonObject error = root.getAsJsonObject("error");
                String errorMsg = error.has("message") ? error.get("message").getAsString() : "未知错误";
                throw new RuntimeException("智谱AI API错误: " + errorMsg);
            }
            
            // 提取识别结果
            if (root.has("text")) {
                return root.get("text").getAsString();
            }
            
            // 如果没有text字段，尝试其他可能的字段
            if (root.has("result")) {
                JsonObject result = root.getAsJsonObject("result");
                if (result.has("text")) {
                    return result.get("text").getAsString();
                }
            }
            
            throw new RuntimeException("无法从响应中提取识别结果: " + responseJson);
        } catch (Exception e) {
            log.error("【智谱AI语音识别】解析响应失败: {}", responseJson, e);
            throw e;
        }
    }

    /**
     * 解析流式响应
     */
    private String parseStreamResponse(String jsonData) {
        try {
            JsonObject root = JsonParser.parseString(jsonData).getAsJsonObject();
            
            // 流式响应可能包含增量文本
            if (root.has("text")) {
                return root.get("text").getAsString();
            }
            
            // 或者包含delta字段（增量更新）
            if (root.has("delta")) {
                JsonObject delta = root.getAsJsonObject("delta");
                if (delta.has("text")) {
                    return delta.get("text").getAsString();
                }
            }
            
            return null;
        } catch (Exception e) {
            log.error("【智谱AI语音识别-流式】解析响应失败: {}", jsonData, e);
            return null;
        }
    }

    /**
     * 写入表单字段
     */
    private void writeFormField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 写入文件字段
     */
    private void writeFileField(OutputStream os, String boundary, String fieldName, String fileName, String contentType, byte[] fileData) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(fileData);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 流式回调接口
     */
    @FunctionalInterface
    public interface StreamCallback {
        void onChunk(String text);
    }
}

