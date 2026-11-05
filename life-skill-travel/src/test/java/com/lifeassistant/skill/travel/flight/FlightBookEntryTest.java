package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 查过的航班存进 Redis 是 JSON：写出去再读回来内容不变，quick 是算出来的不写进去。 */
class FlightBookEntryTest {

    @Test
    void entryRoundTripsThroughJson() throws Exception {
        FlightTools.Flight flight = new FlightTools.Flight("MU5402", "东航", "08:00", "10:30", "双流", "萧山", null,
                Map.of("飞猪", new FlightTools.PlatformPrice(620, "https://example.com", 50)), 620, "飞猪", 150, null,
                false, false, "TFU", "HGH", null);
        FlightBook.Entry entry = new FlightBook.Entry("成都", "杭州", "2026-11-20", flight, "2026-11-04T10:00:00+08:00");
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(entry);

        assertThat(json).doesNotContain("quick");
        FlightBook.Entry back = mapper.readValue(json, FlightBook.Entry.class);
        assertThat(back).isEqualTo(entry);
        assertThat(back.quick()).isTrue();
    }
}
