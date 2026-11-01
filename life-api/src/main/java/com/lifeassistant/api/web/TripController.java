package com.lifeassistant.api.web;

import com.lifeassistant.api.auth.MediaAuth;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.schedule.ScheduleEvent;
import com.lifeassistant.core.place.AmapPlaceService;
import com.lifeassistant.skill.travel.trip.Trip;
import com.lifeassistant.skill.travel.trip.TripEditor;
import com.lifeassistant.skill.travel.trip.TripFood;
import com.lifeassistant.skill.travel.trip.TripTickets;
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
    private final TripFood tripFood;
    private final TripTickets tripTickets;
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

    /** POST /api/v1/trips/{id}/items/{itemId}/replace/preview：换之前看当天路上时间、里程、打车费怎么变，不保存。 */
    @PostMapping("/{id}/items/{itemId}/replace/preview")
    public ApiResponse<TripEditor.DayPreview> previewReplace(@PathVariable long id, @PathVariable String itemId,
                                                             @RequestBody TripEditor.PlacePick place) {
        return ApiResponse.ok(tripEditor.previewReplace(authFacade.current(), id, itemId, place));
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

    /** POST /api/v1/trips/{id}/done/{itemId}?done=true|false：标记某个安排去过了 / 吃过了。只改当前版本。 */
    @PostMapping("/{id}/done/{itemId}")
    public ApiResponse<TripService.TripDetail> done(@PathVariable long id, @PathVariable String itemId,
                                                    @RequestParam boolean done) {
        return ApiResponse.ok(tripEditor.setDone(authFacade.current(), id, itemId, done));
    }

    /** POST /api/v1/trips/{id}/transport?mode=TRANSIT|DRIVE：整个行程默认坐地铁公交还是打车。只改当前版本。 */
    @PostMapping("/{id}/transport")
    public ApiResponse<TripService.TripDetail> transport(@PathVariable long id, @RequestParam String mode) {
        return ApiResponse.ok(tripEditor.setTransport(authFacade.current(), id, mode));
    }

    /** POST /api/v1/trips/{id}/legs/{from}/{to}?mode=TRANSIT|DRIVE：单独改一段路怎么走。只改当前版本。 */
    @PostMapping("/{id}/legs/{from}/{to}")
    public ApiResponse<TripService.TripDetail> legMode(@PathVariable long id, @PathVariable String from,
                                                       @PathVariable String to, @RequestParam String mode) {
        return ApiResponse.ok(tripEditor.setLegMode(authFacade.current(), id, from, to, mode));
    }

    /**
     * POST /api/v1/trips/{id}/booking/{targetId}?status=PLANNED|OPENED|CONFIRMED：设置预订状态。
     * OPENED 只表示点过预订链接，不算订好；用户自己确认订好才是 CONFIRMED。只改当前版本。
     */
    @PostMapping("/{id}/booking/{targetId}")
    public ApiResponse<TripService.TripDetail> booking(@PathVariable long id, @PathVariable String targetId,
                                                       @RequestParam String status) {
        return ApiResponse.ok(tripEditor.setBooking(authFacade.current(), id, targetId, status));
    }

    /** POST /api/v1/trips/{id}/copy?start=2027-03-10：复制成新的草稿行程，给了 start 就整体平移日期，预订状态清空。 */
    @PostMapping("/{id}/copy")
    public ApiResponse<TripService.TripDetail> copy(@PathVariable long id,
                                                    @RequestParam(required = false) java.time.LocalDate start) {
        return ApiResponse.ok(tripEditor.copy(authFacade.current(), id, start));
    }

    /** POST /api/v1/trips/{id}/versions/{version}/restore：把某个历史版本的内容存成新版本（历史不删）。 */
    @PostMapping("/{id}/versions/{version}/restore")
    public ApiResponse<TripService.TripDetail> restore(@PathVariable long id, @PathVariable int version) {
        return ApiResponse.ok(tripEditor.restore(authFacade.current(), id, version));
    }

    /**
     * GET /api/v1/trips/{id}/days/{day}/optimize?goal=TIME|DISTANCE|COST：预览第 day 天（从 1 开始）的路线优化，不保存。
     * 锁定的、已预约的项不动；路程全部来自高德。
     */
    @GetMapping("/{id}/days/{day}/optimize")
    public ApiResponse<TripEditor.DayPreview> previewOptimize(@PathVariable long id, @PathVariable int day,
                                                              @RequestParam(defaultValue = "TIME") TripEditor.Goal goal) {
        return ApiResponse.ok(tripEditor.previewOptimize(authFacade.current(), id, day - 1, goal));
    }

    /** POST /api/v1/trips/{id}/days/{day}/optimize?goal=：确认优化，ids 是预览里的新顺序，一致才存成新版本。 */
    @PostMapping("/{id}/days/{day}/optimize")
    public ApiResponse<TripService.TripDetail> optimize(@PathVariable long id, @PathVariable int day,
                                                        @RequestParam(defaultValue = "TIME") TripEditor.Goal goal,
                                                        @RequestBody Order order) {
        return ApiResponse.ok(tripEditor.optimize(authFacade.current(), id, day - 1, goal, order.ids()));
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

    /**
     * GET /api/v1/trips/{id}/days/{day}/food?meal=LUNCH&keyword=&maxCost=&radius=&noSpicy=&local=&near=：
     * 第 day 天某一顿顺路的餐厅（高德），按评分和绕路远近排。
     */
    @GetMapping("/{id}/days/{day}/food")
    public ApiResponse<TripFood.FoodResult> food(@PathVariable long id, @PathVariable int day,
                                                 @RequestParam(defaultValue = "LUNCH") TripEditor.Meal meal,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer maxCost,
                                                 @RequestParam(required = false) Integer radius,
                                                 @RequestParam(defaultValue = "false") boolean noSpicy,
                                                 @RequestParam(defaultValue = "false") boolean local,
                                                 @RequestParam(required = false) String near) {
        return ApiResponse.ok(tripFood.find(authFacade.current(), id, day - 1,
                new TripFood.FoodQuery(near, meal, keyword, maxCost, radius, noSpicy, local)));
    }

    /** 美食助手的一句话。 */
    public record FoodAsk(String text) {
    }

    /** POST /api/v1/trips/{id}/days/{day}/food/ask?meal=：一句话找餐厅，模型只拆条件，餐厅和路程来自高德。 */
    @PostMapping("/{id}/days/{day}/food/ask")
    public ApiResponse<TripFood.FoodResult> askFood(@PathVariable long id, @PathVariable int day,
                                                    @RequestParam(defaultValue = "LUNCH") TripEditor.Meal meal,
                                                    @RequestBody FoodAsk ask) {
        return ApiResponse.ok(tripFood.ask(authFacade.current(), id, day - 1, meal, ask.text()));
    }

    /** POST /api/v1/trips/{id}/days/{day}/meals/{meal}/preview：把餐厅放进某一顿的预览，不保存。 */
    @PostMapping("/{id}/days/{day}/meals/{meal}/preview")
    public ApiResponse<TripEditor.MealPreview> previewMeal(@PathVariable long id, @PathVariable int day,
                                                           @PathVariable TripEditor.Meal meal,
                                                           @RequestBody TripEditor.PlacePick place) {
        return ApiResponse.ok(tripEditor.previewMeal(authFacade.current(), id, day - 1, meal, place));
    }

    /** POST /api/v1/trips/{id}/days/{day}/meals/{meal}?replaces=d1-4：确认放进这一顿，替换的项和预览一致才存新版本。 */
    @PostMapping("/{id}/days/{day}/meals/{meal}")
    public ApiResponse<TripService.TripDetail> saveMeal(@PathVariable long id, @PathVariable int day,
                                                        @PathVariable TripEditor.Meal meal,
                                                        @RequestParam(required = false) String replaces,
                                                        @RequestBody TripEditor.PlacePick place) {
        return ApiResponse.ok(tripEditor.saveMeal(authFacade.current(), id, day - 1, meal, place, replaces));
    }

    /** POST /api/v1/trips/{id}/days/{day}/flight/preview：把比价页选的航班写进第 day 天的预览，不保存。 */
    @PostMapping("/{id}/days/{day}/flight/preview")
    public ApiResponse<TripEditor.FlightPreview> previewFlight(@PathVariable long id, @PathVariable int day,
                                                               @RequestBody TripEditor.FlightPick flight) {
        return ApiResponse.ok(tripEditor.previewFlight(authFacade.current(), id, day - 1, flight));
    }

    /** POST /api/v1/trips/{id}/days/{day}/flight?replaces=d1-2：确认写进行程，存新版本；不下单。 */
    @PostMapping("/{id}/days/{day}/flight")
    public ApiResponse<TripService.TripDetail> saveFlight(@PathVariable long id, @PathVariable int day,
                                                          @RequestParam(required = false) String replaces,
                                                          @RequestBody TripEditor.FlightPick flight) {
        return ApiResponse.ok(tripEditor.saveFlight(authFacade.current(), id, day - 1, flight, replaces));
    }

    /**
     * POST /api/v1/trips/{id}/stays/preview?stayId=s1：把比价页选的酒店换进行程的预览（不传 stayId：只有一段住宿就换那段，
     * 没有住宿就新加一段），列出每天路程的变化。不保存。
     */
    @PostMapping("/{id}/stays/preview")
    public ApiResponse<TripEditor.StayPreview> previewStay(@PathVariable long id,
                                                           @RequestParam(required = false) String stayId,
                                                           @RequestBody TripEditor.StayPick hotel) {
        return ApiResponse.ok(tripEditor.previewStay(authFacade.current(), id, stayId, hotel));
    }

    /** POST /api/v1/trips/{id}/stays/apply?stayId=&replaces=s1：确认换酒店，被换的那段和预览一致才存新版本；不下单。 */
    @PostMapping("/{id}/stays/apply")
    public ApiResponse<TripService.TripDetail> applyStay(@PathVariable long id,
                                                         @RequestParam(required = false) String stayId,
                                                         @RequestParam(required = false) String replaces,
                                                         @RequestBody TripEditor.StayPick hotel) {
        return ApiResponse.ok(tripEditor.saveStay(authFacade.current(), id, stayId, hotel, replaces));
    }

    /** GET /api/v1/trips/{id}/days/{day}/tickets：第 day 天每个景点的门票（飞猪），对不上的标待查。 */
    @GetMapping("/{id}/days/{day}/tickets")
    public ApiResponse<TripTickets.DayTickets> tickets(@PathVariable long id, @PathVariable int day,
                                                       @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(tripTickets.day(authFacade.current(), id, day - 1, refresh));
    }

    /** 跨天对调两个景点：a、b 是两项的 id；保存时带上预览时两项的名称，对不上说明行程变了。 */
    public record Swap(String a, String b, String aName, String bName) {
    }

    /** POST /api/v1/trips/{id}/swap/preview：预览把不同两天的两个景点对调（如下雨天的户外和晴天的室内），不保存。 */
    @PostMapping("/{id}/swap/preview")
    public ApiResponse<TripEditor.SwapPreview> previewSwap(@PathVariable long id, @RequestBody Swap swap) {
        return ApiResponse.ok(tripEditor.previewSwap(authFacade.current(), id, swap.a(), swap.b()));
    }

    /** POST /api/v1/trips/{id}/swap：确认对调，存新版本。 */
    @PostMapping("/{id}/swap")
    public ApiResponse<TripService.TripDetail> swap(@PathVariable long id, @RequestBody Swap swap) {
        return ApiResponse.ok(tripEditor.saveSwap(authFacade.current(), id, swap.a(), swap.b(), swap.aName(),
                swap.bName()));
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
