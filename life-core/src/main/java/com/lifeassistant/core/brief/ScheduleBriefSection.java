package com.lifeassistant.core.brief;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.schedule.ScheduleEvent;
import com.lifeassistant.core.schedule.ScheduleService;
import com.lifeassistant.core.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 简报的日程段：今天的日程（最多 6 条），再加明天上午 10 点前的，免得早上的事没准备。 */
@Component
@RequiredArgsConstructor
public class ScheduleBriefSection implements BriefSection {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_TODAY = 6;

    private final ScheduleService scheduleService;
    private final UserService userService;

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Optional<Brief.Section> build(Brief.Context context) {
        ZoneId zone = userService.zoneOf(context.userId());
        UserCtx ctx = UserCtx.of(context.userId());
        List<ScheduleEvent> today = scheduleService.list(ctx, context.today().atStartOfDay(zone).toOffsetDateTime(),
                context.today().plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        List<ScheduleEvent> early = scheduleService.list(ctx, context.today().plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                context.today().plusDays(1).atTime(LocalTime.of(10, 0)).atZone(zone).toOffsetDateTime());
        List<String> lines = new ArrayList<>();
        today.stream().limit(MAX_TODAY).forEach(e -> lines.add(time(e, zone) + " " + e.getTitle()));
        if (today.size() > MAX_TODAY) {
            lines.add("还有 " + (today.size() - MAX_TODAY) + " 条，去日程页看");
        }
        early.forEach(e -> lines.add("明早 " + time(e, zone) + " " + e.getTitle()));
        return lines.isEmpty() ? Optional.empty()
                : Optional.of(new Brief.Section("schedule", "日程", lines, "/pages/schedule/schedule"));
    }

    private static String time(ScheduleEvent e, ZoneId zone) {
        return Boolean.TRUE.equals(e.getAllDay()) ? "全天" : e.getStartAt().atZoneSameInstant(zone).format(HM);
    }
}
