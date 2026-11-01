package com.lifeassistant.skill.travel.flight;

import java.time.LocalDate;
import java.util.List;

/** 一个平台的机票查询来源（飞猪、携程、美团……），按出发地、目的地、日期查单程航班和价格。 */
public interface FlightPriceSource {

    String platform();

    boolean available();

    /** 查单程经济舱航班；出错时返回 status=FAILED 的结果，不抛异常。 */
    Result search(String origin, String destination, LocalDate date);

    /** 按舱位查（ECONOMY、BUSINESS、FIRST）；默认只支持经济舱，别的舱位返回没查。 */
    default Result search(String origin, String destination, LocalDate date, String cabin) {
        if (cabin == null || cabin.isBlank() || "ECONOMY".equalsIgnoreCase(cabin)) {
            return search(origin, destination, date);
        }
        return Result.failed(platform(), "这个平台只查经济舱");
    }

    /**
     * 一个航班报价。flightNo 是航班号（中转用 / 连接），depTime、arrTime 为 HH:mm，
     * depAirport、arrAirport 是平台给的机场写法，price 是元，url 是该平台的预订链接；
     * minutes 是全程分钟数、aircraft 是机型，depCode、arrCode 是机场三字码，via 是中转城市，tax 是另付的税费（元，
     * 途牛把票面价和税费分开给），平台没给就为空。
     */
    record Offer(String flightNo, String airline, String depTime, String arrTime, String depAirport,
                 String arrAirport, Integer price, String url, String note, Integer minutes, String aircraft,
                 String depCode, String arrCode, String via, Integer tax) {

        Offer(String flightNo, String airline, String depTime, String arrTime, String depAirport,
              String arrAirport, Integer price, String url, String note, Integer minutes, String aircraft,
              String depCode, String arrCode, String via) {
            this(flightNo, airline, depTime, arrTime, depAirport, arrAirport, price, url, note, minutes, aircraft,
                    depCode, arrCode, via, null);
        }

        Offer(String flightNo, String airline, String depTime, String arrTime, String depAirport,
              String arrAirport, Integer price, String url, String note, Integer minutes, String aircraft) {
            this(flightNo, airline, depTime, arrTime, depAirport, arrAirport, price, url, note, minutes, aircraft,
                    null, null, null, null);
        }

        Offer(String flightNo, String airline, String depTime, String arrTime, String depAirport,
              String arrAirport, Integer price, String url, String note) {
            this(flightNo, airline, depTime, arrTime, depAirport, arrAirport, price, url, note, null, null);
        }
    }

    /** "2h40m""3h""45m" 转成分钟，认不出返回空。 */
    static Integer minutes(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:(\\d+)\\s*[hH小时]+)?\\s*(?:(\\d+)\\s*(?:m|分钟?))?").matcher(text.trim());
        if (!m.find() || m.group(1) == null && m.group(2) == null) {
            return null;
        }
        return (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 60) + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
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
