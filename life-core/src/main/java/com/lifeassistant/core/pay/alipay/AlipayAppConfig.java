package com.lifeassistant.core.pay.alipay;

/**
 * 运行时实际使用的一套支付宝应用配置（沙箱或正式）：网关、APPID、应用私钥、支付宝公钥和收款账号 PID。
 * toString 不输出密钥。
 */
public record AlipayAppConfig(boolean sandbox, String serverUrl, String appId, String privateKey,
                              String alipayPublicKey, String sellerId) {

    /** 只输出环境和 APPID，避免密钥进日志。 */
    @Override
    public String toString() {
        return "AlipayAppConfig[" + (sandbox ? "sandbox" : "production") + ", appId=" + appId + "]";
    }
}
