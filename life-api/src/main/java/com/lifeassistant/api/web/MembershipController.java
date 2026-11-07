package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.member.ChatQuota;
import com.lifeassistant.core.member.MembershipService;
import com.lifeassistant.core.pay.AlipayPayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/** 星络会员：我的会员状态、今天的对话次数、月度会员价格和我的会员记录。 */
@RestController
@RequestMapping("/api/v1/membership")
@RequiredArgsConstructor
public class MembershipController {

    private final AuthFacade authFacade;
    private final MembershipService membership;
    private final ChatQuota chatQuota;
    private final AlipayPayService payService;

    /**
     * 会员页要的信息：是不是会员、到期时间、每月价格、一次多少天、现在能不能付款，
     * 今天用了几次、今天的上限，以及免费用户和会员每天各多少次（页面上对比用）。
     */
    public record MembershipView(boolean active, OffsetDateTime expiresAt, String price, int days, boolean payReady,
                                 int todayUsed, int dailyLimit, int freeDaily, int memberDaily) {
    }

    /**
     * 我的一条会员记录：source 为 PAY 付费开通 / ADMIN 星络赠送或调整，days 为负是扣减；
     * outTradeNo 是付费记录对应的订单号，expiresBefore 是这次变动前的到期时间（没开过或已过期时是开通，否则是续费）。
     */
    public record LogView(int days, String source, String outTradeNo, OffsetDateTime expiresBefore,
                          OffsetDateTime expiresAfter, OffsetDateTime createdAt) {
    }

    /** GET /api/v1/membership */
    @GetMapping
    public ApiResponse<MembershipView> get() {
        long userId = authFacade.current().userId();
        MembershipService.Status status = membership.status(userId);
        ChatQuota.Usage usage = chatQuota.usage(userId);
        MembershipService.Quota quota = membership.quota();
        return ApiResponse.ok(new MembershipView(status.active(), status.expiresAt(),
                membership.monthPrice().toPlainString(), MembershipService.MONTH_DAYS, payService.ready(),
                usage.used(), usage.limit(), quota.freeDaily(), quota.memberDaily()));
    }

    /** GET /api/v1/membership/logs：我最近的会员记录（不含后台备注和管理员）。 */
    @GetMapping("/logs")
    public ApiResponse<List<LogView>> logs() {
        return ApiResponse.ok(membership.logs(authFacade.current().userId(), 50).stream()
                .map(l -> new LogView(l.days(), l.source(), l.outTradeNo(), l.expiresBefore(), l.expiresAfter(),
                        l.createdAt())).toList());
    }
}
