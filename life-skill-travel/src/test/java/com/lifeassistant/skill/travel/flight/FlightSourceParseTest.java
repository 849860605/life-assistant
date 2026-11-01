package com.lifeassistant.skill.travel.flight;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 用三个平台真实返回的机票数据（2026-10-29 抓的深圳→北京 11-05 样本）验证解析和合并。 */
class FlightSourceParseTest {

    @Test
    void fliggyStructuredFlights() throws IOException {
        FlightPriceSource.Result result = FliggyFlightSource.parse(new ObjectMapper().readTree(read("fliggy.json")));

        assertThat(result.status()).isEqualTo("OK");
        FlightPriceSource.Offer first = result.offers().getFirst();
        assertThat(first.flightNo()).isEqualTo("CZ3171");
        assertThat(first.depTime()).isEqualTo("07:30");
        assertThat(first.arrTime()).isEqualTo("10:45");
        assertThat(first.depAirport()).isEqualTo("宝安T3");
        assertThat(first.arrAirport()).isEqualTo("大兴");
        assertThat(first.price()).isEqualTo(600);
        assertThat(first.url()).startsWith("https://router.feizhu.com/");
        // "新海航|海南航空" 只保留后半
        assertThat(result.offers()).anySatisfy(o -> assertThat(o.airline()).isEqualTo("海南航空"));
    }

    @Test
    void ctripFlightsFromMarkdown() throws IOException {
        FlightPriceSource.Result result = CtripFlightSource.parse(read("ctrip.md"));

        assertThat(result.offers()).extracting(FlightPriceSource.Offer::flightNo)
                .containsExactly("ZH9107", "CA1384", "CA1314", "ZH9109", "MU6662");
        FlightPriceSource.Offer mu = result.offers().getLast();
        assertThat(mu.depTime()).isEqualTo("07:20");
        assertThat(mu.arrAirport()).isEqualTo("北京 大兴");
        assertThat(mu.price()).isEqualTo(650);
        assertThat(mu.airline()).isEqualTo("东航");
        assertThat(mu.url()).contains("dfltno=MU6662");
    }

    @Test
    void meituanFlightsFromMarkdown() throws IOException {
        FlightPriceSource.Result result = MeituanFlightSource.parse(read("meituan.md"));

        assertThat(result.offers()).isNotEmpty();
        FlightPriceSource.Offer cz = result.offers().getFirst();
        assertThat(cz.flightNo()).isEqualTo("CZ3171");
        assertThat(cz.airline()).isEqualTo("南方航空");
        assertThat(cz.depAirport()).isEqualTo("深圳·宝安");
        assertThat(cz.arrTime()).isEqualTo("10:45");
        assertThat(cz.price()).isEqualTo(582);
        assertThat(cz.url()).isEqualTo("http://dpurl.cn/ss4XQElz");
    }

    @Test
    void mergeByFlightNumberAndPickCheapest() throws IOException {
        List<FlightTools.Flight> flights = FlightTools.merge(List.of(
                FliggyFlightSource.parse(new ObjectMapper().readTree(read("fliggy.json"))),
                CtripFlightSource.parse(read("ctrip.md")),
                MeituanFlightSource.parse(read("meituan.md"))));

        FlightTools.Flight cz = flights.stream().filter(f -> f.flightNo().equals("CZ3171")).findFirst().orElseThrow();
        assertThat(cz.prices()).containsKeys("飞猪", "美团");
        assertThat(cz.lowest()).isEqualTo(582);
        assertThat(cz.lowestPlatform()).isEqualTo("美团");
        // 按最低价从低到高排
        assertThat(flights).extracting(FlightTools.Flight::lowest).isSorted();
    }

    @Test
    void durationAndAircraft() throws IOException {
        assertThat(FlightPriceSource.minutes("2h40m")).isEqualTo(160);
        assertThat(FlightPriceSource.minutes("45m")).isEqualTo(45);
        assertThat(FlightPriceSource.minutes("3h")).isEqualTo(180);
        assertThat(FlightPriceSource.minutes("经济舱")).isNull();
        FlightPriceSource.Offer meituan = MeituanFlightSource.parse(read("meituan.md")).offers().getFirst();
        assertThat(meituan.minutes()).isEqualTo(195);
    }

    @Test
    void ctripRulesFromAnswer() {
        String text = """
                为您查到<MU2335>经济舱的免费托运行李额为**20KG**，退改签具体费率因购票舱位等级而异，需以购票时展示规则为准。

                ### <MU2335>
                - **航班信息**：上海虹桥T2 07:15起飞，西安咸阳T5 09:40到达，飞行2小时25分
                - **机型**：C919，提供正餐
                - **经济舱票价**：￥500起
                - **免费托运行李额**：20KG，尺寸限制40×60×100cm
                - **随身行李额**：1件，限重8KG，尺寸限制20×40×55cm
                - **退改签规则**：退改费率随距起飞时间递增，具体费率以购票页面公示为准
                """;
        FlightRules.Rules rules = FlightRules.parse("MU2335", "2026-11-20", text);

        assertThat(rules.items()).containsOnlyKeys("机型", "免费托运行李额", "随身行李额", "退改签规则");
        assertThat(rules.items().get("免费托运行李额")).startsWith("20KG");
        assertThat(rules.summary()).startsWith("为您查到MU2335经济舱的免费托运行李额为20KG");
    }

    private String read(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/flight/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
