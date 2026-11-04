package com.lifeassistant.skill.travel.hotel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 快速选酒店的排序：有价格且不超预算的在前，再看高德评分和要求匹配，同分便宜的在前。 */
class QuickHotelRankTest {

    private static HotelPriceTools.QuickHotel hotel(String name, Integer price, Double rating, String... tags) {
        return new HotelPriceTools.QuickHotel(name, price, null, "4", 500, List.of(tags), rating);
    }

    @Test
    void budgetFirstThenRating() {
        List<HotelPriceTools.QuickHotel> ranked = HotelPriceTools.rankQuick(List.of(
                hotel("贵的", 900, 4.9), hotel("没价格", null, 4.8), hotel("一般", 300, 4.3), hotel("好的", 400, 4.7)),
                null, 500.0);
        assertThat(ranked).extracting(HotelPriceTools.QuickHotel::name).containsExactly("好的", "一般", "贵的", "没价格");
    }

    @Test
    void needsMatchingTagsBreakTies() {
        List<HotelPriceTools.QuickHotel> ranked = HotelPriceTools.rankQuick(List.of(
                hotel("普通", 300, 4.5), hotel("有早餐", 350, 4.5, "含早餐", "近地铁")), "早餐、安静", null);
        assertThat(ranked.get(0).name()).isEqualTo("有早餐");
    }
}
