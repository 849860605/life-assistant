package com.lifeassistant.skill.travel.hotel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 美团评价回答的解析（样本取自真实返回）和口碑分的加权。 */
class HotelReviewsTest {

    @Test
    void parsesScoreCountAndHighlight() {
        String md = """
                小团帮你查到啦，**维也纳国际酒店（西安高新科技路地铁站店）**的整体口碑非常不错，评分高达**4.8分**，累计已有**5817条**住客评价，深受大家认可哦！

                **评分与评价**：酒店综合评分为**4.8分**，累计评价数达**5817条**，属于高口碑的优质酒店。
                **住客评价亮点**：住客普遍反馈酒店**交通便利**（紧邻科技路地铁站）、**环境安静**且**房间干净整洁**。
                """;
        HotelReviews.Parsed p = HotelReviews.parse(md, "维也纳国际酒店(西安高新科技路地铁站店)");
        assertThat(p).isNotNull();
        assertThat(p.score()).isEqualTo(4.8);
        assertThat(p.count()).isEqualTo(5817);
        assertThat(p.highlight()).startsWith("住客普遍反馈酒店交通便利");
    }

    @Test
    void parsesCompactFormat() {
        String md = """
                **美豪丽致酒店（西安高新区南二环丰庆公园地铁站店）**

                ⭐ **档次/评价**：高档型/四星级 | 4.8分，超棒 / 2014条评价（住客普遍称赞位置便利和智能设施）
                """;
        HotelReviews.Parsed p = HotelReviews.parse(md, "美豪丽致酒店(西安高新区南二环丰庆公园地铁站店)");
        assertThat(p).isNotNull();
        assertThat(p.score()).isEqualTo(4.8);
        assertThat(p.count()).isEqualTo(2014);
        assertThat(p.highlight()).isEqualTo("住客普遍称赞位置便利和智能设施");
    }

    @Test
    void stripsListMarkerFromHighlight() {
        String md = """
                **西安钟楼北大街希尔顿欢朋酒店**的评分高达**4.9分**，累计有**1975条**住客评价
                **住客评价亮点**：
                - **设施与卫生**：房间设施齐全且较新，采光好无异味，隔音效果不错。
                """;
        assertThat(HotelReviews.parse(md, "西安钟楼北大街希尔顿欢朋酒店").highlight()).startsWith("设施与卫生：房间设施齐全");
    }

    @Test
    void ignoresOtherBranchWhenNotFound() {
        String md = """
                小团帮你仔细查询了，目前在平台上**暂未找到"全季酒店(西安大唐西市店)"的相关信息**，可能是该门店已更名哦～
                [**全季酒店\\(西安金光门地铁站店\\)**](http://dpurl.cn/x) 美团高档型 **美团真实评分4.7**
                """;
        assertThat(HotelReviews.parse(md, "全季酒店(西安大唐西市店)")).isNull();
    }

    @Test
    void acceptsNameWithoutBranch() {
        String md = "小团为你查到啦，**西安城墙希尔顿酒店**的整体评分高达**4.6分**，累计已有**3970条**住客评价";
        HotelReviews.Parsed p = HotelReviews.parse(md, "西安城墙希尔顿酒店(钟鼓楼店)");
        assertThat(p).isNotNull();
        assertThat(p.count()).isEqualTo(3970);
    }

    @Test
    void rejectsOtherBranchOfSameBrand() {
        String md = "小团为你查到啦，**康铂酒店（西安钟鼓楼回民街地铁站店）**的评分为**4.7分**，累计评价条数为**1012条**";
        assertThat(HotelReviews.parse(md, "锦江|康铂酒店(西安钟楼店)")).isNull();
    }

    @Test
    void ignoresAnswerAboutAnotherHotel() {
        String md = "**全季酒店（西安金光门地铁站店）** 美团真实评分4.7，1200条评价";
        assertThat(HotelReviews.parse(md, "全季酒店(西安大唐西市店)")).isNull();
    }

    @Test
    void manyReviewsOutweighFewHigherOnes() {
        HotelReviews.Review many = HotelReviews.combine(4.8, 5817, null, 4.5);
        HotelReviews.Review few = HotelReviews.combine(4.9, 20, null, null);
        assertThat(many.score()).isGreaterThan(few.score());
        assertThat(many.sources()).isEqualTo("美团 4.8（5817 条评价） · 高德 4.5");
        assertThat(HotelReviews.combine(null, null, null, null).score()).isNull();
    }
}
