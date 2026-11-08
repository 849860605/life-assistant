package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
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
    private final ObjectMapper objectMapper;

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

    /** 记下行程的出发城市（规划时用户说了从哪出发）；空的不改。 */
    public void setOrigin(long tripId, String origin) {
        if (origin == null || origin.isBlank()) {
            return;
        }
        String city = origin.trim();
        tripMapper.update(new LambdaUpdateWrapper<Trip>().eq(Trip::getId, tripId)
                .set(Trip::getOrigin, city.length() > 32 ? city.substring(0, 32) : city));
    }

    /**
     * 保存一个新版本：tripId 为空时新建行程（版本从 1 开始），否则合并元信息并把版本号加 1。
     * 新行程是草稿（draft）还是已确认看 draft；已有行程的状态不变。
     *
     * @param createdBy USER 或 AI
     */
    @Transactional
    public TripDetail saveVersion(UserCtx ctx, Long tripId, TripDraft draft, String createdBy) {
        return saveVersion(ctx, tripId, draft, createdBy, null);
    }

    /**
     * 同上；baseVersion 是这次修改基于的版本号，不为空时要求它还是当前版本，否则说明别处（另一个页面或助手）刚改过，
     * 抛 CONFLICT 让用户刷新，而不是悄悄覆盖。两个修改同时提交时，后提交的也会因为版本号对不上而失败。
     */
    @Transactional
    public TripDetail saveVersion(UserCtx ctx, Long tripId, TripDraft draft, String createdBy, Integer baseVersion) {
        Trip trip;
        int oldVer;
        if (tripId == null) {
            trip = new Trip();
            trip.setUserId(ctx.userId());
            trip.setSpaceId(ctx.spaceId());
            trip.setCurrentVer(0);
            trip.setStatus(draft.draft() ? "DRAFT" : "CONFIRMED");
            apply(trip, draft);
            tripMapper.insert(trip);
            oldVer = 0;
        } else {
            trip = getOwned(ctx, tripId);
            oldVer = trip.getCurrentVer();
            if (baseVersion != null && baseVersion != oldVer) {
                throw new BizException(ErrorCode.CONFLICT, "行程刚被修改过（现在是第 " + oldVer + " 版），请刷新后再改");
            }
            apply(trip, draft);
        }
        int version = oldVer + 1;
        trip.setCurrentVer(version);
        trip.setUpdatedAt(OffsetDateTime.now());
        // 先按旧版本号条件更新主记录占住新版本号：并发的另一次保存会在这里更新不到行而失败
        int updated = tripMapper.update(trip, new LambdaUpdateWrapper<Trip>()
                .eq(Trip::getId, trip.getId())
                .eq(Trip::getCurrentVer, oldVer));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "行程刚被修改过，请刷新后再改");
        }
        TripVersion tripVersion = new TripVersion();
        tripVersion.setTripId(trip.getId());
        tripVersion.setVersion(version);
        tripVersion.setContent(draft.content());
        tripVersion.setItinerary(draft.itinerary());
        tripVersion.setChangeNote(draft.changeNote());
        tripVersion.setCreatedBy(createdBy);
        versionMapper.insert(tripVersion);
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

    /**
     * 只删一个版本，行程和其他版本都还在；只剩这一版时不能删（要删就删整个行程）。
     * 删的是正在用的版本时，行程退回到剩下的最新一版，出发和结束日期也按那一版改回来。
     */
    @Transactional
    public Trip deleteVersion(UserCtx ctx, long tripId, int version) {
        Trip trip = getOwned(ctx, tripId);
        List<TripVersion> rest = versionMapper.selectList(new LambdaQueryWrapper<TripVersion>()
                .select(TripVersion::getVersion, TripVersion::getItinerary)
                .eq(TripVersion::getTripId, tripId)
                .ne(TripVersion::getVersion, version)
                .orderByDesc(TripVersion::getVersion));
        if (rest.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只剩这一个版本了，要删就删整个行程");
        }
        int deleted = versionMapper.delete(new LambdaQueryWrapper<TripVersion>()
                .eq(TripVersion::getTripId, tripId)
                .eq(TripVersion::getVersion, version));
        if (deleted == 0) {
            throw BizException.notFound("行程版本");
        }
        int oldVer = trip.getCurrentVer();
        if (oldVer != version) {
            return trip;
        }
        TripVersion back = rest.get(0);
        trip.setCurrentVer(back.getVersion());
        applyDates(trip, back.getItinerary());
        trip.setUpdatedAt(OffsetDateTime.now());
        // 和保存新版本一样按旧版本号条件更新，同时有人在改就让这次删除失败回滚
        int updated = tripMapper.update(trip, new LambdaUpdateWrapper<Trip>()
                .eq(Trip::getId, tripId)
                .eq(Trip::getCurrentVer, oldVer));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "行程刚被修改过，请刷新后再删");
        }
        return trip;
    }

    /** 按结构化行程第一天和最后一天改出发、结束日期；没有结构化行程或日期不对就不动。 */
    private void applyDates(Trip trip, String itinerary) {
        if (itinerary == null) {
            return;
        }
        try {
            JsonNode days = objectMapper.readTree(itinerary).path("days");
            if (days.isArray() && !days.isEmpty()) {
                trip.setStartDate(LocalDate.parse(days.get(0).path("date").asText()));
                trip.setEndDate(LocalDate.parse(days.get(days.size() - 1).path("date").asText()));
            }
        } catch (Exception e) {
            // 日期解析不了就沿用原来的
        }
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

    /**
     * 原地改某个版本的结构化行程，只用于补坐标、锁定这类不改内容的变化；内容有变要用 saveVersion 存新版本。
     */
    public void updateItinerary(long tripId, int version, String itinerary) {
        updateItinerary(tripId, version, itinerary, null);
    }

    /** 同上，content 不为空时正文一起更新（比如重新比价后价格变了）。 */
    public void updateItinerary(long tripId, int version, String itinerary, String content) {
        versionMapper.update(null, new LambdaUpdateWrapper<TripVersion>()
                .set(TripVersion::getItinerary, itinerary)
                .set(content != null, TripVersion::getContent, content)
                .eq(TripVersion::getTripId, tripId)
                .eq(TripVersion::getVersion, version));
    }

    /** 不校验用户，取行程和指定版本；只给后台任务（比如保存后补坐标）用。 */
    public TripDetail getInternal(long tripId, int version) {
        Trip trip = tripMapper.selectById(tripId);
        TripVersion tripVersion = trip == null ? null : versionMapper.selectOne(new LambdaQueryWrapper<TripVersion>()
                .eq(TripVersion::getTripId, tripId)
                .eq(TripVersion::getVersion, version));
        return tripVersion == null ? null : new TripDetail(trip, tripVersion);
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
