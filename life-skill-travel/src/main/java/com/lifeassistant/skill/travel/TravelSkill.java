package com.lifeassistant.skill.travel;

import com.lifeassistant.core.food.FoodTools;
import com.lifeassistant.core.mcp.McpServerRegistry;
import com.lifeassistant.core.schedule.ScheduleTools;
import com.lifeassistant.core.search.WebSearchTools;
import com.lifeassistant.core.skill.LifeSkill;
import com.lifeassistant.core.weather.WeatherTools;
import com.lifeassistant.skill.travel.flight.FlightTools;
import com.lifeassistant.skill.travel.hiking.HikingTools;
import com.lifeassistant.skill.travel.hotel.HotelLinkTools;
import com.lifeassistant.skill.travel.hotel.HotelPriceTools;
import com.lifeassistant.skill.travel.trip.TripTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 出游技能：城市游、爬山徒步的规划和已保存行程的修改。地图能力来自高德官方 MCP（配置了 AMAP_KEY 才启用）。
 */
@Component
public class TravelSkill implements LifeSkill {

    private final WeatherTools weatherTools;
    private final HikingTools hikingTools;
    private final TripTools tripTools;
    private final ScheduleTools scheduleTools;
    private final HotelLinkTools hotelLinkTools;
    private final WebSearchTools webSearchTools;
    private final HotelPriceTools hotelPriceTools;
    private final FlightTools flightTools;
    private final FoodTools foodTools;
    private final ObjectProvider<ToolCallbackProvider> mcpToolProviders;
    private final McpServerRegistry mcpServers;

    public TravelSkill(WeatherTools weatherTools, HikingTools hikingTools, TripTools tripTools,
                       ScheduleTools scheduleTools, HotelLinkTools hotelLinkTools, WebSearchTools webSearchTools,
                       HotelPriceTools hotelPriceTools, FlightTools flightTools, FoodTools foodTools,
                       ObjectProvider<ToolCallbackProvider> mcpToolProviders, McpServerRegistry mcpServers) {
        this.weatherTools = weatherTools;
        this.hikingTools = hikingTools;
        this.tripTools = tripTools;
        this.scheduleTools = scheduleTools;
        this.hotelLinkTools = hotelLinkTools;
        this.webSearchTools = webSearchTools;
        this.hotelPriceTools = hotelPriceTools;
        this.flightTools = flightTools;
        this.foodTools = foodTools;
        this.mcpToolProviders = mcpToolProviders;
        this.mcpServers = mcpServers;
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
        return "出去玩、旅行、出差、爬山徒步：行程规划、景点推荐和攻略、路线和交通、机票查询比价、酒店查询比价、为出行查天气、户外安全评估，以及查看和修改已保存的行程";
    }

    /** 示例问法，提高路由准确率，也是评测集的种子。 */
    @Override
    public List<String> routingExamples() {
        return List.of("周六想去爬泰山看日出", "下周末带爸妈去杭州玩两天", "西湖附近有什么好住的", "明天下雨，帮我改一下行程",
                "11月5号深圳飞北京的机票多少钱");
    }

    /** 出游规划的步骤化提示词：先补齐信息，再查天气、地图，爬山必须走风险评估，最后确认保存。 */
    @Override
    public String systemPrompt() {
        return """
                你现在负责出游。先判断用户要的是哪一种，再动手：
                - 单点问题（某段机票多少钱、某地附近的酒店价格、怎么去某地、某天天气适不适合爬山）：直接用对应工具查，
                  缺的参数优先用常识和记忆补（比如出发城市用常住城市、人数默认 1 间房 2 人），实在没法确定才问，一次问完。
                - 完整的行程规划（去哪玩几天、怎么安排）：按下面的步骤做。
                - 已保存的行程：查看用 listMyTrips / getTrip，修改按第 8 步的修改规则。
                1. 先确认关键信息：出发地、目的地、日期、几个人（有没有老人小孩）、预算、想要的强度。缺的一次问清楚，不要一条条追问。
                2. 查天气（dailyWeather，爬山当天再看 hourlyWeather）。
                3. 用地图工具查景点位置、路线和交通耗时；地图工具不可用时，说明是凭经验估计。
                   想了解玩法、路线怎么排、避坑时，用 searchGuides 搜攻略，
                   综合多篇的共识来安排，引用到的攻略附原链接；攻略里的门票和开放时间可能过时，要提醒用户核实。
                   吃饭：用户问附近吃的，或逐日行程里要安排正餐时，用 findRestaurants 按景点或酒店位置查
                   （同时查大众点评和携程），每顿推荐 1～2 家，写评分、人均和招牌菜，店名原样用 display 字段（已带点评链接）；
                   用户有忌口时带上；不要凭印象编店名。整个行程只在用户要求或主要用餐点各查一次，别每顿都查。
                4. 爬山：用 estimateHikingTime 算用时，用 latestStartTime 算最晚出发时间，用 summitTemperature 估山顶温度，
                   最后必须调用 assessHikingRisk，风险结论以它为准。HIGH 时明确建议改期或换路线。
                5. 酒店：
                   - 按地点找酒店并比价：直接调用一次 findAndCompareHotels（内部会用 RollingGo 选候选，再并行查飞猪、
                     携程、美团），不要先 searchHotels 再 compareHotelPrices。它返回 error 时，用地图工具找酒店再 compareHotelPrices。
                   - 用户点名了具体酒店：用 compareHotelPrices。只想看看有哪些酒店、不比价时，用 searchHotels。
                   - 同一轮里互不依赖的查询（比如天气、路线、酒店）一起发起，它们会并行执行。
                   - 表格列：酒店 | RollingGo | 飞猪 | 携程 | 美团 | 最低。每格写价格（元/晚）并做成该平台的预订链接，
                     RollingGo 用 searchHotels 返回的 bookingUrl；查不到的格写"—"。最低一列写最便宜的平台和价格。
                   - 表格下说明各平台价格口径不同：RollingGo 是实时可订价，飞猪是酒店级明码价，携程是最低参考价，
                     美团是约价、可能含团购价；以预订页为准。平台匹配到的酒店名和查询名不一致时要指出来。
                   - 用户问某家的房型时，用 getHotelDetail 和美团返回的 rooms 回答。
                   - 价格只能来自工具结果，不要编造。
                6. 机票：用 compareFlights 查（往返就去程、回程各查一次），它会同时比飞猪、携程、美团并按航班号合并。
                   表格列：航班 | 起飞→到达（时间和机场）| 飞猪 | 携程 | 美团 | 最低。每格价格做成该平台的预订链接，没有写"—"。
                   默认挑最便宜的 5～8 班；用户在意时间就按时间挑，并提醒早班机要算好去机场的时间。
                   表格下说明价格是经济舱单程票价，可能不含机建燃油，以预订页为准；某个平台没查到时说明原因。
                7. 输出逐日行程：时间、地点、交通方式和耗时、注意事项；爬山附装备清单。
                8. 问用户是否保存。用户回复"可以""好""记录一下""保存""都存"这类认可时：
                   - 调用 saveTripPlan 保存你上一版给出的完整行程，content 用完整的 Markdown 正文，不要缩写成摘要；
                   - 你提议过的提醒（起床、出发、集合等）逐条用 createEvent 写进日程，并带上 saveTripPlan 返回的 tripId；
                     用户说"只存行程"就不建；
                   - 不要用 rememberFact 记录行程或提醒，记忆只放长期不变的个人信息；
                   - 做完告诉用户：行程名和版本号、建了哪几条提醒（时间），在"行程"页和"日程"页能看到；不要编造链接或地址。
                修改已保存的行程时，先 getTrip 读出当前内容，只改需要改的部分，再用 saveTripPlan 带上 tripId 保存。""";
    }

    /** 本地 @Tool 工具对象：天气、爬山计算、行程存取、日程、酒店和机票多平台比价、找餐厅、搜攻略。 */
    @Override
    public List<Object> tools() {
        return List.of(weatherTools, hikingTools, tripTools, scheduleTools, hotelLinkTools, hotelPriceTools,
                flightTools, foodTools, webSearchTools);
    }

    /** MCP 工具：高德地图（Spring AI 自动配置的连接）加 RollingGo 酒店（按需连接）；没配置的返回空。 */
    @Override
    public List<ToolCallback> toolCallbacks() {
        return Stream.concat(
                mcpToolProviders.orderedStream().flatMap(provider -> Arrays.stream(provider.getToolCallbacks())),
                mcpServers.tools("rollinggo").stream()).toList();
    }
}
