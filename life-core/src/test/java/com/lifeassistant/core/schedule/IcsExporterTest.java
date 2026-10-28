package com.lifeassistant.core.schedule;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ICS 导出：空标题、全天日程、长行折叠。 */
class IcsExporterTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final IcsExporter exporter = new IcsExporter();

    @Test
    void nullTitleDoesNotThrow() {
        ScheduleEvent event = event(null, "2026-11-07T04:00:00+08:00", false);

        assertThat(exporter.export(List.of(event), SHANGHAI)).contains("SUMMARY:（无标题）");
    }

    @Test
    void allDayEventUsesDateInUserZone() {
        // 北京时间 11 月 7 日 0 点，在 UTC 里还是 11 月 6 日，日期必须按用户时区取
        ScheduleEvent event = event("国庆调休", "2026-11-07T00:00:00+08:00", true);

        String ics = exporter.export(List.of(event), SHANGHAI);

        assertThat(ics).contains("DTSTART;VALUE=DATE:20261107\r\n").contains("DTEND;VALUE=DATE:20261108\r\n");
    }

    @Test
    void longLinesAreFoldedWithoutSplittingChineseCharacters() {
        ScheduleEvent event = event("爬泰山", "2026-11-07T04:00:00+08:00", false);
        event.setDescription("红门上山，中天门休息，十八盘注意脚下，南天门看日出，带头灯、冲锋衣、手套、登山杖和足够的水");

        String ics = exporter.export(List.of(event), SHANGHAI);

        assertThat(ics).contains("\r\n ");
        for (String physicalLine : ics.split("\r\n")) {
            assertThat(physicalLine.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
            assertThat(physicalLine).doesNotContain("�");
        }
        String unfolded = ics.replace("\r\n ", "");
        assertThat(Arrays.asList(unfolded.split("\r\n")))
                .contains("DESCRIPTION:" + IcsExporter.escape(event.getDescription()));
    }

    private static ScheduleEvent event(String title, String startAt, boolean allDay) {
        ScheduleEvent event = new ScheduleEvent();
        event.setId(1L);
        event.setTitle(title);
        event.setStartAt(OffsetDateTime.parse(startAt));
        event.setAllDay(allDay);
        return event;
    }
}
