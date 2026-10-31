package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.food.FoodTools;
import com.lifeassistant.skill.travel.flight.FlightTools;
import com.lifeassistant.skill.travel.hotel.HotelPriceTools;
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
    private final HotelPriceTools hotelPriceTools;
    private final FoodTools foodTools;

    /** GET /api/v1/search/flights?origin=上海&destination=西安&date=2026-11-20：单程机票多平台比价。 */
    @GetMapping("/flights")
    public ApiResponse<Map<String, Object>> flights(@RequestParam String origin, @RequestParam String destination,
                                                    @RequestParam String date) {
        return ApiResponse.ok(flightTools.compareFlights(origin, destination, date));
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

    /** GET /api/v1/search/restaurants?city=西安&near=钟楼&cuisine=陕菜：大众点评和携程的餐厅合并结果。 */
    @GetMapping("/restaurants")
    public ApiResponse<Map<String, Object>> restaurants(@RequestParam String city,
                                                        @RequestParam(required = false) String near,
                                                        @RequestParam(required = false) String cuisine,
                                                        @RequestParam(required = false) Integer maxPrice) {
        return ApiResponse.ok(foodTools.findRestaurants(city, near, cuisine, maxPrice, null));
    }
}
