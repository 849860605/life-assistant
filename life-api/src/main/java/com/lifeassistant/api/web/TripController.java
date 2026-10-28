package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.skill.travel.trip.Trip;
import com.lifeassistant.skill.travel.trip.TripService;
import com.lifeassistant.skill.travel.trip.TripVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行程查询接口：行程列表、详情和历史版本。行程由旅行技能在对话中生成，这里只读。
 */
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {

    private final AuthFacade authFacade;
    private final TripService tripService;

    /** GET /api/v1/trips：按更新时间倒序列出自己的行程。 */
    @GetMapping
    public ApiResponse<List<Trip>> list() {
        return ApiResponse.ok(tripService.list(authFacade.current()));
    }

    /** GET /api/v1/trips/{id}：行程详情，不传 version 时返回当前版本。 */
    @GetMapping("/{id}")
    public ApiResponse<TripService.TripDetail> get(@PathVariable long id,
                                                   @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(tripService.get(authFacade.current(), id, version));
    }

    /** GET /api/v1/trips/{id}/versions：行程的全部历史版本。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<TripVersion>> versions(@PathVariable long id) {
        return ApiResponse.ok(tripService.versions(authFacade.current(), id));
    }
}
