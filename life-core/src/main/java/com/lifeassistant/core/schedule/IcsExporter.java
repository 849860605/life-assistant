package com.lifeassistant.core.schedule;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 生成 ICS 日历订阅内容（RFC 5545），让 iPhone 日历订阅后自动同步。
 */
@Component
public class IcsExporter {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    /** RFC 5545：一行最多 75 个字节（不含换行），超出要折行 */
    private static final int MAX_LINE_OCTETS = 75;

    /**
     * 把日程拼成 VCALENDAR 文本。普通日程时间转 UTC，没有结束时间的按 1 小时算；
     * 全天日程按 zone 取日期，结束日期是最后一天的次日（RFC 5545 的 DTEND 不含当天）。
     */
    public String export(List<ScheduleEvent> events, ZoneId zone) {
        StringBuilder ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//life-assistant//CN");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "X-WR-CALNAME:星络");
        String stamp = UTC.format(OffsetDateTime.now(ZoneOffset.UTC));
        for (ScheduleEvent event : events) {
            line(ics, "BEGIN:VEVENT");
            line(ics, "UID:event-" + event.getId() + "@life-assistant");
            line(ics, "DTSTAMP:" + stamp);
            if (Boolean.TRUE.equals(event.getAllDay())) {
                LocalDate first = event.getStartAt().atZoneSameInstant(zone).toLocalDate();
                LocalDate last = event.getEndAt() != null ? event.getEndAt().atZoneSameInstant(zone).toLocalDate() : first;
                line(ics, "DTSTART;VALUE=DATE:" + DATE.format(first));
                line(ics, "DTEND;VALUE=DATE:" + DATE.format((last.isBefore(first) ? first : last).plusDays(1)));
            } else {
                OffsetDateTime end = event.getEndAt() != null ? event.getEndAt() : event.getStartAt().plusHours(1);
                line(ics, "DTSTART:" + utc(event.getStartAt()));
                line(ics, "DTEND:" + utc(end));
            }
            String title = event.getTitle() == null || event.getTitle().isBlank() ? "（无标题）" : event.getTitle();
            line(ics, "SUMMARY:" + escape(title));
            if (event.getLocation() != null) {
                line(ics, "LOCATION:" + escape(event.getLocation()));
            }
            if (event.getDescription() != null) {
                line(ics, "DESCRIPTION:" + escape(event.getDescription()));
            }
            line(ics, "END:VEVENT");
        }
        line(ics, "END:VCALENDAR");
        return ics.toString();
    }

    /** 转成 ICS 的 UTC 时间格式。 */
    private static String utc(OffsetDateTime time) {
        return UTC.format(time.withOffsetSameInstant(ZoneOffset.UTC));
    }

    /** 按 ICS 文本规则转义反斜杠、分号、逗号和换行。 */
    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    /** 追加一行并以 CRLF 结尾；超过 75 字节时折行，续行以一个空格开头，不会把一个汉字拆成两半。 */
    static void line(StringBuilder ics, String content) {
        int lineOctets = 0;
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            int octets = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (lineOctets + octets > MAX_LINE_OCTETS) {
                ics.append("\r\n ");
                lineOctets = 1;
            }
            ics.appendCodePoint(codePoint);
            lineOctets += octets;
            i += Character.charCount(codePoint);
        }
        ics.append("\r\n");
    }
}
