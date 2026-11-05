package com.lifeassistant.core.pay.alipay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付宝收款配置，前缀 life.pay.alipay。密钥只来自环境变量（.env）或本机沙箱配置文件，不写进仓库。
 */
@Data
@ConfigurationProperties(prefix = "life.pay.alipay")
public class AlipayProperties {

    /** sandbox：读项目根目录的 .alipay-sandbox.json，走沙箱网关；production：读下面的正式应用配置，走正式网关 */
    private String mode = "sandbox";

    /** 沙箱配置文件的绝对路径；为空时从代码所在位置往上找项目根目录的 .alipay-sandbox.json，不看启动目录 */
    private String sandboxConfig;

    /** 正式应用 APPID */
    private String appId;

    /** 正式应用私钥（Java 用 PKCS#8，原始字符串，不带 PEM 头尾） */
    private String privateKey;

    /** 正式支付宝公钥（原始字符串，不带 PEM 头尾） */
    private String alipayPublicKey;

    /** 正式收款账号的 PID（2088 开头），异步通知里的 seller_id 必须和它一致 */
    private String sellerId;

    /** 正式网关地址 */
    private String gateway = "https://openapi.alipay.com/gateway.do";

    /** 异步通知地址，必须是支付宝能访问到的公网 HTTPS 地址；本地没有时留空，不传给支付宝，靠主动查询确认结果 */
    private String notifyUrl;

    /** 同步回跳地址：付款后浏览器回到的结果页，对应 GET /api/v1/pay/alipay/return */
    private String returnUrl;
}
