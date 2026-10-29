package com.lifeassistant.skill.travel.hotel;

import java.time.LocalDate;
import java.util.List;

/** 一个平台的酒店查价来源（携程、美团、飞猪……）。每个平台的价格口径不同，结果里要带上口径说明。 */
public interface HotelPriceSource {

    /** 平台名，如"携程" */
    String platform();

    /** 是否已配置、可以调用 */
    boolean available();

    /** 查一家酒店某个日期的价格；查不到或出错时返回 status 不是 OK 的结果，不抛异常。 */
    Quote quote(String hotel, String city, LocalDate checkIn, LocalDate checkOut);

    /**
     * 一个平台的报价。price 是每晚最低价（元），拿不到为 null；basis 说明价格口径；
     * rooms 是部分房型价格（有就给）；matchedName 是平台上实际匹配到的酒店名，和查询名对不上时要提醒用户。
     */
    record Quote(String platform, String status, Integer price, String basis, String url, String matchedName,
                 List<String> rooms, String note) {

        static Quote ok(String platform, Integer price, String basis, String url, String matchedName,
                        List<String> rooms, String note) {
            return new Quote(platform, price == null ? "NO_PRICE" : "OK", price, basis, url, matchedName, rooms, note);
        }

        static Quote failed(String platform, String reason) {
            return new Quote(platform, "FAILED", null, null, null, null, List.of(), reason);
        }
    }
}
