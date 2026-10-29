package com.lifeassistant.skill.travel.hotel;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HotelLinkToolsTest {

    @Test
    void buildsAllLinksWithCoordinatesAndDates() {
        Map<String, String> links = HotelLinkTools.links("泰山宾馆", "泰安", 117.1234567, 36.2, LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 11, 1));

        assertThat(links.get("高德")).isEqualTo("https://uri.amap.com/marker?position=117.123457,36.200000"
                + "&name=%E6%B3%B0%E5%B1%B1%E5%AE%BE%E9%A6%86&src=life-assistant&coordinate=gaode&callnative=1");
        assertThat(links.get("携程")).isEqualTo("https://hotels.ctrip.com/hotels/list?keyword="
                + "%E6%B3%B0%E5%AE%89%20%E6%B3%B0%E5%B1%B1%E5%AE%BE%E9%A6%86&checkin=2026/10/31&checkout=2026/11/01");
        assertThat(links.get("美团")).isEqualTo("https://i.meituan.com/s/%E6%B3%B0%E5%AE%89%20%E6%B3%B0%E5%B1%B1%E5%AE%BE%E9%A6%86");
    }

    @Test
    void skipsAmapWithoutCoordinatesAndCityAlreadyInName() {
        Map<String, String> links = HotelLinkTools.links("泰安宝龙福朋喜来登酒店", "泰安", null, null, null, null);

        assertThat(links).doesNotContainKey("高德");
        // 名称里已经有城市，不再重复加；没有日期就不带日期参数
        assertThat(links.get("携程")).endsWith("keyword=%E6%B3%B0%E5%AE%89%E5%AE%9D%E9%BE%99%E7%A6%8F%E6%9C%8B%E5%96%9C%E6%9D%A5%E7%99%BB%E9%85%92%E5%BA%97");
    }
}
