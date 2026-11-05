package com.lifeassistant.core.pay.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradeFastpayRefundQueryModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝开放平台 SDK 的薄封装：网页支付下单、交易查询、退款、退款查询、关单和验签。
 * 客户端第一次用到时才按配置创建，配置缺失时明确报错；沙箱和正式只是配置不同，走同一套代码。
 */
@Component
@RequiredArgsConstructor
public class AlipayGateway {

    private static final String CHARSET = "UTF-8";
    private static final String SIGN_TYPE = "RSA2";
    /** 网页支付固定的产品码。 */
    private static final String PAGE_PAY_PRODUCT_CODE = "FAST_INSTANT_TRADE_PAY";

    private final AlipayConfigLoader configLoader;
    private final AlipayProperties properties;

    private volatile AlipayAppConfig appConfig;
    private volatile AlipayClient client;

    /** 一次接口调用的结果：success 为 false 时 code、subCode、subMsg 是支付宝给的错误信息。 */
    public record CallResult(boolean success, String code, String subCode, String subMsg) {
    }

    /** 交易查询结果：tradeStatus 为空表示没查到或调用失败。 */
    public record TradeQuery(CallResult call, String outTradeNo, String tradeNo, String tradeStatus, String totalAmount) {
    }

    /** 退款结果：fundChange 为 Y 才通常表示资金已经退回。 */
    public record RefundResult(CallResult call, String fundChange, String refundFee) {
    }

    /** 退款查询结果：refundStatus 为 REFUND_SUCCESS 才是退款成功。 */
    public record RefundQuery(CallResult call, String refundStatus, String refundAmount) {
    }

    /** 当前使用的应用配置（第一次调用时加载）。 */
    public AlipayAppConfig config() {
        ensureClient();
        return appConfig;
    }

    /**
     * 网页支付下单（alipay.trade.page.pay），用页面跳转方法 pageExecute(POST) 拿到会自动提交的 HTML 表单。
     * 配了 notifyUrl 才传异步通知地址；returnUrl 为空表示不用同步回跳。
     */
    public String pagePay(String outTradeNo, String totalAmount, String subject) throws AlipayApiException {
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        if (StringUtils.hasText(properties.getNotifyUrl())) {
            request.setNotifyUrl(properties.getNotifyUrl().trim());
        }
        if (StringUtils.hasText(properties.getReturnUrl())) {
            request.setReturnUrl(properties.getReturnUrl().trim());
        }
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(outTradeNo);
        model.setTotalAmount(totalAmount);
        model.setSubject(subject);
        model.setProductCode(PAGE_PAY_PRODUCT_CODE);
        request.setBizModel(model);
        AlipayTradePagePayResponse response = ensureClient().pageExecute(request, "POST");
        if (!response.isSuccess() || !StringUtils.hasText(response.getBody())) {
            throw new AlipayApiException(response.getSubCode(), response.getSubMsg());
        }
        return response.getBody();
    }

    /** 交易查询（alipay.trade.query），按商户订单号查。 */
    public TradeQuery query(String outTradeNo) throws AlipayApiException {
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(outTradeNo);
        request.setBizModel(model);
        AlipayTradeQueryResponse response = ensureClient().execute(request);
        return new TradeQuery(result(response.isSuccess(), response.getCode(), response.getSubCode(), response.getSubMsg()),
                response.getOutTradeNo(), response.getTradeNo(), response.getTradeStatus(), response.getTotalAmount());
    }

    /** 退款（alipay.trade.refund）：同一次退款重试时 outRequestNo、金额、原因都必须和第一次一样。 */
    public RefundResult refund(String outTradeNo, String refundAmount, String reason, String outRequestNo)
            throws AlipayApiException {
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        model.setOutTradeNo(outTradeNo);
        model.setRefundAmount(refundAmount);
        if (StringUtils.hasText(reason)) {
            model.setRefundReason(reason);
        }
        model.setOutRequestNo(outRequestNo);
        request.setBizModel(model);
        AlipayTradeRefundResponse response = ensureClient().execute(request);
        return new RefundResult(result(response.isSuccess(), response.getCode(), response.getSubCode(), response.getSubMsg()),
                response.getFundChange(), response.getRefundFee());
    }

    /** 退款查询（alipay.trade.fastpay.refund.query）。 */
    public RefundQuery refundQuery(String outTradeNo, String outRequestNo) throws AlipayApiException {
        AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
        AlipayTradeFastpayRefundQueryModel model = new AlipayTradeFastpayRefundQueryModel();
        model.setOutTradeNo(outTradeNo);
        model.setOutRequestNo(outRequestNo);
        request.setBizModel(model);
        AlipayTradeFastpayRefundQueryResponse response = ensureClient().execute(request);
        return new RefundQuery(result(response.isSuccess(), response.getCode(), response.getSubCode(), response.getSubMsg()),
                response.getRefundStatus(), response.getRefundAmount());
    }

    /** 关闭未付款交易（alipay.trade.close）。 */
    public CallResult close(String outTradeNo) throws AlipayApiException {
        AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
        AlipayTradeCloseModel model = new AlipayTradeCloseModel();
        model.setOutTradeNo(outTradeNo);
        request.setBizModel(model);
        AlipayTradeCloseResponse response = ensureClient().execute(request);
        return result(response.isSuccess(), response.getCode(), response.getSubCode(), response.getSubMsg());
    }

    /** 用支付宝公钥验签（异步通知、同步回跳参数），传入完整参数，不提前删改任何字段。 */
    public boolean verify(Map<String, String> params) {
        AlipayAppConfig config = config();
        try {
            return AlipaySignature.rsaCheckV1(new HashMap<>(params), config.alipayPublicKey(), CHARSET,
                    params.getOrDefault("sign_type", SIGN_TYPE));
        } catch (AlipayApiException e) {
            return false;
        }
    }

    /** 第一次用到时按配置创建 SDK 客户端，之后复用。 */
    private AlipayClient ensureClient() {
        AlipayClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                AlipayAppConfig loaded = configLoader.load();
                AlipayConfig sdkConfig = new AlipayConfig();
                sdkConfig.setServerUrl(loaded.serverUrl());
                sdkConfig.setAppId(loaded.appId());
                sdkConfig.setPrivateKey(loaded.privateKey());
                sdkConfig.setFormat("json");
                sdkConfig.setAlipayPublicKey(loaded.alipayPublicKey());
                sdkConfig.setCharset(CHARSET);
                sdkConfig.setSignType(SIGN_TYPE);
                AlipayClient created;
                try {
                    created = new DefaultAlipayClient(sdkConfig);
                } catch (AlipayApiException e) {
                    throw new IllegalStateException("支付宝客户端初始化失败，请检查应用配置", e);
                }
                // 先放配置再放客户端：别的线程看到客户端时配置一定已经就绪
                appConfig = loaded;
                client = created;
            }
            return client;
        }
    }

    /** 统一包装调用结果。 */
    private static CallResult result(boolean success, String code, String subCode, String subMsg) {
        return new CallResult(success, code, subCode, subMsg);
    }
}
