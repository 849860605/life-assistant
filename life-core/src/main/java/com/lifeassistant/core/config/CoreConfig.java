package com.lifeassistant.core.config;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import com.lifeassistant.core.card.CardHub;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.lifeassistant.core.audit.CallRecorder;
import com.lifeassistant.core.llm.ParallelToolCallingManager;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * life-core 的基础 Bean：MyBatis-Plus 分页、密码编码器、并行工具调用、外部 HTTP 客户端，并启用 LifeProperties。
 */
@Configuration
@EnableConfigurationProperties(LifeProperties.class)
public class CoreConfig {

    /** MyBatis-Plus 分页插件，按 PostgreSQL 方言生成分页 SQL。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /** 用户密码用 BCrypt 哈希。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 模型一次要求多个工具调用时并行执行（Spring AI 默认是逐个执行），替换自动配置里的 ToolCallingManager；
     * 每次工具调用写调用日志。工具执行完顺便取走用户在回答过程中补充的话，先存进对话记录再交给模型。
     */
    @Bean
    public ToolCallingManager toolCallingManager(ObjectProvider<ToolCallbackResolver> toolCallbackResolver,
                                                 CallRecorder callRecorder, CardHub cardHub,
                                                 ObjectProvider<ChatMemory> chatMemory,
                                                 com.lifeassistant.core.conversation.TurnFlags turnFlags) {
        return new ParallelToolCallingManager(
                toolCallbackResolver.getIfAvailable(() -> new DelegatingToolCallbackResolver(List.of())), callRecorder,
                conversationId -> {
                    List<String> added = cardHub.takeInterjections(conversationId);
                    if (!added.isEmpty()) {
                        chatMemory.getObject().add(conversationId,
                                added.stream().<Message>map(UserMessage::new).toList());
                    }
                    return added;
                }, turnFlags::tooMany);
    }

    /**
     * 调用外部 HTTP 接口用的 RestClient。用 Apache HttpClient 是因为和风天气总是返回 gzip，
     * JDK 自带的客户端不会自动解压。
     */
    @Bean
    public RestClient.Builder externalRestClientBuilder() {
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(HttpClients.createSystem()));
    }
}
