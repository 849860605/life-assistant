package com.lifeassistant.skill.travel.flight;

import java.time.LocalDate;
import java.util.List;

/** 一个平台的机票查询来源（飞猪、携程、美团……），按出发地、目的地、日期查单程航班和价格。 */
public interface FlightPriceSource {

    String platform();

    boolean available();

    /** 查单程航班；出错时返回 status=FAILED 的结果，不抛异常。 */
    Result search(String origin, String destination, LocalDate date);

    /**
     * 一个航班报价。flightNo 是航班号（中转用 / 连接），depTime、arrTime 为 HH:mm，
     * depAirport、arrAirport 是平台给的机场写法，price 是元，url 是该平台的预订链接。
     */
    record Offer(String flightNo, String airline, String depTime, String arrTime, String depAirport,
                 String arrAirport, Integer price, String url, String note) {
    }

    /** 一个平台的查询结果：status 为 OK / EMPTY / FAILED。 */
    record Result(String platform, String status, List<Offer> offers, String note) {

        static Result of(String platform, List<Offer> offers, String note) {
            return new Result(platform, offers.isEmpty() ? "EMPTY" : "OK", offers, note);
        }

        static Result failed(String platform, String reason) {
            return new Result(platform, "FAILED", List.of(), reason);
        }
    }
}
