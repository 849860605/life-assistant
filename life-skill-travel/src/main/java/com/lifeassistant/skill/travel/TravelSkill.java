package com.lifeassistant.skill.travel;

import com.lifeassistant.core.schedule.ScheduleTools;
import com.lifeassistant.core.skill.LifeSkill;
import com.lifeassistant.core.weather.WeatherTools;
import com.lifeassistant.skill.travel.hiking.HikingTools;
import com.lifeassistant.skill.travel.trip.TripTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 出游技能：城市游、爬山徒步的规划和已保存行程的修改。地图能力来自高德官方 MCP（配置了 AMAP_KEY 才启用）。
 */
@Component
public class TravelSkill implements LifeSkill {

    private final WeatherTools weatherTools;
    private final HikingTools hikingTools;
    private final TripTools tripTools;
    private final ScheduleTools scheduleTools;
    private final ObjectProvider<ToolCallbackProvider> mcpToolProviders;

    public TravelSkill(WeatherTools weatherTools, HikingTools hikingTools, TripTools tripTools,
                       ScheduleTools scheduleTools, ObjectProvider<ToolCallbackProvider> mcpToolProviders) {
        this.weatherTools = weatherTools;
        this.hikingTools = hikingTools;
        this.tripTools = tripTools;
        this.scheduleTools = scheduleTools;
        this.mcpToolProviders = mcpToolProviders;
    }

    /** 技能 id，路由结果用它定位技能。 */
    @Override
    public String id() {
        return "travel";
    }

    /** 中文名，给前端和日志看。 */
    @Override
    public String name() {
        return "出游";
    }

    /** 给路由 Agent 看的职责说明。 */
    @Override
    public String description() {
        return "出去玩、旅行、爬山徒步的规划：行程安排、天气、景点和路线、交通、找酒店、户外安全评估，以及查看和修改已保存的行程";
    }

    /** 示例问法，提高路由准确率，也是评测集的种子。 */
    @Override
    public List<String> routingExamples() {
        return List.of("周六想去爬泰山看日出", "下周末带爸妈去杭州玩两天", "西湖附近有什么好住的", "明天下雨，帮我改一下行程");
    }

    /** 出游规划的步骤化提示词：先补齐信息，再查天气、地图，爬山必须走风险评估，最后确认保存。 */
    @Override
    public String systemPrompt() {
        return """
                你现在负责出游规划。按下面的步骤做：
                1. 先确认关键信息：出发地、目的地、日期、几个人（有没有老人小孩）、预算、想要的强度。缺的一次问清楚，不要一条条追问。
                2. 查天气（dailyWeather，爬山当天再看 hourlyWeather）。
                3. 用地图工具查景点位置、路线和交通耗时；地图工具不可用时，说明是凭经验估计。
                4. 爬山：用 estimateHikingTime 算用时，用 latestStartTime 算最晚出发时间，用 summitTemperature 估山顶温度，
                   最后必须调用 assessHikingRisk，风险结论以它为准。HIGH 时明确建议改期或换路线。
                5. 酒店：用地图工具按位置搜索并给出评分和区位，价格提醒用户到携程、美团等平台查，不要编造价格。
                6. 输出逐日行程：时间、地点、交通方式和耗时、注意事项；爬山附装备清单。
                7. 问用户是否保存。用户回复"可以""好""记录一下""保存""都存"这类认可时：
                   - 调用 saveTripPlan 保存你上一版给出的完整行程，content 用完整的 Markdown 正文，不要缩写成摘要；
                   - 你提议过的提醒（起床、出发、集合等）逐条用 createEvent 写进日程，用户说"只存行程"就不建；
                   - 不要用 rememberFact 记录行程或提醒，记忆只放长期不变的个人信息；
                   - 做完告诉用户：行程名和版本号、建了哪几条提醒（时间），在"行程"页和"日程"页能看到；不要编造链接或地址。
                修改已保存的行程时，先 getTrip 读出当前内容，只改需要改的部分，再用 saveTripPlan 带上 tripId 保存。""";
    }

    /** 本地 @Tool 工具对象：天气、爬山计算、行程存取、日程。 */
    @Override
    public List<Object> tools() {
        return List.of(weatherTools, hikingTools, tripTools, scheduleTools);
    }

    /** 汇总所有 MCP 工具提供者（目前是高德地图）的工具回调；未配置 MCP 时返回空列表。 */
    @Override
    public List<ToolCallback> toolCallbacks() {
        return mcpToolProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toList();
    }
}
