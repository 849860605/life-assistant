package com.lifeassistant.core.brief;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.core.notify.NotifyChannel;
import com.lifeassistant.core.notify.NotifyChannelMapper;
import com.lifeassistant.core.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 到点推每日简报：每分钟看一次设了推送渠道的用户，到了设定时间、今天还没推过就推。
 * 服务停机错过时间的，两小时内补推，再晚就不推了（中午收到早安简报没意义）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BriefDispatcher {

    private final NotifyChannelMapper channelMapper;
    private final BriefService briefService;
    private final UserService userService;

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "briefDispatcher", lockAtMostFor = "PT5M")
    public void dispatch() {
        channelMapper.selectList(new LambdaQueryWrapper<NotifyChannel>().eq(NotifyChannel::getEnabled, true)).stream()
                .map(NotifyChannel::getUserId).distinct().forEach(this::maybeSend);
    }

    private void maybeSend(long userId) {
        try {
            BriefSetting setting = briefService.setting(userId);
            ZoneId zone = userService.zoneOf(userId);
            ZonedDateTime now = ZonedDateTime.now(zone);
            LocalDate today = now.toLocalDate();
            LocalTime at = LocalTime.parse(setting.getSendTime());
            long late = java.time.Duration.between(at, now.toLocalTime()).toMinutes();
            if (!Boolean.TRUE.equals(setting.getEnabled()) || today.equals(setting.getLastSentOn()) || late < 0 || late > 120) {
                return;
            }
            // 先记下再推：推送失败也不在这一天里每分钟重试刷屏
            briefService.markSent(userId, today);
            briefService.push(userId);
        } catch (RuntimeException e) {
            log.warn("给用户 {} 推每日简报失败：{}", userId, e.getMessage());
        }
    }
}
