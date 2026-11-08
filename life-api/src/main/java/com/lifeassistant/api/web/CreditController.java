package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.member.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 我的积分（需要登录）：余额、今天送的还剩多少、价格表和积分包，积分明细。买积分走 /api/v1/pay/alipay/orders。 */
@RestController
@RequestMapping("/api/v1/credits")
@RequiredArgsConstructor
public class CreditController {

    private final AuthFacade authFacade;
    private final CreditService credits;

    /** 积分情况加上各项的中文名。 */
    public record CreditView(CreditService.View view, Map<String, String> features) {
    }

    /** GET /api/v1/credits：我的积分、价格表、积分包（第一次调用时开户并送注册积分）。 */
    @GetMapping
    public ApiResponse<CreditView> view() {
        return ApiResponse.ok(new CreditView(credits.view(authFacade.current().userId()), CreditService.FEATURES));
    }

    /** GET /api/v1/credits/ledger?limit=50：我的积分明细，新的在前。 */
    @GetMapping("/ledger")
    public ApiResponse<List<CreditService.Entry>> ledger(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(credits.ledger(authFacade.current().userId(), limit));
    }
}
