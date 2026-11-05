package com.lifeassistant.core.pay.alipay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用支付宝收款配置 life.pay.alipay；SDK 客户端由 AlipayGateway 第一次用到时创建，没配置时不影响应用启动。
 */
@Configuration
@EnableConfigurationProperties(AlipayProperties.class)
public class AlipayPayConfig {
}
