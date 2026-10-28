package com.lifeassistant.core.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.notify.Notice;
import com.lifeassistant.core.notify.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 到点发送提醒。按 remind_at <= now 捞取，所以服务重启后会补发离线期间错过的提醒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderDispatcher {

    private static final int MAX_ATTEMPTS = 5;

    private final ReminderMapper reminderMapper;
    private final NotificationService notificationService;

    /** 每次最多取 50 条到期的 PENDING 提醒按时间先后发送；ShedLock 保证多实例时只有一个在跑。 */
    @Scheduled(fixedDelayString = "${life.reminder.poll-interval:PT30S}")
    @SchedulerLock(name = "reminderDispatcher", lockAtMostFor = "PT2M")
    public void dispatchDue() {
        List<Reminder> due = reminderMapper.selectList(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getStatus, "PENDING")
                .le(Reminder::getRemindAt, OffsetDateTime.now())
                .orderByAsc(Reminder::getRemindAt)
                .last("LIMIT 50"));
        due.forEach(this::send);
    }

    /** 管理端手动重试：重置为 PENDING、清零失败次数后立即发送一次。 */
    public void retry(long reminderId) {
        Reminder reminder = reminderMapper.selectById(reminderId);
        if (reminder == null) {
            throw BizException.notFound("提醒");
        }
        reminder.setStatus("PENDING");
        reminder.setAttempts(0);
        send(reminder);
    }

    /** 发送并回写状态：成功置 SENT；失败累加次数，满 MAX_ATTEMPTS 次置 FAILED，否则留在 PENDING 等下轮重试。 */
    private void send(Reminder reminder) {
        try {
            notificationService.send(reminder.getUserId(),
                    new Notice(reminder.getTitle(), reminder.getBody(), "日程", reminder.getUrl(), reminder.getLevel()),
                    reminder.getId());
            reminder.setStatus("SENT");
            reminder.setSentAt(OffsetDateTime.now());
            reminder.setLastError(null);
        } catch (RuntimeException e) {
            int attempts = reminder.getAttempts() == null ? 1 : reminder.getAttempts() + 1;
            reminder.setAttempts(attempts);
            reminder.setLastError(e.getMessage());
            reminder.setStatus(attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING");
            log.warn("提醒 {} 第 {} 次发送失败：{}", reminder.getId(), attempts, e.getMessage());
        }
        reminderMapper.updateById(reminder);
    }
}
