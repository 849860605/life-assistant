package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.schedule.IcsExporter;
import com.lifeassistant.core.schedule.ScheduleEvent;
import com.lifeassistant.core.schedule.ScheduleService;
import com.lifeassistant.core.token.ApiKeyScope;
import com.lifeassistant.core.token.ApiKeyService;
import com.lifeassistant.core.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 日程接口：日程的增删查，以及供手机日历订阅的 ICS 地址。
 */
@RestController
@RequiredArgsConstructor
public class ScheduleController {

    private final AuthFacade authFacade;
    private final ScheduleService scheduleService;
    private final UserService userService;
    private final ApiKeyService apiKeyService;
    private final IcsExporter icsExporter;

    /** 手动新建日程的请求体；remindMinutesBefore 为提前多少分钟提醒，为空则不提醒。 */
    public record CreateEventRequest(@NotBlank String title, @NotNull OffsetDateTime startAt, OffsetDateTime endAt,
                                     String location, String description, Integer remindMinutesBefore) {
    }

    /** GET /api/v1/events：按用户时区查 [from, to] 两个日期（含 to 当天）内开始的日程。 */
    @GetMapping("/api/v1/events")
    public ApiResponse<List<ScheduleEvent>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UserCtx ctx = authFacade.current();
        ZoneId zone = userService.zoneOf(ctx.userId());
        return ApiResponse.ok(scheduleService.list(ctx, from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
    }

    /** POST /api/v1/events：手动新建日程，来源记为 USER。 */
    @PostMapping("/api/v1/events")
    public ApiResponse<ScheduleEvent> create(@Valid @RequestBody CreateEventRequest request) {
        return ApiResponse.ok(scheduleService.createEvent(authFacade.current(), new ScheduleService.NewEvent(
                request.title(), request.startAt(), request.endAt(), request.location(), request.description(),
                "USER", null, request.remindMinutesBefore())));
    }

    /** DELETE /api/v1/events/{id}：删除自己的日程，不存在时抛 NOT_FOUND。 */
    @DeleteMapping("/api/v1/events/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        scheduleService.delete(authFacade.current(), id);
        return ApiResponse.ok();
    }

    /**
     * GET /api/v1/calendar/feed/{token}.ics：日历订阅，iPhone“设置 → 日历 → 账户 → 添加已订阅的日历”填这个地址。
     * 用 CALENDAR_FEED 用途的个人访问令牌鉴权，不走登录；导出过去 30 天到未来约一年的日程。
     */
    @GetMapping("/api/v1/calendar/feed/{token}.ics")
    public ResponseEntity<String> feed(@PathVariable String token) {
        long userId = apiKeyService.verify(token, ApiKeyScope.CALENDAR_FEED)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "订阅链接无效或已吊销"));
        OffsetDateTime from = OffsetDateTime.now().minusDays(30);
        String ics = icsExporter.export(scheduleService.list(UserCtx.of(userId), from, from.plusDays(400)),
                userService.zoneOf(userId));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8")).body(ics);
    }
}
