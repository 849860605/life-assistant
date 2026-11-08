package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.member.CreditService;
import com.lifeassistant.core.member.MembershipService;
import com.lifeassistant.skill.travel.flight.FlightStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 航班实时动态（需要登录）：查一次扣积分（老会员到期前不扣），5 分钟内重复查走缓存、不扣。 */
@RestController
@RequestMapping("/api/v1/flights/status")
@RequiredArgsConstructor
public class FlightStatusController {

    private final AuthFacade authFacade;
    private final FlightStatusService statusService;
    private final CreditService credits;
    private final MembershipService membership;

    /** 能不能查、查一次多少积分、是不是会员（会员不扣）。 */
    public record Info(boolean available, int price, boolean member) {
    }

    /** GET /api/v1/flights/status?no=CZ3101&date=2026-11-08：查动态，date 不填是今天。 */
    @GetMapping
    public ApiResponse<FlightStatusService.Result> query(@RequestParam String no,
                                                         @RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(statusService.query(authFacade.current().userId(), no, date));
    }

    /** GET /api/v1/flights/status/info：页面上显示"查一次 N 积分"。 */
    @GetMapping("/info")
    public ApiResponse<Info> info() {
        long userId = authFacade.current().userId();
        return ApiResponse.ok(new Info(statusService.available(), credits.price(CreditService.FLIGHT),
                membership.status(userId).active()));
    }
}
