package com.lifeassistant.skill.travel.trip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 行程里的链接只收常用平台的 https 地址。 */
class BookingLinksTest {

    @Test
    void keepsHttpsLinksOfAllowedPlatformsAndSubdomains() {
        assertThat(BookingLinks.safe(" https://www.dianping.com/shop/1 ")).isEqualTo("https://www.dianping.com/shop/1");
        assertThat(BookingLinks.safe("https://dpurl.cn/abc")).isEqualTo("https://dpurl.cn/abc");
        // 美团给的点评短链是 http 的
        assertThat(BookingLinks.safe("http://dpurl.cn/abc")).isEqualTo("http://dpurl.cn/abc");
        assertThat(BookingLinks.safe("https://hotels.CTRIP.com/hotel/1.html")).isNotNull();
        assertThat(BookingLinks.safe("https://uri.amap.com/marker?position=1,2")).isNotNull();
    }

    @Test
    void dropsEverythingElse() {
        assertThat(BookingLinks.safe(null)).isNull();
        assertThat(BookingLinks.safe("http://www.dianping.com/shop/1")).isNull();
        assertThat(BookingLinks.safe("http://x.dpurl.cn/abc")).isNull();
        assertThat(BookingLinks.safe("ftp://dpurl.cn/abc")).isNull();
        assertThat(BookingLinks.safe("javascript:alert(1)")).isNull();
        assertThat(BookingLinks.safe("https://evil.com/dianping.com")).isNull();
        assertThat(BookingLinks.safe("https://dianping.com.evil.com/x")).isNull();
        assertThat(BookingLinks.safe("https://evildianping.com/x")).isNull();
        assertThat(BookingLinks.safe("https://www.dianping.com@evil.com/x")).isNull();
        assertThat(BookingLinks.safe("https://a.com/ x")).isNull();
    }
}
