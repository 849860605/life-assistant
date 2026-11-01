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
import com.lifeassistant.skill.travel.trip.RouteTools;
import com.lifeassistant.skill.travel.trip.TripTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/**
 * 出游技能：城市游、爬山徒步的规划和已保存行程的修改。地图能力来自高德官方 MCP（配置了 AMAP_KEY 才启用）。
 */
@Slf4j
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
    private final RouteTools routeTools;
    private final McpServerRegistry mcpServers;

    public TravelSkill(WeatherTools weatherTools, HikingTools hikingTools, TripTools tripTools,
                       ScheduleTools scheduleTools, HotelLinkTools hotelLinkTools, WebSearchTools webSearchTools,
                       HotelPriceTools hotelPriceTools, FlightTools flightTools, FoodTools foodTools, RouteTools routeTools,
                       McpServerRegistry mcpServers) {
        this.weatherTools = weatherTools;
        this.hikingTools = hikingTools;
        this.tripTools = tripTools;
        this.scheduleTools = scheduleTools;
        this.hotelLinkTools = hotelLinkTools;
        this.webSearchTools = webSearchTools;
        this.hotelPriceTools = hotelPriceTools;
        this.flightTools = flightTools;
        this.foodTools = foodTools;
        this.routeTools = routeTools;
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
                - 已保存的行程：查看用 listMyTrips / getTrip，修改按最后的修改规则。
                工具返回值里有 card 字段时，结果已经以卡片展示给用户（价格、链接、逐日安排都在卡片上），
                不要再用表格或列表重复一遍，只用一两句话点评、给出首选；没有 card 字段时才用表格列出。
                1. 收集需求：完整规划时先调用 askTripRequirements，把已知的信息（目的地、日期、出发地、人数、预算、偏好，
                   记忆里的常住城市和去过的地方）预先填上，然后这一轮只说一句请用户确认需求就结束。
                   用户没说的日期、人数、预算不要自己编一个填上，留空让用户在卡片上填；只说了"5天"这种时长时，日期也留空。
                   用户已经说齐了目的地、日期、人数和预算，或者说"跳过""直接生成"，就不弹需求卡，缺的按常识补。
                   用户提交的需求里有去过的地方时，用 rememberFact 记下（PREFERENCE，key 如 visited_chengdu），以后规划时避开。
                   有老人小孩时，景点、强度、餐厅都要照顾到。
                1.5 出方案：需求确认后，先调用 proposeTripPlans 给出三套风格不同的方案概要（省钱、舒适、深度体验，
                   预算从低到高，地名要真实），这一轮只用一两句说区别和推荐哪套就结束，不查机票酒店。
                   用户选定一套（"就按XX方案"）后，再按下面的步骤展开成完整行程，按选定方案的风格、预算和每日概要来排。
                   需求里日期未定时，展开前先问出发日期（可以按季节给一个建议日期让用户选），不要自己默认成明天或最近几天，
                   日期不定机票、酒店、天气都查不准。
                   每套方案的 transport 写清大交通怎么走（直飞、经哪里中转、从哪个城市进出），三套可以不同，让用户选。
                   用户说"直接生成""不用方案"，或者是修改已有行程、一日游这类简单安排时，跳过这一步。
                2. 查天气（dailyWeather，爬山当天再看 hourlyWeather）。
                3. 用地图工具查景点位置；两地之间怎么走、要多久、打车多少钱用 compareRoutes（同时给地铁公交和打车），
                   市内默认推荐地铁公交，打车明显省时间或人多平摊划算时再提打车；地图工具不可用时，说明是凭经验估计。
                   想了解玩法、路线怎么排、避坑时，用 searchGuides 搜攻略，综合多篇的共识来安排；
                   攻略里的门票和开放时间可能过时，要提醒用户核实。
                   吃饭：用户问附近吃的，或行程里要安排正餐时，用 findRestaurants 按景点或酒店位置查，
                   用户有忌口时带上；不要凭印象编店名。整个行程只在主要用餐点各查一次，别每顿都查。
                4. 爬山：用 estimateHikingTime 算用时，用 latestStartTime 算最晚出发时间，用 summitTemperature 估山顶温度，
                   最后必须调用 assessHikingRisk，风险结论以它为准。HIGH 时明确建议改期或换路线。
                5. 酒店：
                   - 按地点找酒店并比价：直接调用一次 findAndCompareHotels（内部会用 RollingGo 选候选，再并行查飞猪、
                     携程、美团），不要先 searchHotels 再 compareHotelPrices。它返回 error 时，用地图工具找酒店再 compareHotelPrices。
                   - 用户点名了具体酒店：用 compareHotelPrices。只想看看有哪些酒店、不比价时，用 searchHotels。
                   - 同一轮里互不依赖的查询（比如天气、路线、酒店）一起发起，它们会并行执行。
                   - 点评时提一句各平台价格口径不同、以预订页为准；平台匹配到的酒店名和查询名不一致时要指出来。
                   - 没有卡片要列表格时，列：酒店 | RollingGo | 飞猪 | 携程 | 美团 | 最低，每格价格做成该平台的预订链接，没有写"—"。
                   - 用户问某家的房型时，用 getHotelDetail 和美团返回的 rooms 回答。价格只能来自工具结果，不要编造。
                6. 机票：用 compareFlights 查（往返就去程、回程各查一次），它会同时比飞猪、携程、美团并按航班号合并。
                   点评时按用户在意的（价格或时间）挑一两班，提醒早班机算好去机场的时间、价格可能不含机建燃油。
                   没有卡片要列表格时，列：航班 | 起飞→到达 | 飞猪 | 携程 | 美团 | 最低，每格价格做成预订链接。
                   大交通怎么走由用户定：要中转、换个城市进出（比如改成沈阳进赤峰出）、改坐高铁，都不要自己改进行程。
                   直飞没有或太贵时，把几种走法的时间、价格（都来自工具结果）列给用户比，问他选哪种，选了再改；
                   需求里写了"只要直飞"就不要推荐中转，写了"尽量直飞"时中转只作为备选提一句。
                   一班都没查到时，按返回的 notice 跟用户说原因（日期太远多半是还没开售、额度用完要晚点再试），
                   不要把各平台的报错原样念给用户，也不要编航班和价格。
                7. 生成行程：调用 saveTripPlan，days 写完整的逐日安排（每项写开始结束时间、类型、地点名、地址、一句说明、
                   人均花费；餐厅的 url 填 dianpingUrl）。过夜行程必须填 stays：从酒店比价结果里按用户的预算和偏好选定一家
                   （酒店名原样用比价结果里的），写入住和离店日期；入住、回酒店、退房这些项写 HOTEL 类型。
                   每项的 name 只写一个地点的正式名称（能在地图上搜到的那种），顺路的几个地方拆成几项，
                   玩法、怎么走写进 note；不然地图上找不到，路线和路程都算不出来。tips 写简短的注意事项、装备清单、天气提醒和预算估算，
                   不要重复机票酒店的比价分析（卡片上已经有了）。
                   安排要留出交通和休息时间，同一天的时间不要重叠。市内交通默认坐地铁公交（行程页会按高德算好每段坐哪条线、
                   多久），note 里不要写成"打车前往"；用户说要打车、自驾或带老人小孩不方便挤地铁时才按打车写。它会保存成草稿并以行程卡展示，
                   回答里不要再写逐日行程，只用两三句说整体思路和亮点，问用户确认还是要调整。返回 error 时按 problems 改好再调一次。
                8. 用户认可（"可以""好""保存""就这个"）时：
                   - 调用 confirmTrip 确认行程；
                   - 你提议过的提醒（起床、出发、集合等）逐条用 createEvent 写进日程，并带上 tripId；用户说"只存行程"就不建；
                   - 不要用 rememberFact 记录行程或提醒，记忆只放长期不变的个人信息；
                   - 做完告诉用户：行程名、建了哪几条提醒（时间），在"行程"页和"日程"页能看到；不要编造链接或地址。
                修改已保存的行程时，先 getTrip 读出当前内容，只改需要改的部分，再用 saveTripPlan 带上 tripId 和完整的 days 保存。
                用户已经说清楚改哪天、往哪个方向改（比如"太赶了，安排轻松点""直接按这个改好保存"），就直接改好保存，不要再反问；
                说不清楚时才问，一次问完。
                status 里有"用户要保留"的项，地点和时间都不要动。
                标着"已去过""已吃过"的项用户已经完成了，不要删、不要换；重新安排时只动还没去的。
                status 里有"已预约"的安排和住宿，是用户已经在平台上订好的，地点、时间、酒店都不要改；
                用户坚持要改时，先提醒他去原平台取消预约，避免扣费或爽约。
                用户记忆里"去过""不感兴趣"的地方，规划和推荐时都避开。""";
    }

    /** 本地 @Tool 工具对象：天气、爬山计算、行程存取、日程、酒店和机票多平台比价、找餐厅、搜攻略。 */
    @Override
    public List<Object> tools() {
        return List.of(weatherTools, hikingTools, tripTools, scheduleTools, hotelLinkTools, hotelPriceTools,
                flightTools, foodTools, webSearchTools, routeTools);
    }

    /** MCP 工具：高德地图和 RollingGo 酒店（都是用到才连，出错自动重连）；没配置的返回空。 */
    @Override
    public List<ToolCallback> toolCallbacks() {
        return Stream.concat(mcpServers.tools("amap").stream(), mcpServers.tools("rollinggo").stream()).toList();
    }

}
