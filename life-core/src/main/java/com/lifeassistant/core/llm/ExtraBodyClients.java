package com.lifeassistant.core.llm;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.Map;

/**
 * 给 OpenAI 兼容接口的每个请求体固定加几项参数（思考开关，比如千问的 enable_thinking、DeepSeek 的 thinking）。
 * Spring AI 1.1 组装请求时会丢掉 OpenAiChatOptions 里的 extraBody，所以在序列化请求体这一层补上：
 * 普通请求走 RestClient，流式请求走 WebClient，两边用同一个改过的 ObjectMapper。
 */
final class ExtraBodyClients {

    private ExtraBodyClients() {
    }

    /** 普通请求用的 RestClient：请求体序列化时加上 extra。 */
    static RestClient.Builder restClient(Map<String, Object> extra) {
        ObjectMapper mapper = mapper(extra);
        return RestClient.builder().messageConverters(converters -> {
            converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
            converters.add(0, new MappingJackson2HttpMessageConverter(mapper));
        });
    }

    /** 流式请求用的 WebClient：请求体序列化时加上 extra。 */
    static WebClient.Builder webClient(Map<String, Object> extra) {
        ObjectMapper mapper = mapper(extra);
        return WebClient.builder().codecs(codecs -> {
            codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
            codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
        });
    }

    /** 只改 ChatCompletionRequest 的序列化：先按原样转成 JSON，再把 extra 的每一项放进去（同名的以 extra 为准）。 */
    static ObjectMapper mapper(Map<String, Object> extra) {
        ObjectMapper plain = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleModule module = new SimpleModule("extra-body");
        module.addSerializer(OpenAiApi.ChatCompletionRequest.class, new JsonSerializer<>() {
            @Override
            public void serialize(OpenAiApi.ChatCompletionRequest value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                ObjectNode node = plain.valueToTree(value);
                extra.forEach((k, v) -> node.set(k, plain.valueToTree(v)));
                gen.writeTree(node);
            }
        });
        return plain.copy().registerModule(module);
    }
}
