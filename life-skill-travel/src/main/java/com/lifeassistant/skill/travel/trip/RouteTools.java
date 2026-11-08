package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.core.place.AmapPlaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两地之间怎么走：同时给地铁公交（高德，地铁优先）和打车（高德驾车时间、里程和打车费估算），让用户自己比。
 * 和行程页里每段路的算法一样，回答和页面上的数对得上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteTools {

    private final AmapPlaceService places;
    private final com.lifeassistant.core.conversation.TurnFlags turnFlags;

    @Tool(description = """
            查两地之间怎么走，同时返回地铁公交方案（坐哪几条线、分钟、每人票价、步行公里）和打车方案（分钟、公里、打车费估算），
            都来自高德。用户问"从 A 到 B 怎么去 / 坐地铁怎么走 / 打车多少钱"时优先用它；跨城的长途没有公交方案。""")
    public Map<String, Object> compareRoutes(
            @ToolParam(description = "起点，地点名称，例如：西安钟楼") String origin,
            @ToolParam(description = "终点，地点名称，例如：秦始皇兵马俑博物馆") String destination,
            @ToolParam(description = "所在城市，例如：西安") String city) {
        if (turnFlags.planningOnly()) {
            return Map.of("skipped", "规划完整行程时不用逐段查路线：行程保存后系统会自动补每段的路程和交通方式。直接把行程写出来。");
        }
        if (!places.available()) {
            return Map.of("error", "还没有配置高德 Key，查不了路线");
        }
        AmapPlaceService.Place from = locate(origin, city);
        AmapPlaceService.Place to = locate(destination, city);
        if (from == null || to == null) {
            return Map.of("error", "高德上没找到" + (from == null ? "「" + origin + "」" : "「" + destination + "」")
                    + "，换个更完整的名称再试");
        }
        double[] a = {from.lng(), from.lat()};
        double[] b = {to.lng(), to.lat()};
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.name());
        out.put("to", to.name());
        try {
            places.cityCode(city).flatMap(code -> places.transit(a, b, code)).ifPresentOrElse(
                    t -> out.put("transit", Map.of("lines", t.lines(), "minutes", t.minutes(),
                            "farePerPerson", t.fee() == null ? "高德没给票价" : t.fee(), "walkKm", t.walkKm())),
                    () -> out.put("transit", "没有公交方案（可能太近、跨城或这里没有公交数据）"));
        } catch (RuntimeException e) {
            log.warn("公交路线 {} → {} 查询失败：{}", origin, destination, e.getMessage());
            out.put("transit", "公交路线暂时查不到");
        }
        try {
            places.driving(a, b).ifPresentOrElse(
                    r -> out.put("taxi", Map.of("minutes", r.minutes(), "km", r.km(),
                            "fare", r.taxi() == null ? "高德没给打车费" : r.taxi())),
                    () -> out.put("taxi", "没查到驾车路线"));
        } catch (RuntimeException e) {
            log.warn("驾车路线 {} → {} 查询失败：{}", origin, destination, e.getMessage());
            out.put("taxi", "驾车路线暂时查不到");
        }
        out.put("note", "打车费是高德按里程估的整车价格，不含高速费和高峰加价；地铁公交票价按每人算");
        return out;
    }

    /** 按名称在城市里找地点，先要名字对得上的，找不到就用搜索结果的第一个。 */
    private AmapPlaceService.Place locate(String name, String city) {
        try {
            return places.find(name, city).orElseGet(() -> {
                List<AmapPlaceService.Place> found = places.search(name, city, null, 1);
                return found.isEmpty() || found.get(0).lng() == null ? null : found.get(0);
            });
        } catch (RuntimeException e) {
            log.warn("找地点 {} 失败：{}", name, e.getMessage());
            return null;
        }
    }
}
