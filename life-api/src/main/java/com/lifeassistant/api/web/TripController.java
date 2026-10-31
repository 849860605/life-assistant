package com.lifeassistant.api.web;

import com.lifeassistant.api.auth.MediaAuth;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.schedule.ScheduleEvent;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.skill.travel.trip.Trip;
import com.lifeassistant.skill.travel.trip.TripEditor;
import com.lifeassistant.skill.travel.trip.TripPdf;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.lifeassistant.skill.travel.trip.TripService;
import com.lifeassistant.skill.travel.trip.TripVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 行程接口：列表、详情、历史版本、关联日程、确认和删除，以及在页面上直接改行程（换一个、删掉、保留、调顺序、加入、换酒店、重新比价），导出 PDF。
 * 行程由出游技能在对话中生成。
 */
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {

    private final AuthFacade authFacade;
    private final TripService tripService;
    private final TripEditor tripEditor;
    private final TripPdf tripPdf;
    private final MediaAuth mediaAuth;

    /** 换掉或删掉某项时的请求：换成的地点（删掉时不用）和原因（VISITED 去过了、DISLIKE 不感兴趣，可空）。 */
    public record ItemChange(TripEditor.PlacePick place, TripEditor.Reason reason) {
    }

    /** 调整某天顺序：这一天全部项的 id，按新顺序。 */
    public record Order(List<String> ids) {
    }

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

    /** GET /api/v1/trips/{id}/events：行程关联的日程和提醒。 */
    @GetMapping("/{id}/events")
    public ApiResponse<List<ScheduleEvent>> events(@PathVariable long id) {
        return ApiResponse.ok(tripService.events(authFacade.current(), id));
    }

    /** POST /api/v1/trips/{id}/confirm：确认草稿行程。 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<Trip> confirm(@PathVariable long id) {
        return ApiResponse.ok(tripService.confirm(authFacade.current(), id));
    }

    /** DELETE /api/v1/trips/{id}：删除行程，关联的日程和提醒一起删。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        tripService.delete(authFacade.current(), id);
        return ApiResponse.ok();
    }

    /** POST /api/v1/trips/{id}/geo：当前版本还没补坐标、照片和路程就补上，返回补完的行程。 */
    @PostMapping("/{id}/geo")
    public ApiResponse<TripService.TripDetail> geo(@PathVariable long id) {
        return ApiResponse.ok(tripEditor.ensureGeo(authFacade.current(), id));
    }

    /** GET /api/v1/trips/{id}/items/{itemId}/alternatives：某项的候选地点（附近同类、评分高的）。 */
    @GetMapping("/{id}/items/{itemId}/alternatives")
    public ApiResponse<List<AmapPlaceService.Place>> alternatives(@PathVariable long id, @PathVariable String itemId,
                                                                  @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(tripEditor.alternatives(authFacade.current(), id, itemId, keyword));
    }

    /** POST /api/v1/trips/{id}/items/{itemId}/replace：把某项换成选中的地点，存新版本。 */
    @PostMapping("/{id}/items/{itemId}/replace")
    public ApiResponse<TripService.TripDetail> replace(@PathVariable long id, @PathVariable String itemId,
                                                       @RequestBody ItemChange change) {
        return ApiResponse.ok(tripEditor.replace(authFacade.current(), id, itemId, change.place(), change.reason()));
    }

    /** POST /api/v1/trips/{id}/items/{itemId}/remove：删掉某项，存新版本。 */
    @PostMapping("/{id}/items/{itemId}/remove")
    public ApiResponse<TripService.TripDetail> remove(@PathVariable long id, @PathVariable String itemId,
                                                      @RequestBody(required = false) ItemChange change) {
        return ApiResponse.ok(tripEditor.remove(authFacade.current(), id, itemId, change == null ? null : change.reason()));
    }

    /** POST /api/v1/trips/{id}/items/{itemId}/lock?locked=true：标记保留或取消。 */
    @PostMapping("/{id}/items/{itemId}/lock")
    public ApiResponse<TripService.TripDetail> lock(@PathVariable long id, @PathVariable String itemId,
                                                    @RequestParam boolean locked) {
        return ApiResponse.ok(tripEditor.lock(authFacade.current(), id, itemId, locked));
    }

    /** POST /api/v1/trips/{id}/booked/{targetId}?booked=true：把某项安排或某段住宿标记为已预约（或取消标记）。 */
    @PostMapping("/{id}/booked/{targetId}")
    public ApiResponse<TripService.TripDetail> booked(@PathVariable long id, @PathVariable String targetId,
                                                      @RequestParam boolean booked) {
        return ApiResponse.ok(tripEditor.markBooked(authFacade.current(), id, targetId, booked));
    }

    /** PUT /api/v1/trips/{id}/days/{day}/order：调整第 day 天（从 1 开始）的顺序，存新版本。 */
    @PutMapping("/{id}/days/{day}/order")
    public ApiResponse<TripService.TripDetail> reorder(@PathVariable long id, @PathVariable int day,
                                                       @RequestBody Order order) {
        return ApiResponse.ok(tripEditor.reorder(authFacade.current(), id, day - 1, order.ids()));
    }

    /** POST /api/v1/trips/{id}/days/{day}/items：把一个地点加进第 day 天，存新版本。 */
    @PostMapping("/{id}/days/{day}/items")
    public ApiResponse<TripService.TripDetail> add(@PathVariable long id, @PathVariable int day,
                                                   @RequestBody TripEditor.PlacePick place) {
        return ApiResponse.ok(tripEditor.add(authFacade.current(), id, day - 1, place));
    }

    /** 换酒店：酒店名（比价候选里的一家，或用户自己说的）。 */
    public record StayChange(String name) {
    }

    /** POST /api/v1/trips/{id}/stays/{stayId}/switch：换一家酒店，存新版本。 */
    @PostMapping("/{id}/stays/{stayId}/switch")
    public ApiResponse<TripService.TripDetail> switchStay(@PathVariable long id, @PathVariable String stayId,
                                                          @RequestBody StayChange change) {
        return ApiResponse.ok(tripEditor.switchStay(authFacade.current(), id, stayId, change.name()));
    }

    /** POST /api/v1/trips/{id}/stays/{stayId}/requote：当前酒店和候选重新比价（十几秒到一分钟）。 */
    @PostMapping("/{id}/stays/{stayId}/requote")
    public ApiResponse<TripService.TripDetail> requote(@PathVariable long id, @PathVariable String stayId) {
        return ApiResponse.ok(tripEditor.requote(authFacade.current(), id, stayId));
    }

    /** GET /api/v1/trips/{id}/pdf?mt=票据：导出 PDF（不传 version 时导出当前版本），作为附件下载。新标签页带不了请求头，用下载票据。 */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id, @RequestParam(required = false) Integer version,
                                      @RequestParam(required = false) String mt) {
        TripService.TripDetail detail = tripService.get(mediaAuth.user(mt), id, version);
        String name = (detail.trip().getTitle() == null ? "行程" : detail.trip().getTitle()).replaceAll("[\\\\/:*?\"<>|]", "_") + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .body(tripPdf.render(detail));
    }

    /** GET /api/v1/trips/{id}/versions：行程的全部历史版本。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<TripVersion>> versions(@PathVariable long id) {
        return ApiResponse.ok(tripService.versions(authFacade.current(), id));
    }
}
