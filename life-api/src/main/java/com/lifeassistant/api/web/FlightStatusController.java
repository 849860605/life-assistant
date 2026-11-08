package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.member.FeatureTrial;
import com.lifeassistant.skill.travel.flight.FlightStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 航班实时动态（需要登录）：免费用户总共能试查几次，会员不限；5 分钟内重复查走缓存、不扣次数。 */
@RestController
@RequestMapping("/api/v1/flights/status")
@RequiredArgsConstructor
public class FlightStatusController {

    private final AuthFacade authFacade;
    private final FlightStatusService statusService;
    private final FeatureTrial trial;

    /** 能不能查、是不是会员、还剩几次免费试查。 */
    public record TrialView(boolean available, boolean member, int total, int left) {
    }

    /** GET /api/v1/flights/status?no=CZ3101&date=2026-11-08：查动态，date 不填是今天。 */
    @GetMapping
    public ApiResponse<FlightStatusService.Result> query(@RequestParam String no,
                                                         @RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(statusService.query(authFacade.current().userId(), no, date));
    }

    /** GET /api/v1/flights/status/trial：试查次数（页面上显示"还可以免费查 N 次"）。 */
    @GetMapping("/trial")
    public ApiResponse<TrialView> trial() {
        FeatureTrial.Usage u = trial.usage(authFacade.current().userId(), FeatureTrial.FLIGHT_STATUS);
        return ApiResponse.ok(new TrialView(statusService.available(), u.member(), u.total(), u.left()));
    }
}
