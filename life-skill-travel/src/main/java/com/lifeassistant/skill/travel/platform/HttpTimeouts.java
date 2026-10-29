package com.lifeassistant.skill.travel.platform;

import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/** 平台接口响应要几秒到几十秒，各客户端用单独的读超时。 */
final class HttpTimeouts {

    private HttpTimeouts() {
    }

    /** 连接超时 10 秒，读超时按参数。 */
    static JdkClientHttpRequestFactory factory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
