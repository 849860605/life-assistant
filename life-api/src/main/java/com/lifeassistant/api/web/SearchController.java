package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.food.FoodTools;
import com.lifeassistant.skill.travel.flight.FlightRules;
import com.lifeassistant.skill.travel.flight.FlightTools;
import com.lifeassistant.skill.travel.flight.TuniuFlightSource;
import com.lifeassistant.skill.travel.hotel.HotelPriceTools;
import com.lifeassistant.skill.travel.hotel.HotelQuoteBook;
import com.lifeassistant.skill.travel.hotel.HotelSearch;
import com.lifeassistant.skill.travel.ticket.TicketRules;
import com.lifeassistant.skill.travel.ticket.TicketSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 比价查询接口：机票、酒店、餐厅页面上直接查，不经过对话。和助手用的是同一套多平台查询，查一次十几秒到一分钟。
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final FlightTools flightTools;
    private final FlightRules flightRules;
    private final TuniuFlightSource tuniuFlights;
    private final HotelPriceTools hotelPriceTools;
    private final HotelSearch hotelSearch;
    private final TicketSearch ticketSearch;
    private final TicketRules ticketRules;
    private final com.lifeassistant.core.cache.QueryCache queryCache;
    private final FoodTools foodTools;

    /** GET /api/v1/search/flights?origin=上海&destination=西安&date=2026-11-20&cabin=ECONOMY：单程机票多平台比价；公务舱、头等舱只查飞猪。 */
    @GetMapping("/flights")
    public ApiResponse<Map<String, Object>> flights(@RequestParam String origin, @RequestParam String destination,
                                                    @RequestParam String date,
                                                    @RequestParam(required = false) String cabin,
                                                    @RequestParam(defaultValue = "false") boolean refresh) {
        String c = cabin == null || !cabin.matches("(?i)ECONOMY|BUSINESS|FIRST") ? null : cabin.toUpperCase();
        return ApiResponse.ok(flightTools.search(origin, destination, date, c, refresh));
    }

    /**
     * GET /api/v1/search/flights/tuniu-cabins?origin=上海&destination=西安&date=2026-11-20&flightNo=MU2335：
     * 途牛上这个航班的各个舱位（价格、税费、购买条件和限制、行李额、餐食），按需查（途牛每天只有 50 次）。
     */
    @GetMapping("/flights/tuniu-cabins")
    public ApiResponse<java.util.List<TuniuFlightSource.Cabin>> tuniuCabins(@RequestParam String origin,
                                                                           @RequestParam String destination,
                                                                           @RequestParam java.time.LocalDate date,
                                                                           @RequestParam String flightNo,
                                                                           @RequestParam(defaultValue = "false") boolean refresh) {
        if (!flightNo.matches("[A-Z0-9]{2}\\d{1,4}")) {
            throw new com.lifeassistant.common.error.BizException(com.lifeassistant.common.error.ErrorCode.BAD_REQUEST, "航班号不对");
        }
        try {
            return ApiResponse.ok(tuniuFlights.cabins(origin, destination, date, flightNo, refresh));
        } catch (IllegalStateException e) {
            throw new com.lifeassistant.common.error.BizException(com.lifeassistant.common.error.ErrorCode.EXTERNAL_SERVICE, e.getMessage());
        }
    }

    /**
     * GET /api/v1/search/flights/calendar?origin=上海&destination=西安&date=2026-11-20：前后 3 天每天的最低价（飞猪），
     * 给价格日历和走势用，约 5 秒。
     */
    @GetMapping("/flights/calendar")
    public ApiResponse<java.util.List<FlightTools.DayPrice>> flightCalendar(@RequestParam String origin,
                                                                           @RequestParam String destination,
                                                                           @RequestParam java.time.LocalDate date,
                                                                           @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(flightTools.calendar(origin, destination, date, 3, refresh));
    }

    /**
     * GET /api/v1/search/flights/rules?flightNo=MU2335&origin=上海&destination=西安&date=2026-11-20：
     * 这个航班的行李额和退改签说明（携程问道，一般规则，以购票页面为准），约十几秒。
     */
    @GetMapping("/flights/rules")
    public ApiResponse<FlightRules.Rules> flightRules(@RequestParam String flightNo, @RequestParam String origin,
                                                     @RequestParam String destination,
                                                     @RequestParam java.time.LocalDate date,
                                                     @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(flightRules.rules(flightNo, origin, destination, date, refresh));
    }

    /**
     * GET /api/v1/search/hotels?city=西安&place=钟楼&checkIn=…&checkOut=…：某地附近的酒店多平台比价；
     * 不填 place 就按整个城市找。
     */
    @GetMapping("/hotels")
    public ApiResponse<Map<String, Object>> hotels(@RequestParam String city,
                                                   @RequestParam(required = false) String place,
                                                   @RequestParam String checkIn, @RequestParam String checkOut,
                                                   @RequestParam(required = false) Double maxPrice) {
        boolean wholeCity = place == null || place.isBlank() || place.equals(city);
        return ApiResponse.ok(hotelPriceTools.findAndCompareHotels(wholeCity ? city : place + " " + city,
                wholeCity ? "城市" : "景点", city, checkIn, checkOut, maxPrice, null, 5));
    }

    /**
     * GET /api/v1/search/hotels/candidates?city=西安&near=钟楼&checkIn=…&checkOut=…&type=hotel&stars=4,5&maxPrice=600&radius=3000：
     * 酒店比价页的候选（飞猪，带这几天的价格、评分、档次、坐标），约 5 秒。
     */
    @GetMapping("/hotels/candidates")
    public ApiResponse<HotelSearch.Result> hotelCandidates(@RequestParam String city,
                                                           @RequestParam(required = false) String near,
                                                           @RequestParam java.time.LocalDate checkIn,
                                                           @RequestParam java.time.LocalDate checkOut,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(required = false) String stars,
                                                           @RequestParam(required = false) Integer maxPrice,
                                                           @RequestParam(required = false) Integer radius,
                                                           @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(hotelSearch.search(city, near, checkIn, checkOut, type, stars, maxPrice, radius, refresh));
    }

    /** GET /api/v1/search/hotels/quote?name=…&city=…&checkIn=…&checkOut=…：一家酒店在各平台的价格，十几秒。 */
    @GetMapping("/hotels/quote")
    public ApiResponse<HotelQuoteBook.Comparison> hotelQuote(@RequestParam String name, @RequestParam String city,
                                                             @RequestParam java.time.LocalDate checkIn,
                                                             @RequestParam java.time.LocalDate checkOut,
                                                             @RequestParam(defaultValue = "false") boolean refresh) {
        // 一家酒店的多平台比价缓存 30 分钟，至少一个平台有价才缓存
        return ApiResponse.ok(queryCache.get("hotel-quote", name + "|" + city + "|" + checkIn + "|" + checkOut,
                java.time.Duration.ofMinutes(30), refresh, HotelQuoteBook.Comparison.class,
                () -> hotelPriceTools.quote(java.util.List.of(name), city, checkIn, checkOut),
                c -> c.hotels().stream().flatMap(h -> h.quotes().stream()).anyMatch(q -> "OK".equals(q.status()))));
    }

    /**
     * GET /api/v1/search/spots?city=西安&keyword=&category=museum：景点（飞猪），带等级、榜单、是否免费、简介、坐标。
     */
    @GetMapping("/spots")
    public ApiResponse<java.util.List<TicketSearch.Spot>> spots(@RequestParam String city,
                                                               @RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) String category,
                                                               @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(ticketSearch.spots(city, keyword, category, refresh));
    }

    /** GET /api/v1/search/spots/rules?city=西安&spot=陕西历史博物馆：预约规则、开放时间（携程问道，按需查，十几秒）。 */
    @GetMapping("/spots/rules")
    public ApiResponse<TicketRules.Rules> spotRules(@RequestParam String city, @RequestParam String spot,
                                                   @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(ticketRules.rules(city, spot, refresh));
    }

    /** GET /api/v1/search/spots/tickets?city=西安&spot=兵马俑：这个景点的门票商品（飞猪），成人价、儿童价按票种挑。 */
    @GetMapping("/spots/tickets")
    public ApiResponse<java.util.List<TicketSearch.Product>> spotTickets(@RequestParam String city,
                                                                        @RequestParam String spot,
                                                                        @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(ticketSearch.tickets(city, spot, refresh));
    }

    /** GET /api/v1/search/restaurants?city=西安&near=钟楼&cuisine=陕菜：大众点评和携程的餐厅合并结果。 */
    @GetMapping("/restaurants")
    public ApiResponse<Map<String, Object>> restaurants(@RequestParam String city,
                                                        @RequestParam(required = false) String near,
                                                        @RequestParam(required = false) String cuisine,
                                                        @RequestParam(required = false) Integer maxPrice) {
        return ApiResponse.ok(foodTools.findRestaurants(city, near, cuisine, maxPrice, null));
    }
}
