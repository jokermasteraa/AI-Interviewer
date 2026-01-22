package com.axle.base;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class ZhipuAiConfig {



    @Value("${spring.ai.zhipuai.api-key}")
    private String apiKey ;


//    @Value("${dashscope.api-key}")
//    private String dashscopeApiKey;
//

    @Bean
    public ZhiPuAiChatModel zhipuAiChatModel() {
        return new ZhiPuAiChatModel(new ZhiPuAiApi(apiKey)); // 先创建 ZhipuAiApi
    }

//    @Bean
//    public DashScopeChatModel dashscopeChatModel() {
//        return new DashScopeChatModel(dashscopeApiKey);
//    }


    /**
     * 为 Spring AI 自动配置的 RestClient (HTTP 客户端) 增加超时时间
     * * Spring AI 1.0.0 (及更高版本) 会自动使用这个 RestClientCustomizer
     * 来定制用于调用 AI API 的 RestClient.Builder。
     * * 这将修复因AI处理大型Token（如最终总结）耗时过长
     * 而导致的 SocketTimeoutException。
     */
    @Bean
    public RestClientCustomizer restClientTimeoutCustomizer() {
        return (restClientBuilder) -> restClientBuilder
                .requestFactory(
                        // 使用一个底层的 ClientHttpRequestFactory 来设置超时
                        new SimpleClientHttpRequestFactory() {{
                            // 设置读取超时时间为 3 分钟
                            setReadTimeout(Duration.ofMinutes(3));
                            // 设置连接超时时间为 30 秒
                            setConnectTimeout(Duration.ofSeconds(30));
                        }}
                );
    }
}