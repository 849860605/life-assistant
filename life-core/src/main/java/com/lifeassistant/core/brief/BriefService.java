package com.lifeassistant.core.brief;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.memory.MemoryItem;
import com.lifeassistant.core.memory.MemoryService;
import com.lifeassistant.core.notify.Notice;
import com.lifeassistant.core.notify.NotificationService;
import com.lifeassistant.core.user.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 每日简报：读设置、拼内容、推送。内容由各个 BriefSection 提供，按 order 排；某段出错只跳过那段。
 */
@Slf4j
@Service
public class BriefService {

    static final String DEFAULT_TIME = "07:30";
    private static final Pattern HHMM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final String[] WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final BriefSettingMapper settingMapper;
    private final List<BriefSection> sections;
    private final UserService userService;
    private final MemoryService memoryService;
    private final NotificationService notificationService;

    public BriefService(BriefSettingMapper settingMapper, List<BriefSection> sections, UserService userService,
                        MemoryService memoryService, NotificationService notificationService) {
        this.settingMapper = settingMapper;
        this.sections = sections.stream().sorted(Comparator.comparingInt(BriefSection::order)).toList();
        this.userService = userService;
        this.memoryService = memoryService;
        this.notificationService = notificationService;
    }

    /** 给页面看的设置：city 为空时 suggestedCity 是从记忆里猜的常住城市；hasChannel 表示能不能推送。 */
    public record Settings(boolean enabled, String sendTime, String city, String suggestedCity, boolean hasChannel) {
    }

    public Settings settings(long userId) {
        BriefSetting s = setting(userId);
        return new Settings(s.getEnabled(), s.getSendTime(), s.getCity(), s.getCity() == null ? memoryCity(userId) : null,
                notificationService.hasChannel(userId));
    }

    /** 改设置：时间要是 HH:mm，城市留空表示用记忆里的常住城市。 */
    public Settings update(long userId, boolean enabled, String sendTime, String city) {
        String time = sendTime == null || sendTime.isBlank() ? DEFAULT_TIME : sendTime.trim();
        if (!HHMM.matcher(time).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "推送时间要写成 07:30 这样");
        }
        String c = city == null || city.isBlank() ? null : city.trim();
        if (c != null && c.length() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "城市名太长了");
        }
        BriefSetting row = settingMapper.selectById(userId);
        boolean exists = row != null;
        if (!exists) {
            row = new BriefSetting();
            row.setUserId(userId);
        }
        row.setEnabled(enabled);
        row.setSendTime(time);
        row.setCity(c);
        row.setUpdatedAt(OffsetDateTime.now());
        if (exists) {
            settingMapper.updateById(row);
        } else {
            settingMapper.insert(row);
        }
        return settings(userId);
    }

    /** 生成今天（用户时区）的简报。 */
    public Brief build(long userId) {
        ZoneId zone = userService.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        BriefSetting setting = setting(userId);
        String city = setting.getCity() != null ? setting.getCity() : memoryCity(userId);
        Brief.Context context = new Brief.Context(userId, today, city);
        List<Brief.Section> out = new ArrayList<>();
        for (BriefSection section : sections) {
            try {
                section.build(context).ifPresent(out::add);
            } catch (RuntimeException e) {
                log.warn("简报的 {} 段生成失败：{}", section.getClass().getSimpleName(), e.getMessage());
            }
        }
        String title = today.getMonthValue() + "月" + today.getDayOfMonth() + "日 " + WEEK[today.getDayOfWeek().getValue() - 1]
                + " · 今日简报";
        return new Brief(today.toString(), title, out);
    }

    /** 推送到用户的渠道；没有内容也推一句，免得用户以为坏了。 */
    public Brief push(long userId) {
        Brief brief = build(userId);
        notificationService.send(userId, new Notice(brief.title(), body(brief), "每日简报", null, "active"), null);
        return brief;
    }

    /** 推送正文：每段一行小标题，后面是内容。 */
    static String body(Brief brief) {
        if (brief.sections().isEmpty()) {
            return "今天没有日程和行程，祝你一天顺利";
        }
        StringBuilder out = new StringBuilder();
        for (Brief.Section s : brief.sections()) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("【").append(s.title()).append("】");
            s.lines().forEach(line -> out.append('\n').append(line));
        }
        return out.toString();
    }

    /** 读设置，没有记录时用默认值（开、07:30）。 */
    BriefSetting setting(long userId) {
        BriefSetting row = settingMapper.selectById(userId);
        if (row == null) {
            row = new BriefSetting();
            row.setUserId(userId);
            row.setEnabled(true);
            row.setSendTime(DEFAULT_TIME);
        }
        return row;
    }

    /** 已经推过的那天记下来，一天只推一次。 */
    void markSent(long userId, LocalDate day) {
        BriefSetting row = settingMapper.selectById(userId);
        if (row == null) {
            row = setting(userId);
            row.setLastSentOn(day);
            settingMapper.insert(row);
        } else {
            row.setLastSentOn(day);
            settingMapper.updateById(row);
        }
    }

    /** 记忆里的常住城市（home_city，例如"从深圳出发""常住杭州"），取出城市名；没有返回 null。 */
    String memoryCity(long userId) {
        Optional<MemoryItem> home = memoryService.listForOwner(UserCtx.of(userId)).stream()
                .filter(m -> "home_city".equals(m.getMemKey())).findFirst();
        return home.map(m -> cityOf(m.getContent())).orElse(null);
    }

    /** "从深圳出发""常住在杭州市""住深圳南山" → 深圳 / 杭州 / 深圳南山；取不出来返回 null。 */
    static String cityOf(String content) {
        if (content == null) {
            return null;
        }
        String c = content.replaceAll("(用户)?(常住(在)?|住在|居住在|家在|现居|从|出发|在|城市|是|，.*|。.*)", "").trim();
        c = c.replaceAll("市$", "");
        return c.isEmpty() || c.length() > 10 ? null : c;
    }
}
