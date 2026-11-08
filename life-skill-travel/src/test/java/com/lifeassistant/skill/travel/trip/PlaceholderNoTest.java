package com.lifeassistant.skill.travel.trip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 占位车次、航班号能认出来，真实班次和"车次待定"不误伤。 */
class PlaceholderNoTest {

    @Test
    void detectsPlaceholders() {
        for (String s : new String[]{"高铁 Gxxxx 深圳北 → 广州南", "动车 Dxxx", "MUxxxx 上海 → 北京", "航班 XX1234", "高铁 G×××× 出发"}) {
            assertThat(TripTools.PLACEHOLDER_NO.matcher(s).find()).as(s).isTrue();
        }
        for (String s : new String[]{"高铁 G1102 深圳北 → 广州南", "东航 MU5402 双流T2 → 萧山T3", "高铁（车次待定）", "航班待定", "Xiamen 鼓浪屿"}) {
            assertThat(TripTools.PLACEHOLDER_NO.matcher(s).find()).as(s).isFalse();
        }
    }
}
