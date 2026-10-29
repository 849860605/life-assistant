package com.lifeassistant.core.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 日程存储和提醒调度属于平台核心，所有技能都可以创建日程；
 * life-skill-schedule 只是它的对话入口。
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleEventMapper eventMapper;
    private final ReminderMapper reminderMapper;

    /** 新建日程的参数；source 为空时记为 USER，remindMinutesBefore 为空或负数表示不提醒。 */
    public record NewEvent(String title, OffsetDateTime startAt, OffsetDateTime endAt, String location,
                           String description, String source, String sourceRef, Integer remindMinutesBefore) {
    }

    /** 新建日程，需要时按开始时间往前推算一并创建提醒（同一事务）。 */
    @Transactional
    public ScheduleEvent createEvent(UserCtx ctx, NewEvent request) {
        if (request.endAt() != null && request.endAt().isBefore(request.startAt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "结束时间不能早于开始时间");
        }
        ScheduleEvent event = new ScheduleEvent();
        event.setUserId(ctx.userId());
        event.setSpaceId(ctx.spaceId());
        event.setTitle(request.title());
        event.setStartAt(request.startAt());
        event.setEndAt(request.endAt());
        event.setLocation(request.location());
        event.setDescription(request.description());
        event.setAllDay(false);
        event.setSource(request.source() == null ? "USER" : request.source());
        event.setSourceRef(request.sourceRef());
        eventMapper.insert(event);

        if (request.remindMinutesBefore() != null && request.remindMinutesBefore() >= 0) {
            OffsetDateTime remindAt = request.startAt().minusMinutes(request.remindMinutesBefore());
            String body = request.location() == null ? null : "地点：" + request.location();
            createReminder(ctx.userId(), event.getId(), event.getTitle(), body, null, "active", remindAt);
        }
        return event;
    }

    /** 新建一条 PENDING 提醒，由 ReminderDispatcher 到点发送；eventId 为空表示不关联日程。 */
    public Reminder createReminder(long userId, Long eventId, String title, String body, String url,
                                   String level, OffsetDateTime remindAt) {
        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setEventId(eventId);
        reminder.setTitle(title);
        reminder.setBody(body);
        reminder.setUrl(url);
        reminder.setLevel(level);
        reminder.setRemindAt(remindAt);
        reminder.setStatus("PENDING");
        reminder.setAttempts(0);
        reminderMapper.insert(reminder);
        return reminder;
    }

    /** 查当前用户开始时间落在 [from, to) 的日程，from/to 为空表示不限，按开始时间升序。 */
    public List<ScheduleEvent> list(UserCtx ctx, OffsetDateTime from, OffsetDateTime to) {
        return eventMapper.selectList(new LambdaQueryWrapper<ScheduleEvent>()
                .eq(ScheduleEvent::getUserId, ctx.userId())
                .ge(from != null, ScheduleEvent::getStartAt, from)
                .lt(to != null, ScheduleEvent::getStartAt, to)
                .orderByAsc(ScheduleEvent::getStartAt));
    }

    /** 某个来源对象（如 TRAVEL + 行程 id）关联的日程，按开始时间升序。 */
    public List<ScheduleEvent> listBySource(UserCtx ctx, String source, String sourceRef) {
        return eventMapper.selectList(new LambdaQueryWrapper<ScheduleEvent>()
                .eq(ScheduleEvent::getUserId, ctx.userId())
                .eq(ScheduleEvent::getSource, source)
                .eq(ScheduleEvent::getSourceRef, sourceRef)
                .orderByAsc(ScheduleEvent::getStartAt));
    }

    /** 删掉某个来源对象关联的全部日程，提醒随外键级联删除；返回删除条数。 */
    @Transactional
    public int deleteBySource(UserCtx ctx, String source, String sourceRef) {
        return eventMapper.delete(new LambdaQueryWrapper<ScheduleEvent>()
                .eq(ScheduleEvent::getUserId, ctx.userId())
                .eq(ScheduleEvent::getSource, source)
                .eq(ScheduleEvent::getSourceRef, sourceRef));
    }

    /** 只能删自己的日程，不存在或不属于当前用户都报 404。 */
    @Transactional
    public void delete(UserCtx ctx, long eventId) {
        int deleted = eventMapper.delete(new LambdaQueryWrapper<ScheduleEvent>()
                .eq(ScheduleEvent::getId, eventId)
                .eq(ScheduleEvent::getUserId, ctx.userId()));
        if (deleted == 0) {
            throw BizException.notFound("日程");
        }
    }
}
