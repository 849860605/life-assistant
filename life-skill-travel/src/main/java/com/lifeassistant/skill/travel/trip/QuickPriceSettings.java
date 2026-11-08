package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.setting.AppSettingService;
import com.lifeassistant.skill.travel.flight.FlightPriceSource;
import com.lifeassistant.skill.travel.hotel.HotelPriceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 规划行程时"快速查价"用哪家平台（后台可改）：机票按航线只查这一家；酒店候选由 RollingGo 按片区搜，
 * 参考价用 RollingGo 自带的，或者换成另一家平台对排在前面的几家并行报价。其他平台的价格在行程保存后后台补。
 */
@Component
@RequiredArgsConstructor
public class QuickPriceSettings {

    public static final String FLIGHT_KEY = "quick.flight.platform";
    public static final String HOTEL_KEY = "quick.hotel.platform";
    public static final String ROLLINGGO = "RollingGo";
    private static final String DEFAULT_FLIGHT = "飞猪";

    private final AppSettingService settings;
    private final List<FlightPriceSource> flightSources;
    private final List<HotelPriceSource> hotelSources;

    /** 当前设置和可选的平台（available 表示已配置好、能用）。 */
    public record View(String flight, String hotel, List<Option> flightOptions, List<Option> hotelOptions) {
    }

    /** 一个可选平台。 */
    public record Option(String platform, boolean available) {
    }

    public View view() {
        List<Option> hotels = new ArrayList<>(List.of(new Option(ROLLINGGO, true)));
        hotelSources.forEach(s -> hotels.add(new Option(s.platform(), s.available())));
        return new View(flightPlatform(), hotelPlatform(),
                flightSources.stream().map(s -> new Option(s.platform(), s.available())).toList(), hotels);
    }

    /** 机票快速查价的平台，默认飞猪。 */
    public String flightPlatform() {
        return settings.get(FLIGHT_KEY, DEFAULT_FLIGHT);
    }

    /** 酒店参考价的平台，默认 RollingGo（搜候选时自带价格）。 */
    public String hotelPlatform() {
        return settings.get(HOTEL_KEY, ROLLINGGO);
    }

    /** 改设置：平台要在可选列表里。 */
    public View update(String flight, String hotel) {
        if (flight != null) {
            if (flightSources.stream().noneMatch(s -> s.platform().equals(flight))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "机票没有 " + flight + " 这个平台");
            }
            settings.set(FLIGHT_KEY, flight);
        }
        if (hotel != null) {
            if (!ROLLINGGO.equals(hotel) && hotelSources.stream().noneMatch(s -> s.platform().equals(hotel))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "酒店没有 " + hotel + " 这个平台");
            }
            settings.set(HOTEL_KEY, hotel);
        }
        return view();
    }

    /** 机票后台补价不查的平台：快速查价已经查过的那家，加上额度紧的途牛。 */
    public Set<String> flightSkip() {
        String flight = flightPlatform();
        return "途牛".equals(flight) ? Set.of("途牛") : Set.of(flight, "途牛");
    }

    /** 酒店后台补价不查的平台：额度紧的途牛，加上参考价已经用过的那家。 */
    public Set<String> hotelSkip() {
        String hotel = hotelPlatform();
        return ROLLINGGO.equals(hotel) || "途牛".equals(hotel) ? Set.of("途牛") : Set.of(hotel, "途牛");
    }
}
