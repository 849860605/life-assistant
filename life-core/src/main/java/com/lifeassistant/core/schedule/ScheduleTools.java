package com.lifeassistant.core.schedule;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.auth.ToolContexts;
import com.lifeassistant.core.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** 日程工具，日程技能和出游技能（行程写进日程）共用。 */
@Component
@RequiredArgsConstructor
public class ScheduleTools {

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ScheduleService scheduleService;
    private final UserService userService;

    /** 时间按用户时区解析；end 为空或空白视为未知。 */
    @Tool(description = "新建一条日程，可以顺便设置提前多少分钟推送提醒到用户手机。")
    public Map<String, Object> createEvent(
            @ToolParam(description = "标题，例如：爬泰山、和老王吃饭") String title,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm，用户所在时区") String start,
            @ToolParam(description = "结束时间，格式 yyyy-MM-dd HH:mm，不知道就不填", required = false) String end,
            @ToolParam(description = "地点", required = false) String location,
            @ToolParam(description = "提前多少分钟提醒；不需要提醒就不填", required = false) Integer remindMinutesBefore,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        ZoneId zone = userService.zoneOf(ctx.userId());
        ScheduleEvent event = scheduleService.createEvent(ctx, new ScheduleService.NewEvent(
                title, parse(start, zone), end == null || end.isBlank() ? null : parse(end, zone),
                location, null, "USER", null, remindMinutesBefore));
        return Map.of("eventId", event.getId(), "title", event.getTitle(), "start", start,
                "remind", remindMinutesBefore == null ? "不提醒" : "提前 " + remindMinutesBefore + " 分钟");
    }

    /** 两端日期都包含，按用户时区换算成 [fromDate 0 点, toDate 次日 0 点)。 */
    @Tool(description = "查询某个日期范围内的日程。")
    public List<Map<String, Object>> listEvents(
            @ToolParam(description = "开始日期 yyyy-MM-dd（包含）") String fromDate,
            @ToolParam(description = "结束日期 yyyy-MM-dd（包含）") String toDate,
            ToolContext toolContext) {
        UserCtx ctx = ToolContexts.user(toolContext);
        ZoneId zone = userService.zoneOf(ctx.userId());
        OffsetDateTime from = LocalDate.parse(fromDate).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = LocalDate.parse(toDate).plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return scheduleService.list(ctx, from, to).stream()
                .map(e -> Map.<String, Object>of(
                        "eventId", e.getId(),
                        "title", e.getTitle(),
                        "start", e.getStartAt().atZoneSameInstant(zone).format(INPUT),
                        "location", e.getLocation() == null ? "" : e.getLocation()))
                .toList();
    }

    /** 只能删当前用户的日程；"先确认"只靠工具说明约束模型，代码层面没有拦截。 */
    @Tool(description = "删除一条日程。删除前先向用户确认是哪一条。")
    public String deleteEvent(@ToolParam(description = "日程 id") long eventId, ToolContext toolContext) {
        scheduleService.delete(ToolContexts.user(toolContext), eventId);
        return "已删除";
    }

    /** 把 yyyy-MM-dd HH:mm 按给定时区解析成带偏移的时间。 */
    private static OffsetDateTime parse(String text, ZoneId zone) {
        return LocalDateTime.parse(text.trim(), INPUT).atZone(zone).toOffsetDateTime();
    }
}
