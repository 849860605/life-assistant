package com.lifeassistant.skill.travel.trip;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.file.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 行程的账本：行程的名称、人数、总预算，预订记录（带凭证文件）和实际支出。
 * 所有操作都先校验行程是当前用户的；一条预订只能记一笔支出，避免重复计算。
 * 平台同步（SYNC）的预订只有服务端能写，用户录入的只能是手动录入或凭证导入。
 */
@Service
@RequiredArgsConstructor
public class TripLedgerService {

    /** 支持的币种；没有明确汇率时按币种分开统计 */
    public static final Set<String> CURRENCIES = Set.of("CNY", "HKD", "MOP", "TWD", "USD", "EUR", "GBP", "JPY", "KRW",
            "THB", "SGD", "MYR", "AUD");
    private static final Set<String> BOOKING_KINDS = Set.of("FLIGHT", "HOTEL", "TICKET", "OTHER");
    private static final Set<String> BOOKING_STATUSES = Set.of("PLANNED", "BOOKED", "CANCELLED");
    private static final Set<String> USER_SOURCES = Set.of("MANUAL", "IMPORT");
    private static final Set<String> EXPENSE_CATEGORIES = Set.of("TRANSPORT", "STAY", "FOOD", "TICKET", "SHOPPING", "OTHER");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final int MAX_FILES = 5;

    private final TripMapper tripMapper;
    private final TripBookingMapper bookingMapper;
    private final TripExpenseMapper expenseMapper;
    private final UploadService uploadService;

    /** 改行程名称、出发城市、人数和总预算的请求；人数、预算可空（表示不填），origin 为 null 表示不改、空串表示清空。 */
    public record InfoRequest(String title, Integer people, BigDecimal budget, String budgetCurrency, String origin) {
    }

    /** 新增或修改预订的请求；fileIds 是已上传的凭证文件 id。 */
    public record BookingRequest(String kind, String title, String platform, OffsetDateTime startAt, OffsetDateTime endAt,
                                 Integer people, BigDecimal amount, String currency, String bookingNo, String location,
                                 String status, String source, List<String> fileIds, String note) {
    }

    /** 新增或修改支出的请求；bookingId 关联一条预订（可空）。 */
    public record ExpenseRequest(String category, String title, BigDecimal amount, String currency, LocalDate spentOn,
                                 Long bookingId, String note) {
    }

    /** 凭证文件：id 和文件名。 */
    public record FileRef(String id, String name) {
    }

    /** 给前端看的预订：预订本身的字段，加上凭证文件和关联的那笔支出 id。 */
    public record BookingView(@JsonUnwrapped TripBooking booking, List<FileRef> files, Long expenseId) {
    }

    // ========== 行程信息 ==========

    /** 改行程名称、出发城市、人数和总预算。 */
    public Trip updateInfo(UserCtx ctx, long tripId, InfoRequest request) {
        owned(ctx, tripId);
        String title = text(request.title(), "行程名称", 60, true);
        Integer people = request.people();
        if (people != null && (people < 1 || people > 99)) {
            throw bad("人数要在 1 到 99 之间");
        }
        BigDecimal budget = request.budget() == null ? null : amount(request.budget(), "预算");
        String currency = currency(request.budgetCurrency());
        LambdaUpdateWrapper<Trip> update = new LambdaUpdateWrapper<Trip>().eq(Trip::getId, tripId);
        if (request.origin() != null) {
            update.set(Trip::getOrigin, text(request.origin(), "出发城市", 32, false));
        }
        tripMapper.update(update
                .set(Trip::getTitle, title)
                .set(Trip::getPeople, people)
                .set(Trip::getBudget, budget)
                .set(Trip::getBudgetCurrency, currency)
                .set(Trip::getUpdatedAt, OffsetDateTime.now()));
        return tripMapper.selectById(tripId);
    }

    // ========== 预订 ==========

    /** 行程的全部预订，按开始时间（没有的排后面）、再按录入顺序。 */
    public List<BookingView> bookings(UserCtx ctx, long tripId) {
        owned(ctx, tripId);
        List<TripBooking> rows = bookingMapper.selectList(new LambdaQueryWrapper<TripBooking>()
                .eq(TripBooking::getTripId, tripId)
                .orderByAsc(TripBooking::getStartAt)
                .orderByAsc(TripBooking::getId));
        Map<Long, Long> expenseByBooking = expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
                        .eq(TripExpense::getTripId, tripId)
                        .isNotNull(TripExpense::getBookingId))
                .stream().collect(Collectors.toMap(TripExpense::getBookingId, TripExpense::getId, (a, b) -> a));
        return rows.stream().map(b -> view(ctx, b, expenseByBooking.get(b.getId()))).toList();
    }

    /** 新增一条预订。 */
    public BookingView createBooking(UserCtx ctx, long tripId, BookingRequest request) {
        owned(ctx, tripId);
        TripBooking row = new TripBooking();
        row.setTripId(tripId);
        row.setUserId(ctx.userId());
        fill(ctx, row, request);
        bookingMapper.insert(row);
        return view(ctx, bookingMapper.selectById(row.getId()), null);
    }

    /** 修改一条预订；平台同步的不能手动改。 */
    public BookingView updateBooking(UserCtx ctx, long tripId, long bookingId, BookingRequest request) {
        TripBooking existing = ownedBooking(ctx, tripId, bookingId);
        if ("SYNC".equals(existing.getSource())) {
            throw bad("平台同步的预订不能手动修改");
        }
        TripBooking row = new TripBooking();
        fill(ctx, row, request);
        bookingMapper.update(new LambdaUpdateWrapper<TripBooking>()
                .eq(TripBooking::getId, bookingId)
                .set(TripBooking::getKind, row.getKind())
                .set(TripBooking::getTitle, row.getTitle())
                .set(TripBooking::getPlatform, row.getPlatform())
                .set(TripBooking::getStartAt, row.getStartAt())
                .set(TripBooking::getEndAt, row.getEndAt())
                .set(TripBooking::getPeople, row.getPeople())
                .set(TripBooking::getAmount, row.getAmount())
                .set(TripBooking::getCurrency, row.getCurrency())
                .set(TripBooking::getBookingNo, row.getBookingNo())
                .set(TripBooking::getLocation, row.getLocation())
                .set(TripBooking::getStatus, row.getStatus())
                .set(TripBooking::getSource, row.getSource())
                .set(TripBooking::getFileIds, row.getFileIds())
                .set(TripBooking::getNote, row.getNote())
                .set(TripBooking::getUpdatedAt, OffsetDateTime.now()));
        TripExpense linked = expenseMapper.selectOne(new LambdaQueryWrapper<TripExpense>().eq(TripExpense::getBookingId, bookingId));
        return view(ctx, bookingMapper.selectById(bookingId), linked == null ? null : linked.getId());
    }

    /** 删除一条预订；关联的支出保留，只是不再关联。 */
    public void deleteBooking(UserCtx ctx, long tripId, long bookingId) {
        ownedBooking(ctx, tripId, bookingId);
        bookingMapper.deleteById(bookingId);
    }

    // ========== 支出 ==========

    /** 行程的全部支出，按日期（没有的排后面）、再按录入顺序。 */
    public List<TripExpense> expenses(UserCtx ctx, long tripId) {
        owned(ctx, tripId);
        return expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
                .eq(TripExpense::getTripId, tripId)
                .orderByAsc(TripExpense::getSpentOn)
                .orderByAsc(TripExpense::getId));
    }

    /** 记一笔支出；关联预订时校验预订在同一个行程里，且还没记过。 */
    @Transactional
    public TripExpense createExpense(UserCtx ctx, long tripId, ExpenseRequest request) {
        owned(ctx, tripId);
        TripExpense row = new TripExpense();
        row.setTripId(tripId);
        row.setUserId(ctx.userId());
        fill(ctx, tripId, null, row, request);
        expenseMapper.insert(row);
        return expenseMapper.selectById(row.getId());
    }

    /** 修改一笔支出。 */
    @Transactional
    public TripExpense updateExpense(UserCtx ctx, long tripId, long expenseId, ExpenseRequest request) {
        ownedExpense(ctx, tripId, expenseId);
        TripExpense row = new TripExpense();
        fill(ctx, tripId, expenseId, row, request);
        expenseMapper.update(new LambdaUpdateWrapper<TripExpense>()
                .eq(TripExpense::getId, expenseId)
                .set(TripExpense::getCategory, row.getCategory())
                .set(TripExpense::getTitle, row.getTitle())
                .set(TripExpense::getAmount, row.getAmount())
                .set(TripExpense::getCurrency, row.getCurrency())
                .set(TripExpense::getSpentOn, row.getSpentOn())
                .set(TripExpense::getBookingId, row.getBookingId())
                .set(TripExpense::getNote, row.getNote())
                .set(TripExpense::getUpdatedAt, OffsetDateTime.now()));
        return expenseMapper.selectById(expenseId);
    }

    /** 删除一笔支出。 */
    public void deleteExpense(UserCtx ctx, long tripId, long expenseId) {
        ownedExpense(ctx, tripId, expenseId);
        expenseMapper.deleteById(expenseId);
    }

    // ========== 校验和组装 ==========

    /** 校验并把请求写进预订行（不含 id、行程和用户）。 */
    private void fill(UserCtx ctx, TripBooking row, BookingRequest r) {
        row.setKind(oneOf(r.kind(), BOOKING_KINDS, "预订类型"));
        row.setTitle(text(r.title(), "名称", 120, true));
        row.setPlatform(text(r.platform(), "平台", 40, false));
        if (r.startAt() != null && r.endAt() != null && r.endAt().isBefore(r.startAt())) {
            throw bad("结束时间不能早于开始时间");
        }
        row.setStartAt(r.startAt());
        row.setEndAt(r.endAt());
        if (r.people() != null && (r.people() < 1 || r.people() > 99)) {
            throw bad("人数要在 1 到 99 之间");
        }
        row.setPeople(r.people());
        row.setAmount(r.amount() == null ? null : amount(r.amount(), "金额"));
        row.setCurrency(currency(r.currency()));
        row.setBookingNo(text(r.bookingNo(), "订单号", 80, false));
        row.setLocation(text(r.location(), "地点", 200, false));
        row.setStatus(r.status() == null ? "BOOKED" : oneOf(r.status(), BOOKING_STATUSES, "预订状态"));
        if (r.source() != null && !USER_SOURCES.contains(r.source())) {
            throw bad("来源只能是手动录入或凭证导入");
        }
        row.setSource(r.source() == null ? "MANUAL" : r.source());
        List<String> ids = r.fileIds() == null ? List.of() : r.fileIds().stream().filter(StringUtils::hasText).distinct().toList();
        if (ids.size() > MAX_FILES) {
            throw bad("凭证最多 " + MAX_FILES + " 个文件");
        }
        ids.forEach(id -> uploadService.ownedName(ctx.userId(), id));
        row.setFileIds(ids.isEmpty() ? null : String.join(",", ids));
        row.setNote(text(r.note(), "备注", 500, false));
    }

    /** 校验并把请求写进支出行；expenseId 是正在改的那笔（新增时为空），用来判断关联的预订是否已被别的支出占用。 */
    private void fill(UserCtx ctx, long tripId, Long expenseId, TripExpense row, ExpenseRequest r) {
        row.setCategory(oneOf(r.category(), EXPENSE_CATEGORIES, "分类"));
        row.setTitle(text(r.title(), "名称", 120, true));
        if (r.amount() == null) {
            throw bad("请填写金额");
        }
        row.setAmount(amount(r.amount(), "金额"));
        row.setCurrency(currency(r.currency()));
        row.setSpentOn(r.spentOn());
        if (r.bookingId() != null) {
            ownedBooking(ctx, tripId, r.bookingId());
            TripExpense taken = expenseMapper.selectOne(new LambdaQueryWrapper<TripExpense>()
                    .eq(TripExpense::getBookingId, r.bookingId()));
            if (taken != null && !taken.getId().equals(expenseId)) {
                throw bad("这条预订已经记过一笔支出了，改那一笔就行");
            }
        }
        row.setBookingId(r.bookingId());
        row.setNote(text(r.note(), "备注", 500, false));
    }

    private BookingView view(UserCtx ctx, TripBooking b, Long expenseId) {
        List<FileRef> files = new ArrayList<>();
        if (StringUtils.hasText(b.getFileIds())) {
            for (String id : Arrays.asList(b.getFileIds().split(","))) {
                try {
                    files.add(new FileRef(id, uploadService.ownedName(ctx.userId(), id)));
                } catch (BizException e) {
                    files.add(new FileRef(id, null));
                }
            }
        }
        return new BookingView(b, files, expenseId);
    }

    private Trip owned(UserCtx ctx, long tripId) {
        Trip trip = tripMapper.selectById(tripId);
        if (trip == null) {
            throw BizException.notFound("行程");
        }
        if (trip.getUserId() != ctx.userId()) {
            throw BizException.forbidden();
        }
        return trip;
    }

    private TripBooking ownedBooking(UserCtx ctx, long tripId, long bookingId) {
        owned(ctx, tripId);
        TripBooking row = bookingMapper.selectById(bookingId);
        if (row == null || row.getTripId() != tripId || row.getUserId() != ctx.userId()) {
            throw BizException.notFound("预订");
        }
        return row;
    }

    private TripExpense ownedExpense(UserCtx ctx, long tripId, long expenseId) {
        owned(ctx, tripId);
        TripExpense row = expenseMapper.selectById(expenseId);
        if (row == null || row.getTripId() != tripId || row.getUserId() != ctx.userId()) {
            throw BizException.notFound("支出");
        }
        return row;
    }

    /** 去掉首尾空白；required 时不能为空；超长报错。空字符串按没填。 */
    private static String text(String value, String label, int max, boolean required) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            if (required) {
                throw bad("请填写" + label);
            }
            return null;
        }
        if (clean.length() > max) {
            throw bad(label + "最多 " + max + " 个字");
        }
        return clean;
    }

    private static String oneOf(String value, Set<String> allowed, String label) {
        if (value == null || !allowed.contains(value)) {
            throw bad(label + "不对");
        }
        return value;
    }

    /** 金额：0 到 1 亿，最多两位小数。 */
    private static BigDecimal amount(BigDecimal value, String label) {
        if (value.signum() < 0 || value.compareTo(MAX_AMOUNT) >= 0) {
            throw bad(label + "要在 0 到 1 亿之间");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw bad(label + "最多两位小数");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String currency(String value) {
        String c = value == null || value.isBlank() ? "CNY" : value.trim().toUpperCase();
        if (!CURRENCIES.contains(c)) {
            throw bad("不支持的币种：" + c);
        }
        return c;
    }

    private static BizException bad(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }
}
