package com.lifeassistant.skill.travel.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 模型给的景点简介清理：去引号标题，"无"和太短的当没有。 */
class SpotIntroCleanTest {

    @Test
    void stripsQuotesAndHeadings() {
        assertThat(SpotIntroService.clean("“岳麓书院是中国古代四大书院之一，院内保留讲堂、御书楼等建筑，适合慢慢逛。”"))
                .isEqualTo("岳麓书院是中国古代四大书院之一，院内保留讲堂、御书楼等建筑，适合慢慢逛。");
        assertThat(SpotIntroService.clean("## 岳麓书院是中国古代四大书院之一，适合慢慢逛。")).startsWith("岳麓书院");
    }

    @Test
    void noneOrTooShortIsEmpty() {
        assertThat(SpotIntroService.clean("无")).isNull();
        assertThat(SpotIntroService.clean("无。资料不足")).isNull();
        assertThat(SpotIntroService.clean("一个亭子")).isNull();
        assertThat(SpotIntroService.clean(null)).isNull();
    }
}
