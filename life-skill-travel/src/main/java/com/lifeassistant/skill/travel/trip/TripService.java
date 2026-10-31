package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.schedule.ScheduleEvent;
import com.lifeassistant.core.schedule.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 行程的存取：每次保存都追加一个新版本，t_trip 记录当前版本号；所有读写都校验行程属于当前用户。
 */
@Service
@RequiredArgsConstructor
public class TripService {

    private final TripMapper tripMapper;
    private final TripVersionMapper versionMapper;
    private final ScheduleService scheduleService;

    /** 出游技能建的日程在 t_schedule_event.source 里的取值，source_ref 是行程 id */
    public static final String SCHEDULE_SOURCE = "TRAVEL";

    /**
     * 一次保存的输入；元信息字段为空表示沿用原值，content 每次都是完整正文。
     * itinerary 是结构化行程的 JSON（可空）；draft 为 true 时新建的行程是草稿，用户认可后再确认。
     */
    public record TripDraft(String title, String tripType, String destination, LocalDate startDate,
                            LocalDate endDate, String content, String changeNote, String itinerary, boolean draft) {

        /** 只有正文、直接确认的保存。 */
        public TripDraft(String title, String tripType, String destination, LocalDate startDate, LocalDate endDate,
                         String content, String changeNote) {
            this(title, tripType, destination, startDate, endDate, content, changeNote, null, false);
        }
    }

    /** 行程主记录加上某一个版本的内容。 */
    public record TripDetail(Trip trip, TripVersion version) {
    }

    /**
     * 保存一个新版本：tripId 为空时新建行程（版本从 1 开始），否则合并元信息并把版本号加 1。
     * 新行程是草稿（draft）还是已确认看 draft；已有行程的状态不变。
     *
     * @param createdBy USER 或 AI
     */
    @Transactional
    public TripDetail saveVersion(UserCtx ctx, Long tripId, TripDraft draft, String createdBy) {
        Trip trip;
        if (tripId == null) {
            trip = new Trip();
            trip.setUserId(ctx.userId());
            trip.setSpaceId(ctx.spaceId());
            trip.setCurrentVer(0);
            trip.setStatus(draft.draft() ? "DRAFT" : "CONFIRMED");
            apply(trip, draft);
            tripMapper.insert(trip);
        } else {
            trip = getOwned(ctx, tripId);
            apply(trip, draft);
        }
        int version = trip.getCurrentVer() + 1;
        TripVersion tripVersion = new TripVersion();
        tripVersion.setTripId(trip.getId());
        tripVersion.setVersion(version);
        tripVersion.setContent(draft.content());
        tripVersion.setItinerary(draft.itinerary());
        tripVersion.setChangeNote(draft.changeNote());
        tripVersion.setCreatedBy(createdBy);
        versionMapper.insert(tripVersion);

        trip.setCurrentVer(version);
        trip.setUpdatedAt(OffsetDateTime.now());
        tripMapper.updateById(trip);
        return new TripDetail(trip, tripVersion);
    }

    /** 用户认可草稿：状态改成已确认。 */
    public Trip confirm(UserCtx ctx, long tripId) {
        Trip trip = getOwned(ctx, tripId);
        if (!"CONFIRMED".equals(trip.getStatus())) {
            trip.setStatus("CONFIRMED");
            trip.setUpdatedAt(OffsetDateTime.now());
            tripMapper.updateById(trip);
        }
        return trip;
    }

    /** 删除行程：版本随外键级联删除，关联的出游日程和提醒也一起删掉。 */
    @Transactional
    public void delete(UserCtx ctx, long tripId) {
        Trip trip = getOwned(ctx, tripId);
        scheduleService.deleteBySource(ctx, SCHEDULE_SOURCE, trip.getId().toString());
        tripMapper.deleteById(trip.getId());
    }

    /** 行程关联的日程（出游技能建提醒时带了 tripId 的那些）。 */
    public List<ScheduleEvent> events(UserCtx ctx, long tripId) {
        Trip trip = getOwned(ctx, tripId);
        return scheduleService.listBySource(ctx, SCHEDULE_SOURCE, trip.getId().toString());
    }

    /** 当前用户的全部行程，最近更新的在前；只返回主记录，不含正文。 */
    public List<Trip> list(UserCtx ctx) {
        return tripMapper.selectList(new LambdaQueryWrapper<Trip>()
                .eq(Trip::getUserId, ctx.userId())
                .orderByDesc(Trip::getUpdatedAt));
    }

    /** 读取行程的某个版本，version 为空时取当前版本；版本不存在抛 notFound。 */
    public TripDetail get(UserCtx ctx, long tripId, Integer version) {
        Trip trip = getOwned(ctx, tripId);
        int v = version == null ? trip.getCurrentVer() : version;
        TripVersion tripVersion = versionMapper.selectOne(new LambdaQueryWrapper<TripVersion>()
                .eq(TripVersion::getTripId, tripId)
                .eq(TripVersion::getVersion, v));
        if (tripVersion == null) {
            throw BizException.notFound("行程版本");
        }
        return new TripDetail(trip, tripVersion);
    }

    /** 版本历史，新版本在前；不查 content，只返回版本号、改动说明、作者和时间。 */
    public List<TripVersion> versions(UserCtx ctx, long tripId) {
        getOwned(ctx, tripId);
        return versionMapper.selectList(new LambdaQueryWrapper<TripVersion>()
                .select(TripVersion::getTripId, TripVersion::getVersion, TripVersion::getChangeNote,
                        TripVersion::getCreatedBy, TripVersion::getCreatedAt)
                .eq(TripVersion::getTripId, tripId)
                .orderByDesc(TripVersion::getVersion));
    }

    /** 按 id 取行程并校验归属：不存在抛 notFound，不是当前用户的抛 forbidden。 */
    private Trip getOwned(UserCtx ctx, long tripId) {
        Trip trip = tripMapper.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程");
        }
        if (trip.getUserId() != ctx.userId()) {
            throw BizException.forbidden();
        }
        return trip;
    }

    /** 把草稿里非空的元信息合并到行程上；类型仍为空时默认 CITY。 */
    private static void apply(Trip trip, TripDraft draft) {
        if (draft.title() != null) {
            trip.setTitle(draft.title());
        }
        if (draft.tripType() != null) {
            trip.setTripType(draft.tripType());
        }
        if (draft.destination() != null) {
            trip.setDestination(draft.destination());
        }
        if (draft.startDate() != null) {
            trip.setStartDate(draft.startDate());
        }
        if (draft.endDate() != null) {
            trip.setEndDate(draft.endDate());
        }
        if (trip.getTripType() == null) {
            trip.setTripType("CITY");
        }
    }
}
