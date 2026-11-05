package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.member.ChatQuota;
import com.lifeassistant.core.member.MembershipService;
import com.lifeassistant.core.pay.AlipayPayService;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.core.user.User;
import com.lifeassistant.core.user.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理后台的会员与账单：账单概况、订单（查单、退款）、会员变动记录，改会员价格和每天的对话次数，
 * 给某个用户加减会员天数。退款、改价和改次数只有超级管理员能做，都记操作日志。
 */
@RestController
@RequestMapping("/admin/api/v1/pay")
@RequiredArgsConstructor
public class AdminPayController {

    private final AlipayPayService payService;
    private final MembershipService membership;
    private final ChatQuota chatQuota;
    private final UserMapper userMapper;
    private final AdminContext adminContext;

    /** 订单列表的一行：下单用户的用户名、昵称加订单。 */
    public record OrderRow(long userId, String username, String nickname, AlipayPayService.OrderView order) {
    }

    /** 会员设置：月度价格、一次多少天、支付宝收款配置好了没有，免费用户和会员每天能对话多少次。 */
    public record MemberSetting(String price, int days, boolean payReady, int freeDaily, int memberDaily) {
    }

    /** 改每天对话次数的请求体。 */
    public record QuotaRequest(int freeDaily, int memberDaily) {
    }

    /** 会员变动记录的一行：用户名、昵称加记录。 */
    public record LogRow(String username, String nickname, MembershipService.LogEntry log) {
    }

    /** 改价请求体。 */
    public record PriceRequest(@NotNull BigDecimal price) {
    }

    /** 退款请求体：amount 本次退款金额（元），reason 可不填。 */
    public record RefundRequest(@NotNull BigDecimal amount, String reason) {
    }

    /** 加减会员天数的请求体：days 为负数时往回扣，note 是原因（比如"送朋友"、"退款扣回"）。 */
    public record GrantRequest(int days, String note) {
    }

    /** 某个用户的会员状态、今天的对话次数和会员记录。 */
    public record MemberStatus(boolean active, OffsetDateTime expiresAt, int todayUsed, int dailyLimit,
                               List<MembershipService.LogEntry> logs) {
    }

    /** GET /admin/api/v1/pay/orders?limit=100：最近的订单。 */
    @GetMapping("/orders")
    public ApiResponse<List<OrderRow>> orders(@RequestParam(defaultValue = "100") int limit) {
        adminContext.current();
        List<AlipayPayService.AdminOrderView> orders = payService.recentOrders(limit);
        Map<Long, User> users = usersOf(orders.stream().map(AlipayPayService.AdminOrderView::userId).distinct().toList());
        return ApiResponse.ok(orders.stream().map(o -> {
            User u = users.get(o.userId());
            return new OrderRow(o.userId(), u == null ? null : u.getUsername(), u == null ? null : u.getNickname(), o.order());
        }).toList());
    }

    /** POST /admin/api/v1/pay/orders/{outTradeNo}/query：向支付宝查询并同步订单状态。 */
    @PostMapping("/orders/{outTradeNo}/query")
    public ApiResponse<AlipayPayService.QueryView> query(@PathVariable String outTradeNo) {
        adminContext.current();
        return ApiResponse.ok(payService.adminQuery(outTradeNo));
    }

    /** POST /admin/api/v1/pay/orders/{outTradeNo}/refunds：退款（可部分退），会员时间不会自动扣。 */
    @PostMapping("/orders/{outTradeNo}/refunds")
    public ApiResponse<AlipayPayService.RefundView> refund(@PathVariable String outTradeNo,
                                                           @Valid @RequestBody RefundRequest request) {
        AdminUser me = adminContext.requireSuper();
        AlipayPayService.RefundView view = payService.adminRefund(outTradeNo, request.amount(), request.reason());
        adminContext.log(me.getId(), "REFUND_ORDER", outTradeNo + " ¥" + view.refundAmount() + " " + view.status());
        return ApiResponse.ok(view);
    }

    /** POST /admin/api/v1/pay/refunds/{outRequestNo}/retry：结果未确认的退款用原请求号原样重试。 */
    @PostMapping("/refunds/{outRequestNo}/retry")
    public ApiResponse<AlipayPayService.RefundView> retryRefund(@PathVariable String outRequestNo) {
        AdminUser me = adminContext.requireSuper();
        AlipayPayService.RefundView view = payService.adminRetryRefund(outRequestNo);
        adminContext.log(me.getId(), "RETRY_REFUND", outRequestNo + " " + view.status());
        return ApiResponse.ok(view);
    }

    /** POST /admin/api/v1/pay/refunds/{outRequestNo}/query：向支付宝查询退款结果。 */
    @PostMapping("/refunds/{outRequestNo}/query")
    public ApiResponse<AlipayPayService.RefundView> queryRefund(@PathVariable String outRequestNo) {
        adminContext.current();
        return ApiResponse.ok(payService.adminQueryRefund(outRequestNo));
    }

    /** GET /admin/api/v1/pay/member：会员价格等设置。 */
    @GetMapping("/member")
    public ApiResponse<MemberSetting> memberSetting() {
        adminContext.current();
        return ApiResponse.ok(setting());
    }

    /** PUT /admin/api/v1/pay/member/price：改月度会员价格，之后的新订单按新价格。 */
    @PutMapping("/member/price")
    public ApiResponse<MemberSetting> setPrice(@Valid @RequestBody PriceRequest request) {
        AdminUser me = adminContext.requireSuper();
        BigDecimal price = membership.setMonthPrice(request.price());
        adminContext.log(me.getId(), "SET_MEMBER_PRICE", "¥" + price.toPlainString());
        return ApiResponse.ok(setting());
    }

    /** GET /admin/api/v1/pay/stats：本月实收、本月付费订单、有效会员数、累计实收。 */
    @GetMapping("/stats")
    public ApiResponse<MembershipService.Stats> stats() {
        adminContext.current();
        return ApiResponse.ok(membership.stats());
    }

    /** GET /admin/api/v1/pay/member-logs?limit=100：所有人最近的会员变动记录。 */
    @GetMapping("/member-logs")
    public ApiResponse<List<LogRow>> memberLogs(@RequestParam(defaultValue = "100") int limit) {
        adminContext.current();
        List<MembershipService.LogEntry> logs = membership.recentLogs(limit);
        Map<Long, User> users = usersOf(logs.stream().map(MembershipService.LogEntry::userId).distinct().toList());
        return ApiResponse.ok(logs.stream().map(l -> {
            User u = users.get(l.userId());
            return new LogRow(u == null ? null : u.getUsername(), u == null ? null : u.getNickname(), l);
        }).toList());
    }

    /** PUT /admin/api/v1/pay/member/quota：改免费用户和会员每天能对话多少次，马上生效。 */
    @PutMapping("/member/quota")
    public ApiResponse<MemberSetting> setQuota(@RequestBody QuotaRequest request) {
        AdminUser me = adminContext.requireSuper();
        MembershipService.Quota q = membership.setQuota(request.freeDaily(), request.memberDaily());
        adminContext.log(me.getId(), "SET_CHAT_QUOTA", "免费 " + q.freeDaily() + " 次/天，会员 " + q.memberDaily() + " 次/天");
        return ApiResponse.ok(setting());
    }

    /** GET /admin/api/v1/pay/members/{userId}：某个用户的会员状态、今天的对话次数和会员记录。 */
    @GetMapping("/members/{userId}")
    public ApiResponse<MemberStatus> memberStatus(@PathVariable long userId) {
        adminContext.current();
        return ApiResponse.ok(memberStatusOf(userId));
    }

    /** POST /admin/api/v1/pay/members/{userId}/grant：给用户加（或扣）会员天数，比如送朋友、退款后扣回。 */
    @PostMapping("/members/{userId}/grant")
    public ApiResponse<MemberStatus> grant(@PathVariable long userId, @RequestBody GrantRequest request) {
        AdminUser me = adminContext.current();
        if (request.days() == 0 || Math.abs(request.days()) > 3660) {
            throw new BizException(ErrorCode.BAD_REQUEST, "天数要在 -3660 到 3660 之间，不能是 0");
        }
        if (userMapper.selectById(userId) == null) {
            throw BizException.notFound("用户");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 200) {
            throw new BizException(ErrorCode.BAD_REQUEST, "备注最多 200 个字");
        }
        membership.extendByAdmin(userId, request.days(), me.getId(), note);
        adminContext.log(me.getId(), "GRANT_MEMBER", "用户 " + userId + (request.days() > 0 ? " +" : " ") + request.days() + " 天"
                + (note == null ? "" : "（" + note + "）"));
        return ApiResponse.ok(memberStatusOf(userId));
    }

    /** 某个用户的会员状态、今天的对话次数和最近 50 条会员记录。 */
    private MemberStatus memberStatusOf(long userId) {
        MembershipService.Status s = membership.status(userId);
        ChatQuota.Usage usage = chatQuota.usage(userId);
        return new MemberStatus(s.active(), s.expiresAt(), usage.used(), usage.limit(), membership.logs(userId, 50));
    }

    /** 按 id 批量取用户。 */
    private Map<Long, User> usersOf(List<Long> ids) {
        return ids.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** 当前的会员设置。 */
    private MemberSetting setting() {
        MembershipService.Quota q = membership.quota();
        return new MemberSetting(membership.monthPrice().toPlainString(), MembershipService.MONTH_DAYS, payService.ready(),
                q.freeDaily(), q.memberDaily());
    }
}
