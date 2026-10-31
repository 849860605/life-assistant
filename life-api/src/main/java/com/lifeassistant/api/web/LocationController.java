package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.place.AmapPlaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定位接口：页面拿到浏览器的 GPS 坐标后，换成高德坐标并查出所在城市。位置不保存、不记日志。
 */
@RestController
@RequestMapping("/api/v1/location")
@RequiredArgsConstructor
public class LocationController {

    private final AmapPlaceService places;

    /** GET /api/v1/location/resolve?lng=&lat=（浏览器给的 WGS-84 坐标）：高德坐标、城市、区县、地址。 */
    @GetMapping("/resolve")
    public ApiResponse<AmapPlaceService.Located> resolve(@RequestParam double lng, @RequestParam double lat) {
        // 高德只覆盖国内，大致按中国的经纬度范围拦一下
        if (lng < 73 || lng > 136 || lat < 3 || lat > 54) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前位置不在国内，附近功能只支持国内");
        }
        return ApiResponse.ok(places.locate(lng, lat));
    }
}
