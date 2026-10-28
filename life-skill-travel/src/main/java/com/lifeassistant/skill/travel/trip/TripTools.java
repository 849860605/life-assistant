package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.core.auth.ToolContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 给模型用的行程工具：保存（含改版）、列出、读取行程，用户身份从 ToolContext 取。
 */
@Component
@RequiredArgsConstructor
public class TripTools {

    private final TripService tripService;

    /**
     * 以 AI 身份保存一个行程版本，返回 tripId、版本号和详情页路径。
     * changeNote 缺省时，新建记为“首次生成”，修改记为“修改”。
     */
    @Tool(description = """
            保存行程。用户确认行程后调用；修改已保存的行程时传 tripId，会生成新版本，旧版本可以回看。
            content 用 Markdown 写完整的逐日安排，包含时间、地点、交通、注意事项。""")
    public Map<String, Object> saveTripPlan(
            @ToolParam(description = "已有行程的 id；新行程不填", required = false) Long tripId,
            @ToolParam(description = "标题，例如：泰山日出两日游") String title,
            @ToolParam(description = "类型：CITY 城市游 / HIKING 爬山徒步 / MIXED 混合") String tripType,
            @ToolParam(description = "目的地") String destination,
            @ToolParam(description = "开始日期 yyyy-MM-dd", required = false) String startDate,
            @ToolParam(description = "结束日期 yyyy-MM-dd", required = false) String endDate,
            @ToolParam(description = "行程正文，Markdown") String content,
            @ToolParam(description = "这次改了什么，例如：第二天下雨改成室内", required = false) String changeNote,
            ToolContext toolContext) {
        TripService.TripDetail detail = tripService.saveVersion(ToolContexts.user(toolContext), tripId,
                new TripService.TripDraft(title, tripType, destination, date(startDate), date(endDate), content,
                        changeNote == null ? (tripId == null ? "首次生成" : "修改") : changeNote),
                "AI");
        return Map.of("tripId", detail.trip().getId(), "version", detail.version().getVersion(),
                "page", "/trip/" + detail.trip().getId());
    }

    /** 列出当前用户的行程摘要（不含正文），空字段转成空串。 */
    @Tool(description = "列出用户保存过的行程。")
    public List<Map<String, Object>> listMyTrips(ToolContext toolContext) {
        return tripService.list(ToolContexts.user(toolContext)).stream()
                .map(t -> Map.<String, Object>of(
                        "tripId", t.getId(),
                        "title", t.getTitle(),
                        "destination", t.getDestination() == null ? "" : t.getDestination(),
                        "startDate", t.getStartDate() == null ? "" : t.getStartDate().toString(),
                        "version", t.getCurrentVer()))
                .toList();
    }

    /** 读取行程当前版本的标题、版本号和正文。 */
    @Tool(description = "读取一个已保存行程的当前内容，修改行程前先读。")
    public Map<String, Object> getTrip(@ToolParam(description = "行程 id") long tripId, ToolContext toolContext) {
        TripService.TripDetail detail = tripService.get(ToolContexts.user(toolContext), tripId, null);
        return Map.of("tripId", tripId, "title", detail.trip().getTitle(),
                "version", detail.version().getVersion(), "content", detail.version().getContent());
    }

    /** 解析 yyyy-MM-dd，空串当作未填。 */
    private static LocalDate date(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text.trim());
    }
}
