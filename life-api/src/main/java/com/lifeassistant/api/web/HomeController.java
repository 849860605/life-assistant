package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.skill.travel.today.TodayAgenda;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 首页接口：今日安排（日程加进行中旅行当天的安排，带导航坐标和建议出发时间），以及算出发时间用的家庭地址。
 */
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class HomeController {

    private final AuthFacade authFacade;
    private final TodayAgenda todayAgenda;

    /** 家庭地址的请求体：address 为空表示删掉。 */
    public record AddressBody(String address) {
    }

    /** GET /api/v1/home/today?city=深圳：今日安排；city 是页面当前城市，用来找日程地点的坐标。 */
    @GetMapping("/today")
    public ApiResponse<TodayAgenda.Agenda> today(@RequestParam(required = false) String city) {
        return ApiResponse.ok(todayAgenda.today(authFacade.current(), city));
    }

    /** GET /api/v1/home/address：家庭地址，没设时为空。 */
    @GetMapping("/address")
    public ApiResponse<Map<String, String>> address() {
        Map<String, String> body = new HashMap<>();
        body.put("address", todayAgenda.homeAddress(authFacade.current()));
        return ApiResponse.ok(body);
    }

    /** PUT /api/v1/home/address：设置家庭地址（高德找得到才存），空字符串表示删掉。 */
    @PutMapping("/address")
    public ApiResponse<Map<String, String>> saveAddress(@RequestBody AddressBody body) {
        Map<String, String> out = new HashMap<>();
        out.put("address", todayAgenda.saveHome(authFacade.current(), body == null ? null : body.address()));
        return ApiResponse.ok(out);
    }
}
