package com.lifeassistant.core.pay;

import com.alipay.api.AlipayApiException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.member.CreditService;
import com.lifeassistant.core.member.MembershipService;
import com.lifeassistant.core.pay.alipay.AlipayAppConfig;
import com.lifeassistant.core.pay.alipay.AlipayGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝 AI 网页应用收款：下单、查询、关单、退款、退款查询，以及异步通知和同步回跳的处理。
 * 订单状态只认验签通过的异步通知和主动查询结果，同步回跳参数只用来定位订单。
 * 用户只能按商品下单（金额由服务端定），付款成功时按商品发放权益；退款只开放给后台。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlipayPayService {

    static final String WAIT_PAY = "WAIT_PAY";
    static final String PAID = "PAID";
    static final String CLOSED = "CLOSED";
    static final String REFUND_PENDING = "PENDING";
    static final String REFUND_SUCCESS = "SUCCESS";
    static final String REFUND_FAILED = "FAILED";

    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AlipayGateway gateway;
    private final PayOrderMapper orderMapper;
    private final PayRefundMapper refundMapper;
    private final PayNotifyEventMapper notifyEventMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final MembershipService membership;
    private final CreditService credits;

    /** 下单结果：商户订单号和要在浏览器里渲染并自动提交的支付表单。 */
    public record CreatedOrder(String outTradeNo, String formHtml) {
    }

    /** 订单给前端看的样子：金额是两位小数的字符串，refundedAmount 是已确认成功的退款合计。 */
    public record OrderView(String outTradeNo, String subject, String totalAmount, String status, String tradeNo,
                            OffsetDateTime paidAt, OffsetDateTime createdAt, String refundedAmount,
                            List<RefundView> refunds) {
    }

    /** 主动查询结果：state 是 PAID / PENDING_PAYMENT / CLOSED / UNKNOWN，message 说明未知原因。 */
    public record QueryView(String state, String message, OrderView order) {
    }

    /** 退款请求给前端看的样子。 */
    public record RefundView(String outRequestNo, String outTradeNo, String refundAmount, String status,
                             String message, OffsetDateTime createdAt) {
    }

    /** 后台订单列表的一行：订单视图加上下单用户。 */
    public record AdminOrderView(long userId, OrderView order) {
    }

    /** 同步回跳确认结果：state 多了 NONE（没有可确认的参数）和 INVALID（验签或归属校验没通过）。 */
    public record ReturnView(String state, String message, String outTradeNo, String subject, String totalAmount) {
    }

    // ========== 下单 ==========

    /** 支付宝收款配置好了没有（缺配置或密钥格式不对时页面上不能付款，不影响会员页其他内容）。 */
    public boolean ready() {
        try {
            gateway.config();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 按月的会员已经改成积分，不再卖；老会员到期前照常用。 */
    public CreatedOrder createMemberOrder(long userId) {
        throw new BizException(ErrorCode.BAD_REQUEST, "会员已经改成积分了，按用多少扣多少，去买积分吧");
    }

    /** 买一个积分包：金额和积分数都按服务端现在的积分包定，商品编码里带着积分数（到账按它算）。 */
    public CreatedOrder createCreditOrder(long userId, String packCode) {
        CreditService.Pack pack = credits.pack(packCode);
        return createOrder(userId, pack.code(), "星络积分 · " + pack.credits() + " 积分", pack.yuan());
    }

    /** 先落库一笔待支付订单，再调 alipay.trade.page.pay 拿支付表单；调用失败时订单留在待支付，可以查询或关闭。 */
    private CreatedOrder createOrder(long userId, String productCode, String subject, BigDecimal amount) {
        String title = cleanSubject(subject);
        BigDecimal total = checkAmount(amount);
        AlipayAppConfig config = gateway.config();
        PayOrder order = new PayOrder();
        order.setUserId(userId);
        order.setOutTradeNo(newNo("LA"));
        order.setAppId(config.appId());
        order.setProductCode(productCode);
        order.setSubject(title);
        order.setTotalAmount(total);
        order.setStatus(WAIT_PAY);
        orderMapper.insert(order);
        try {
            String form = gateway.pagePay(order.getOutTradeNo(), total.toPlainString(), title);
            return new CreatedOrder(order.getOutTradeNo(), form);
        } catch (AlipayApiException e) {
            log.warn("支付宝下单失败 outTradeNo={} code={} msg={}", order.getOutTradeNo(), e.getErrCode(), e.getErrMsg());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "支付宝下单失败：" + safeMsg(e.getErrMsg()));
        }
    }

    /** 当前用户最近的订单，最多 20 笔。 */
    public List<OrderView> listOrders(long userId) {
        List<PayOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getUserId, userId).orderByDesc(PayOrder::getId).last("LIMIT 20"));
        return orders.stream().map(this::view).toList();
    }

    /** 单笔订单详情（只看本地记录，不调支付宝）。 */
    public OrderView getOrder(long userId, String outTradeNo) {
        return view(owned(userId, outTradeNo));
    }

    // ========== 查询 ==========

    /** 主动查询支付宝并同步本地状态；待支付和未知都正常返回，不当成错误。 */
    public QueryView queryOrder(long userId, String outTradeNo) {
        PayOrder order = owned(userId, outTradeNo);
        return syncFromAlipay(order);
    }

    /**
     * 调 alipay.trade.query，按结果更新本地订单：TRADE_SUCCESS / TRADE_FINISHED 且金额一致才标记已支付，
     * TRADE_CLOSED 只关闭本地待支付订单；查不到交易表示用户还没打开收银台，算待支付。
     */
    private QueryView syncFromAlipay(PayOrder order) {
        AlipayGateway.TradeQuery query;
        try {
            query = gateway.query(order.getOutTradeNo());
        } catch (AlipayApiException e) {
            log.warn("支付宝交易查询失败 outTradeNo={} code={}", order.getOutTradeNo(), e.getErrCode());
            return new QueryView("UNKNOWN", "暂时查不到支付结果，请稍后再查", view(reload(order)));
        }
        if (!query.call().success()) {
            if ("ACQ.TRADE_NOT_EXIST".equals(query.call().subCode())) {
                return new QueryView(localState(order), "支付宝还没有这笔交易（还没打开收银台付款）", view(reload(order)));
            }
            return new QueryView("UNKNOWN", "支付宝返回：" + safeMsg(query.call().subMsg()), view(reload(order)));
        }
        String status = query.tradeStatus() == null ? "" : query.tradeStatus();
        switch (status) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> {
                if (!order.getOutTradeNo().equals(query.outTradeNo()) || !amountEquals(order.getTotalAmount(), query.totalAmount())) {
                    log.warn("支付宝查询结果与本地订单不一致 outTradeNo={}", order.getOutTradeNo());
                    return new QueryView("UNKNOWN", "支付宝返回的订单号或金额和本地订单不一致，请人工核对", view(reload(order)));
                }
                transactionTemplate.executeWithoutResult(tx -> settlePaid(order.getOutTradeNo(), query.tradeNo()));
                return new QueryView("PAID", null, view(reload(order)));
            }
            case "WAIT_BUYER_PAY" -> {
                return new QueryView("PENDING_PAYMENT", null, view(reload(order)));
            }
            case "TRADE_CLOSED" -> {
                orderMapper.markClosed(order.getOutTradeNo());
                PayOrder latest = reload(order);
                // 已支付的订单全额退款后支付宝也会显示 TRADE_CLOSED，本地仍保留已支付记录
                return new QueryView(PAID.equals(latest.getStatus()) ? "PAID" : "CLOSED",
                        PAID.equals(latest.getStatus()) ? "交易已全额退款" : null, view(latest));
            }
            default -> {
                return new QueryView("UNKNOWN", "支付宝返回了未识别的交易状态", view(reload(order)));
            }
        }
    }

    // ========== 关单 ==========

    /** 关闭未付款订单：支付宝关单成功后本地标记已关闭。 */
    public OrderView closeOrder(long userId, String outTradeNo) {
        PayOrder order = owned(userId, outTradeNo);
        if (!WAIT_PAY.equals(order.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有待支付的订单可以关闭");
        }
        AlipayGateway.CallResult result;
        try {
            result = gateway.close(outTradeNo);
        } catch (AlipayApiException e) {
            log.warn("支付宝关单失败 outTradeNo={} code={}", outTradeNo, e.getErrCode());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "关单结果未知，请先查询订单状态");
        }
        if (result.success()) {
            orderMapper.markClosed(outTradeNo);
            return view(reload(order));
        }
        if ("ACQ.TRADE_NOT_EXIST".equals(result.subCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "支付宝还没有这笔交易（还没打开收银台），不需要关闭");
        }
        throw new BizException(ErrorCode.EXTERNAL_SERVICE, "关单失败：" + safeMsg(result.subMsg()));
    }

    // ========== 退款 ==========

    /**
     * 发起一次新退款：锁住订单行，校验已支付、累计退款（含结果未确认的）不超过订单金额，
     * 落库一条新的退款请求（新 out_request_no）后再调支付宝。
     */
    private RefundView refund(long userId, String outTradeNo, BigDecimal amount, String reason) {
        BigDecimal refundAmount = checkAmount(amount);
        String cleanReason = StringUtils.hasText(reason) ? reason.trim() : null;
        if (cleanReason != null && cleanReason.length() > 256) {
            throw new BizException(ErrorCode.BAD_REQUEST, "退款原因最多 256 个字");
        }
        PayRefund refund = transactionTemplate.execute(status -> {
            PayOrder order = orderMapper.lockByOutTradeNo(outTradeNo);
            if (order == null || order.getUserId() != userId) {
                throw BizException.notFound("订单");
            }
            if (!PAID.equals(order.getStatus())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "只有已支付的订单可以退款");
            }
            BigDecimal used = refundMapper.sumActive(outTradeNo);
            if (used.add(refundAmount).compareTo(order.getTotalAmount()) > 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "累计退款不能超过订单金额，还可以退 "
                        + order.getTotalAmount().subtract(used).setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " 元");
            }
            PayRefund created = new PayRefund();
            created.setOutTradeNo(outTradeNo);
            created.setOutRequestNo(newNo("RF"));
            created.setRefundAmount(refundAmount);
            created.setReason(cleanReason);
            created.setStatus(REFUND_PENDING);
            refundMapper.insert(created);
            return created;
        });
        return sendRefund(refund);
    }

    /** 重试一笔结果未确认的退款：用原来的 out_request_no、金额和原因，不会重复退。 */
    private RefundView retryRefund(long userId, String outRequestNo) {
        PayRefund refund = ownedRefund(userId, outRequestNo);
        if (!REFUND_PENDING.equals(refund.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有结果待确认的退款可以重试");
        }
        return sendRefund(refund);
    }

    /**
     * 调 alipay.trade.refund：fund_change=Y 记成功；受理了但 fund_change 不是 Y 保持待确认，要用退款查询确认；
     * 支付宝明确拒绝（业务失败且不是系统错误）记失败；网络异常或系统错误保持待确认，可以原样重试。
     */
    private RefundView sendRefund(PayRefund refund) {
        AlipayGateway.RefundResult result;
        try {
            result = gateway.refund(refund.getOutTradeNo(), refund.getRefundAmount().toPlainString(),
                    refund.getReason(), refund.getOutRequestNo());
        } catch (AlipayApiException e) {
            log.warn("支付宝退款调用异常 outRequestNo={} code={}", refund.getOutRequestNo(), e.getErrCode());
            refundMapper.updateStatus(refund.getOutRequestNo(), REFUND_PENDING, "退款结果未知，请稍后查询或重试");
            return refundView(refundMapper.findByOutRequestNo(refund.getOutRequestNo()));
        }
        if (result.call().success()) {
            if ("Y".equals(result.fundChange())) {
                refundMapper.updateStatus(refund.getOutRequestNo(), REFUND_SUCCESS, null);
                revokeCredits(refund);
            } else {
                refundMapper.updateStatus(refund.getOutRequestNo(), REFUND_PENDING, "支付宝已受理，请 10 秒后查询退款结果");
            }
        } else if ("40004".equals(result.call().code()) && !"ACQ.SYSTEM_ERROR".equals(result.call().subCode())) {
            refundMapper.updateStatus(refund.getOutRequestNo(), REFUND_FAILED, safeMsg(result.call().subMsg()));
        } else {
            refundMapper.updateStatus(refund.getOutRequestNo(), REFUND_PENDING, "退款结果未知，请稍后查询或重试");
        }
        return refundView(refundMapper.findByOutRequestNo(refund.getOutRequestNo()));
    }

    /** 积分包退款成功：按退款金额占订单的比例扣回积分（已经用掉的扣成负数）；同一笔退款只扣一次。 */
    private void revokeCredits(PayRefund refund) {
        PayOrder order = orderMapper.findByOutTradeNo(refund.getOutTradeNo());
        int bought = order == null ? 0 : CreditService.creditsOf(order.getProductCode());
        if (bought <= 0 || order.getTotalAmount().signum() <= 0) {
            return;
        }
        int back = refund.getRefundAmount().multiply(BigDecimal.valueOf(bought))
                .divide(order.getTotalAmount(), 0, java.math.RoundingMode.HALF_UP).intValue();
        if (back > 0) {
            credits.revokePurchase(order.getUserId(), back, refund.getOutRequestNo());
        }
    }

    /** 退款查询：只有 refund_status=REFUND_SUCCESS 才记成功，其他情况保持原状态。 */
    private RefundView queryRefund(long userId, String outRequestNo) {
        PayRefund refund = ownedRefund(userId, outRequestNo);
        if (REFUND_SUCCESS.equals(refund.getStatus())) {
            return refundView(refund);
        }
        AlipayGateway.RefundQuery query;
        try {
            query = gateway.refundQuery(refund.getOutTradeNo(), outRequestNo);
        } catch (AlipayApiException e) {
            log.warn("支付宝退款查询失败 outRequestNo={} code={}", outRequestNo, e.getErrCode());
            throw new BizException(ErrorCode.EXTERNAL_SERVICE, "暂时查不到退款结果，请稍后再查");
        }
        if (query.call().success() && "REFUND_SUCCESS".equals(query.refundStatus())) {
            refundMapper.updateStatus(outRequestNo, REFUND_SUCCESS, null);
            revokeCredits(refund);
        } else if (REFUND_PENDING.equals(refund.getStatus())) {
            String message = query.call().success() ? "支付宝还没确认退款成功，请稍后再查"
                    : "支付宝返回：" + safeMsg(query.call().subMsg());
            refundMapper.updateStatus(outRequestNo, REFUND_PENDING, message);
        }
        return refundView(refundMapper.findByOutRequestNo(outRequestNo));
    }

    // ========== 异步通知 ==========

    /**
     * 处理支付宝异步通知，返回 true 时回 success：验签 → 用通知里的 out_trade_no 找本地订单 →
     * 校验 app_id、金额、seller_id（预期值取运行时配置）→ 按 notify_id 幂等记录并更新订单，两步在同一事务里。
     * 只有 TRADE_SUCCESS / TRADE_FINISHED 且不是退款类通知才标记已支付。
     */
    public boolean handleNotify(Map<String, String> params) {
        AlipayAppConfig config = gateway.config();
        if (!config.appId().equals(params.get("app_id"))) {
            log.warn("支付宝通知 app_id 不是当前应用 {}", sanitize(params));
            return false;
        }
        if (!gateway.verify(params)) {
            log.warn("支付宝通知验签失败 {}", sanitize(params));
            return false;
        }
        String outTradeNo = params.get("out_trade_no");
        PayOrder order = StringUtils.hasText(outTradeNo) ? orderMapper.findByOutTradeNo(outTradeNo) : null;
        if (order == null || !businessFieldsMatch(order, params, config)) {
            log.warn("支付宝通知业务字段校验没通过 {}", sanitize(params));
            return false;
        }
        String notifyId = params.get("notify_id");
        if (!StringUtils.hasText(notifyId)) {
            log.warn("支付宝通知缺少 notify_id {}", sanitize(params));
            return false;
        }
        String json = toJson(sanitize(params));
        Boolean handled = transactionTemplate.execute(status -> {
            int inserted = notifyEventMapper.insertOnce(notifyId, outTradeNo, params.get("trade_no"),
                    params.get("trade_status"), json);
            if (inserted == 0) {
                return true;
            }
            if (isPaidNotification(params)) {
                settlePaid(outTradeNo, params.get("trade_no"));
            } else if ("TRADE_CLOSED".equals(params.get("trade_status"))) {
                orderMapper.markClosed(outTradeNo);
            }
            return true;
        });
        return Boolean.TRUE.equals(handled);
    }

    /** 通知里的 app_id、out_trade_no、total_amount、seller_id 都要和本地订单、运行时配置一致。 */
    private static boolean businessFieldsMatch(PayOrder order, Map<String, String> params, AlipayAppConfig config) {
        return config.appId().equals(params.get("app_id"))
                && config.appId().equals(order.getAppId())
                && order.getOutTradeNo().equals(params.get("out_trade_no"))
                && amountEquals(order.getTotalAmount(), params.get("total_amount"))
                && config.sellerId().equals(params.get("seller_id"));
    }

    /** 付款成功通知：状态是 TRADE_SUCCESS / TRADE_FINISHED，并且不带退款类字段。 */
    private static boolean isPaidNotification(Map<String, String> params) {
        String status = params.get("trade_status");
        boolean paid = "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status);
        return paid && !StringUtils.hasText(params.get("out_biz_no"))
                && !StringUtils.hasText(params.get("gmt_refund"))
                && !StringUtils.hasText(params.get("refund_fee"));
    }

    // ========== 同步回跳 ==========

    /**
     * 确认同步回跳：参数必须验签通过、app_id 是当前应用、订单存在且金额一致，然后主动查询支付宝得到最终状态。
     * 没有参数时返回 NONE，页面展示中性提示。
     */
    public ReturnView confirmReturn(Map<String, String> params) {
        if (!StringUtils.hasText(params.get("sign")) || !StringUtils.hasText(params.get("out_trade_no"))) {
            return new ReturnView("NONE", "没有需要确认的订单", null, null, null);
        }
        AlipayAppConfig config = gateway.config();
        String appId = StringUtils.hasText(params.get("app_id")) ? params.get("app_id") : params.get("auth_app_id");
        if (!config.appId().equals(appId) || !gateway.verify(params)) {
            return new ReturnView("INVALID", "回跳参数校验没通过，请到订单列表查看结果", null, null, null);
        }
        PayOrder order = orderMapper.findByOutTradeNo(params.get("out_trade_no"));
        if (order == null || !amountEquals(order.getTotalAmount(), params.get("total_amount"))) {
            return new ReturnView("INVALID", "找不到对应的订单，请到订单列表查看结果", null, null, null);
        }
        QueryView result = syncFromAlipay(order);
        return new ReturnView(result.state(), result.message(), order.getOutTradeNo(), order.getSubject(),
                order.getTotalAmount().toPlainString());
    }

    /**
     * 标记已支付并发放商品权益，必须在事务里调用：只有这次真的把订单从未支付改成已支付才发放，
     * 通知和主动查询同时到达、通知重复推送时都只发一次。
     */
    private void settlePaid(String outTradeNo, String tradeNo) {
        if (orderMapper.markPaid(outTradeNo, tradeNo) == 0) {
            return;
        }
        PayOrder order = orderMapper.findByOutTradeNo(outTradeNo);
        if (MembershipService.MEMBER_MONTH.equals(order.getProductCode())) {
            membership.extendByOrder(order.getUserId(), MembershipService.MONTH_DAYS, outTradeNo);
            log.info("会员已开通 userId={} outTradeNo={}", order.getUserId(), outTradeNo);
        }
        int bought = CreditService.creditsOf(order.getProductCode());
        if (bought > 0) {
            credits.grantPurchase(order.getUserId(), bought, outTradeNo);
        }
    }

    // ========== 后台 ==========

    /** 后台看最近的订单，最多 limit 笔。 */
    public List<AdminOrderView> recentOrders(int limit) {
        List<PayOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<PayOrder>()
                .orderByDesc(PayOrder::getId).last("LIMIT " + Math.max(1, Math.min(limit, 200))));
        return orders.stream().map(o -> new AdminOrderView(o.getUserId(), view(o))).toList();
    }

    /** 后台主动查询某笔订单。 */
    public QueryView adminQuery(String outTradeNo) {
        PayOrder order = existing(outTradeNo);
        return syncFromAlipay(order);
    }

    /** 后台给某笔已支付订单退款（会员时间不会自动扣，需要的话在后台另外调整）。 */
    public RefundView adminRefund(String outTradeNo, BigDecimal amount, String reason) {
        return refund(existing(outTradeNo).getUserId(), outTradeNo, amount, reason);
    }

    /** 后台重试结果未确认的退款。 */
    public RefundView adminRetryRefund(String outRequestNo) {
        return retryRefund(refundOwner(outRequestNo), outRequestNo);
    }

    /** 后台查询退款结果。 */
    public RefundView adminQueryRefund(String outRequestNo) {
        return queryRefund(refundOwner(outRequestNo), outRequestNo);
    }

    /** 按订单号找订单，找不到报不存在。 */
    private PayOrder existing(String outTradeNo) {
        PayOrder order = StringUtils.hasText(outTradeNo) ? orderMapper.findByOutTradeNo(outTradeNo) : null;
        if (order == null) {
            throw BizException.notFound("订单");
        }
        return order;
    }

    /** 退款请求所属订单的下单用户。 */
    private long refundOwner(String outRequestNo) {
        PayRefund refund = StringUtils.hasText(outRequestNo) ? refundMapper.findByOutRequestNo(outRequestNo) : null;
        if (refund == null) {
            throw BizException.notFound("退款");
        }
        return existing(refund.getOutTradeNo()).getUserId();
    }

    // ========== 工具方法 ==========

    /** 当前用户自己的订单，别人的按不存在处理。 */
    private PayOrder owned(long userId, String outTradeNo) {
        PayOrder order = StringUtils.hasText(outTradeNo) ? orderMapper.findByOutTradeNo(outTradeNo) : null;
        if (order == null || order.getUserId() != userId) {
            throw BizException.notFound("订单");
        }
        return order;
    }

    /** 当前用户自己订单下的退款请求。 */
    private PayRefund ownedRefund(long userId, String outRequestNo) {
        PayRefund refund = StringUtils.hasText(outRequestNo) ? refundMapper.findByOutRequestNo(outRequestNo) : null;
        if (refund == null) {
            throw BizException.notFound("退款");
        }
        owned(userId, refund.getOutTradeNo());
        return refund;
    }

    /** 重新读一次订单（状态可能刚被更新）。 */
    private PayOrder reload(PayOrder order) {
        return orderMapper.findByOutTradeNo(order.getOutTradeNo());
    }

    /** 本地状态对应的查询状态：待支付 → PENDING_PAYMENT。 */
    private static String localState(PayOrder order) {
        return switch (order.getStatus()) {
            case PAID -> "PAID";
            case CLOSED -> "CLOSED";
            default -> "PENDING_PAYMENT";
        };
    }

    /** 订单转成前端视图，带退款列表和已成功退款合计。 */
    private OrderView view(PayOrder order) {
        List<RefundView> refunds = refundMapper.listByOrder(order.getOutTradeNo()).stream().map(AlipayPayService::refundView).toList();
        BigDecimal refunded = refundMapper.sumSucceeded(order.getOutTradeNo()).setScale(2, RoundingMode.UNNECESSARY);
        return new OrderView(order.getOutTradeNo(), order.getSubject(), order.getTotalAmount().toPlainString(),
                order.getStatus(), order.getTradeNo(), order.getPaidAt(), order.getCreatedAt(), refunded.toPlainString(), refunds);
    }

    /** 退款请求转成前端视图。 */
    private static RefundView refundView(PayRefund refund) {
        return new RefundView(refund.getOutRequestNo(), refund.getOutTradeNo(), refund.getRefundAmount().toPlainString(),
                refund.getStatus(), refund.getMessage(), refund.getCreatedAt());
    }

    /** 金额校验：0.01 到 1 亿元，最多两位小数，统一成两位小数。 */
    private static BigDecimal checkAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额不能为空");
        }
        BigDecimal value = amount.stripTrailingZeros();
        if (value.scale() > 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额最多两位小数");
        }
        if (value.compareTo(MIN_AMOUNT) < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "金额要在 0.01 到 100000000 元之间");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** 订单标题：去掉控制字符和首尾空白，不能为空，最多 256 个字。 */
    private static String cleanSubject(String subject) {
        String text = subject == null ? "" : subject.replaceAll("\\p{Cntrl}", "").trim();
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单标题不能为空");
        }
        if (text.length() > 256) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单标题最多 256 个字");
        }
        return text;
    }

    /** 金额按数值比较（10.0 和 10.00 相等），格式不对算不一致。 */
    private static boolean amountEquals(BigDecimal expected, String actual) {
        if (expected == null || !StringUtils.hasText(actual)) {
            return false;
        }
        try {
            return expected.compareTo(new BigDecimal(actual.trim())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 生成订单号、退款请求号：前缀 + 北京时间到毫秒 + 6 位随机数，只含字母和数字。 */
    private static String newNo(String prefix) {
        return prefix + LocalDateTime.now(ZONE).format(NO_TIME) + String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** 去掉签名后的通知参数，用于日志和幂等表。 */
    private static Map<String, String> sanitize(Map<String, String> params) {
        Map<String, String> safe = new HashMap<>(params);
        safe.remove("sign");
        return safe;
    }

    /** 参数转 JSON 字符串存进 jsonb 字段。 */
    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("通知参数转 JSON 失败", e);
        }
    }

    /** 支付宝返回的说明为空时给个兜底文案，并限制长度。 */
    private static String safeMsg(String message) {
        if (!StringUtils.hasText(message)) {
            return "未知错误";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
