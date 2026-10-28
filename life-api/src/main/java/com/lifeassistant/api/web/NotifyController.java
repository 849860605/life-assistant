package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.notify.Notice;
import com.lifeassistant.core.notify.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推送设置接口：配置 Bark 并发送测试推送。
 */
@RestController
@RequestMapping("/api/v1/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final AuthFacade authFacade;
    private final NotificationService notificationService;

    /** Bark 配置请求体，deviceKey 会去掉首尾空格后保存。 */
    public record BarkRequest(@NotBlank String deviceKey) {
    }

    /** PUT /api/v1/notify/bark：设置 Bark 设备 key（Bark App 首页的那串 key），已有则覆盖。 */
    @PutMapping("/bark")
    public ApiResponse<Void> configureBark(@Valid @RequestBody BarkRequest request) {
        notificationService.configureBark(authFacade.current().userId(), request.deviceKey());
        return ApiResponse.ok();
    }

    /** POST /api/v1/notify/test：发一条测试推送，确认手机能收到。 */
    @PostMapping("/test")
    public ApiResponse<Void> test() {
        notificationService.send(authFacade.current().userId(),
                new Notice("生活助手", "推送设置成功", "系统", null, "active"), null);
        return ApiResponse.ok();
    }
}
