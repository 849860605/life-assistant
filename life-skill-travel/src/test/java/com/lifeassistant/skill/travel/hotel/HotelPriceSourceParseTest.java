package com.lifeassistant.skill.travel.hotel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 用三个平台真实返回的内容（2026-10-29 抓的样本，存在 test/resources/hotel）验证解析。 */
class HotelPriceSourceParseTest {

    @Test
    void ctripWendaoLowestPriceAndLink() throws IOException {
        HotelPriceSource.Quote quote = CtripWendaoPriceSource.parse(read("ctrip-wendao.md"));

        assertThat(quote.status()).isEqualTo("OK");
        assertThat(quote.price()).isEqualTo(809);
        assertThat(quote.url()).startsWith("https://m.ctrip.com/webapp/hotels/detail?").contains("hotelid=127627368");
        assertThat(quote.matchedName()).isEqualTo("深圳湾万象城逸扉酒店");
    }

    @Test
    void ctripWendaoOtherPriceWording() {
        String text = "该酒店起步参考价约为809元/晚，实际价格会波动。\n- [预定链接](<https://m.ctrip.com/webapp/hotels/detail?hotelid=1>)";
        HotelPriceSource.Quote quote = CtripWendaoPriceSource.parse(text);
        assertThat(quote.price()).isEqualTo(809);
        assertThat(quote.url()).isEqualTo("https://m.ctrip.com/webapp/hotels/detail?hotelid=1");
    }

    @Test
    void ctripDatesInChinese() {
        assertThat(CtripWendaoPriceSource.dates(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31)))
                .isEqualTo("2026年10月30日入住、2026年10月31日离店");
    }

    @Test
    void meituanRoomPricesAndLowest() throws IOException {
        HotelPriceSource.Quote quote = MeituanPriceSource.parse(read("meituan.md"));

        assertThat(quote.status()).isEqualTo("OK");
        // "约834元起" 比房型价 866 低，取 834
        assertThat(quote.price()).isEqualTo(834);
        assertThat(quote.rooms()).contains("逸扉城景大床房 ￥866", "逸扉豪华城景大床房 ￥886", "行政城景双床房 ￥992");
        assertThat(quote.url()).isEqualTo("http://dpurl.cn/rAE6Fhtz");
        assertThat(quote.matchedName()).isEqualTo("深圳湾万象城逸扉酒店");
    }

    @Test
    void fliggyPicksSameNameHotel() throws IOException {
        HotelPriceSource.Quote quote = FliggyPriceSource.parse(new ObjectMapper().readTree(read("fliggy.json")),
                "深圳湾万象城逸扉酒店");

        assertThat(quote.price()).isEqualTo(742);
        assertThat(quote.matchedName()).isEqualTo("深圳湾万象城逸扉酒店");
        assertThat(quote.url()).startsWith("https://router.feizhu.com/");
        assertThat(quote.note()).isNull();
    }

    @Test
    void fliggyWarnsWhenNameDoesNotMatch() throws IOException {
        HotelPriceSource.Quote quote = FliggyPriceSource.parse(new ObjectMapper().readTree(read("fliggy.json")),
                "不存在的酒店");
        assertThat(quote.note()).contains("名字可能不一致");
    }

    private String read(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/hotel/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
