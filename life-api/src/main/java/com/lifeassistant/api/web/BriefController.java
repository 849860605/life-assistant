package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.brief.Brief;
import com.lifeassistant.core.brief.BriefService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日简报接口：看今天的简报、改推送设置、立即推一条试试。
 */
@RestController
@RequestMapping("/api/v1/brief")
@RequiredArgsConstructor
public class BriefController {

    private final AuthFacade authFacade;
    private final BriefService briefService;

    /** 修改设置的请求体：sendTime 为 HH:mm，city 留空用记忆里的常住城市。 */
    public record SettingsRequest(boolean enabled, String sendTime, String city) {
    }

    /** GET /api/v1/brief：今天的简报（不推送）。 */
    @GetMapping
    public ApiResponse<Brief> today() {
        return ApiResponse.ok(briefService.build(authFacade.current().userId()));
    }

    /** GET /api/v1/brief/settings：推送设置。 */
    @GetMapping("/settings")
    public ApiResponse<BriefService.Settings> settings() {
        return ApiResponse.ok(briefService.settings(authFacade.current().userId()));
    }

    /** PUT /api/v1/brief/settings：开关、推送时间、城市。 */
    @PutMapping("/settings")
    public ApiResponse<BriefService.Settings> update(@RequestBody SettingsRequest request) {
        return ApiResponse.ok(briefService.update(authFacade.current().userId(), request.enabled(), request.sendTime(),
                request.city()));
    }

    /** POST /api/v1/brief/push：立即推一条今天的简报，没设推送渠道时报错。 */
    @PostMapping("/push")
    public ApiResponse<Brief> push() {
        return ApiResponse.ok(briefService.push(authFacade.current().userId()));
    }
}
