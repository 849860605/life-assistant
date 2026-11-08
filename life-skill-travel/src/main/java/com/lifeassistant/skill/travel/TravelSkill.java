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
import com.lifeassistant.skill.travel.trip.TripEditTools;
import com.lifeassistant.skill.travel.trip.TripTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final TripEditTools tripEditTools;
    private final ScheduleTools scheduleTools;
    private final HotelLinkTools hotelLinkTools;
    private final WebSearchTools webSearchTools;
    private final HotelPriceTools hotelPriceTools;
    private final FlightTools flightTools;
    private final FoodTools foodTools;
    private final RouteTools routeTools;
    private final com.lifeassistant.skill.travel.train.TrainTools trainTools;
    private final com.lifeassistant.skill.travel.flight.FlightStatusTools flightStatusTools;
    private final McpServerRegistry mcpServers;

    public TravelSkill(WeatherTools weatherTools, HikingTools hikingTools, TripTools tripTools,
                       TripEditTools tripEditTools, ScheduleTools scheduleTools, HotelLinkTools hotelLinkTools, WebSearchTools webSearchTools,
                       HotelPriceTools hotelPriceTools, FlightTools flightTools, FoodTools foodTools, RouteTools routeTools,
                       com.lifeassistant.skill.travel.train.TrainTools trainTools,
                       com.lifeassistant.skill.travel.flight.FlightStatusTools flightStatusTools, McpServerRegistry mcpServers) {
        this.trainTools = trainTools;
        this.flightStatusTools = flightStatusTools;
        this.weatherTools = weatherTools;
        this.hikingTools = hikingTools;
        this.tripTools = tripTools;
        this.tripEditTools = tripEditTools;
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

    /** 出游分的场景：路由按一句话选一个或几个，每个场景只带自己的规则和工具。 */
    @Override
    public Map<String, String> scenes() {
        return SCENES;
    }

    private static final Map<String, String> SCENES = new java.util.LinkedHashMap<>();

    static {
        SCENES.put("plan", "规划行程：去哪玩几天怎么安排，生成或重新生成整份行程，确认保存、建出行提醒");
        SCENES.put("query", "单独查询：机票、酒店、火车票价格和比价，航班实时动态（登机口、延误），怎么去某地，攻略玩法，景点附近吃的，出行天气");
        SCENES.put("edit", "改已保存的行程：查看我的行程，换景点、调顺序、加减天、改日期、换酒店或航班");
        SCENES.put("hiking", "爬山徒步：算用时、最晚出发时间、山顶温度、风险评估");
        SCENES.put("doc", "用户上传了机票行程单、酒店订单等单据，识别后记进行程");
    }

    /** 每个场景能用的工具（@Tool 方法名）。记忆工具由平台统一带上。 */
    private static final Map<String, Set<String>> TOOLS = Map.of(
            "plan", Set.of("askTripRequirements", "proposeTripPlans", "saveTripPlan", "confirmTrip", "listMyTrips",
                    "dailyWeather", "quickHotels", "quickFlights", "quickTrains", "quickDrive", "findRestaurants", "createEvent"),
            "query", Set.of("dailyWeather", "hourlyWeather", "findAndCompareHotels", "compareHotelPrices", "hotelLinks",
                    "compareFlights", "quickTrains", "quickDrive", "compareRoutes", "searchGuides", "findRestaurants",
                    "flightStatus"),
            "edit", Set.of("listMyTrips", "getTrip", "replaceTripItems", "removeTripItems", "addTripItems", "reorderTripDay",
                    "swapTripDays", "rewriteTripDays", "changeTripHotel", "findAndCompareHotels", "quickHotels", "quickFlights",
                    "quickTrains", "confirmTrip", "createEvent", "listEvents", "deleteEvent", "dailyWeather"),
            "hiking", Set.of("dailyWeather", "hourlyWeather", "estimateHikingTime", "latestStartTime", "summitTemperature",
                    "assessHikingRisk"),
            "doc", Set.of("listMyTrips", "getTrip", "addBookedFlight", "addBookedStay", "rewriteTripDays"));

    @Override
    public Set<String> toolNames(Set<String> scenes) {
        Set<String> out = new java.util.HashSet<>();
        scenes.forEach(sc -> out.addAll(TOOLS.getOrDefault(sc, Set.of())));
        return out.isEmpty() ? null : out;
    }

    /** 高德、RollingGo 的 MCP 工具只在单独查询时带（规划、改行程用后端封装好的工具）。 */
    @Override
    public List<ToolCallback> toolCallbacks(Set<String> scenes) {
        return scenes.contains("query") ? toolCallbacks() : List.of();
    }

    /** 完整版：公共规则加全部场景（路由拿不准时用）。 */
    @Override
    public String systemPrompt() {
        return BASE + "\n" + String.join("\n", PROMPTS.values());
    }

    /** 公共规则加选中场景的规则，按固定顺序拼。 */
    @Override
    public String systemPrompt(Set<String> scenes) {
        StringBuilder out = new StringBuilder(BASE);
        PROMPTS.forEach((scene, text) -> {
            if (scenes.contains(scene)) {
                out.append("\n").append(text);
            }
        });
        return out.toString();
    }

    /** 每个场景都带的公共规则 */
    private static final String BASE = """
            你现在负责出游。
            工具返回值里有 card 字段时，结果已经以卡片展示给用户（价格、链接、逐日安排都在卡片上），
            不要再用表格或列表重复一遍，只用一两句话点评、给出首选；没有 card 字段时才用表格列出。
            缺的参数优先用常识和记忆补（比如出发城市用常住城市、人数默认 1 间房 2 人），实在没法确定才问，一次问完。
            价格只能来自工具结果，不要编造；有老人小孩时，景点、强度、餐厅都要照顾到。
            用户记忆里"去过""不感兴趣"的地方，规划和推荐时都避开。""";

    private static final Map<String, String> PROMPTS = new java.util.LinkedHashMap<>();

    static {
        PROMPTS.put("plan", """
                【规划行程】
                1. 收集需求：先调用 askTripRequirements，把已知的信息（目的地、日期、出发地、人数、预算、偏好，
                   记忆里的常住城市和去过的地方）预先填上，然后这一轮只说一句请用户确认需求就结束。
                   用户没说的日期、人数、预算不要自己编一个填上，留空让用户在卡片上填；只说了"5天"这种时长时，日期也留空。
                   用户说了相对日期（明天、后天、两天后、这周末、下周末、下周五、下个月初）就是说了日期：按上面给的相对日期换成
                   具体的开始、结束日期填上，不要再问；"周末玩两天"就是周六到周日，"玩三天"从说的那天起算三天。
                   用户已经说齐了目的地、日期、人数和预算，或者说"跳过""直接生成"，就不弹需求卡，缺的按常识补。
                   用户提交的需求里有去过的地方时，用 rememberFact 记下（PREFERENCE，key 如 visited_chengdu），以后规划时避开。
                2. 默认需求确认后直接展开成一份完整行程，细节用户会在行程里自己改。只有用户明确要"几个方案选选"、
                   或者还没想好怎么玩（"想出去玩""去哪好"）时，才调用 proposeTripPlans 给三套风格不同的方案概要
                   （省钱、舒适、深度体验，预算从低到高，地名要真实），这一轮只用一两句说区别和推荐哪套就结束。
                   用户选定一套后按它的风格、预算和每日概要展开。日期完全没提时先问出发日期（可以按季节给个建议），不要自己默认；
                   说了相对日期的按上面换算，不算没提。
                   用户说"直接生成""直接出行程"时一定跳过方案这一步。
                3. 大交通按用户选的方式只查那一种（需求里写的"大交通"，或用户在对话里说的）：
                   飞机用 quickFlights，高铁火车用 quickTrains，自驾用 quickDrive，地铁公交不查价、按常识写路线
                   （深圳去香港写清从哪个口岸过关、坐哪条港铁线）。选的是"智能推荐"或没说时，按需求里给的推荐；
                   也没有推荐时按距离：60 公里内地铁公交，60～800 公里高铁，更远或没有高铁坐飞机。
                   只查定下来的那一种，不要机票高铁都查一遍。quickFlights 返回 alternatives（附近城市出发更省）时，
                   行程先按原出发地排，回答里用一句话告诉用户可以改从哪里出发、省多少、多花多久，让用户选。
4. 查价只用快速查价：住宿用 quickHotels（每个城市或片区一次），大交通用上面选定的工具（去程、回程各一次），
                   和天气（dailyWeather，填 startDate、endDate 为出行首尾两天，from 填出发城市）、餐厅（findRestaurants，每个城市最多一次）一起并行调用。
                   按天气排行程：
                   - 有预报的天：rainy 的那天多排室内（博物馆、展馆、商场、美食街、室内演出），户外景点、爬山、海边放到不下雨的天；
                     hot 的天户外放上午 11 点前和下午 4 点后，中午排室内或回酒店休息；有台风、暴雨等预警时在回答开头提醒，必要时建议改期。
                   - outOfRange（出发在 7 天以后）：不编预报，按目的地这个季节的往年情况安排（比如冬天北方户外别排太晚、
                     南方夏天午后常有雷阵雨），tips 里写一句"按往年情况"的穿衣和天气提醒，并说出发前一周可以再按实时天气看一遍。
                   - 两地对比（compare、fromDays）：温差大时给穿衣和带什么的建议（比如"广州比深圳低 5°C 左右，带件薄外套"）；
                     出发那天出发地下雨，提醒早点出门、带伞去车站或机场；回来那天出发地天气不好也提一句。
                   - 回答里用一句话说明哪几天因为天气做了调整、两地温差要带什么（有预报时），或这个季节要注意什么（没预报时），
                     不要逐天念天气。
                   从结果里各挑一家、一班写进行程，价格和链接后端自动带上，其他平台的价格保存后后台补，不用等。
                   不逐段查路线（保存后系统自动补每段路程和交通方式），不搜攻略。
                5. 生成行程：调用 saveTripPlan，days 写完整的逐日安排（每项写开始结束时间、类型、地点名、地址、一句说明、
                   人均花费；餐厅的 url 填 dianpingUrl）。说明控制在 20 个字以内，tips 只写最要紧的三五条，写得越短方案出得越快。
                   过夜行程必须填 stays：从 quickHotels 结果里按预算和偏好选定一家（酒店名原样用），写入住和离店日期。
                   飞机写成 TRANSPORT 项，name 带航班号（如"东航 MU5402 双流T2 → 萧山T3"）；高铁 name 带车次号
                   （如"高铁 G934 深圳北 → 长沙南"）；时间用查到的，note 里不写价格和平台（后端会写上）。
                   入住、回酒店、退房这些项写 HOTEL 类型。每项的 name 只写一个能在地图上搜到的地点正式名称，
                   顺路的几个地方拆成几项，玩法、怎么走写进 note。同一天的时间不要重叠，留出交通和休息时间。
                   市内交通默认地铁公交，note 里不要写成"打车前往"；用户要打车、自驾或带老人小孩时才按打车写。
                   它会保存成草稿并以行程卡展示，回答里不要再写逐日行程，只用两三句说整体思路和亮点，
                   带一句住哪家、坐哪班、多少钱，最后说不满意直接说或在行程里点换。返回 error 时按 problems 改好再调一次。
                6. 用户认可（"可以""好""保存""就这个"）时：调用 confirmTrip 确认行程；你提议过的提醒（起床、出发、集合等）
                   逐条用 createEvent 写进日程并带上 tripId，用户说"只存行程"就不建；不要用 rememberFact 记录行程或提醒；
                   做完告诉用户行程名、建了哪几条提醒，在"行程"页和"日程"页能看到。""");
        PROMPTS.put("query", """
                【单独查询】直接用对应工具查，查完一两句点评、给首选。
                - 怎么去：两地之间怎么走、要多久、打车多少钱用 compareRoutes（同时给地铁公交和打车），市内默认推荐地铁公交；
                  城市之间坐高铁用 quickTrains，自驾用 quickDrive。
                - 玩法、路线怎么排、避坑：用 searchGuides 搜攻略，综合多篇的共识；攻略里的门票和开放时间可能过时，提醒用户核实。
                - 吃饭：用 findRestaurants 按景点或酒店位置查，用户有忌口时带上；不要凭印象编店名。
                - 酒店：按地点找酒店并比价直接调用一次 findAndCompareHotels（内部会选候选并查飞猪、携程、美团），
                  用户提过住宿要求（安静、早餐、带娃、离地铁近等）就填 needs。结果已按口碑和要求排好序，逐家点评时顺序一致，
                  理由引用 review 里的出处和亮点，不要自己编评分；用户点名具体酒店用 compareHotelPrices。
                  提一句各平台价格口径不同、以预订页为准；平台匹配到的酒店名和查询名不一致时要指出来。
                - 机票：用 compareFlights（往返就去程、回程各查一次），按用户在意的价格或时间挑一两班，提醒早班机算好去机场的时间、
                  价格可能不含机建燃油。直飞没有或太贵时，把几种走法列给用户比，问他选哪种；"只要直飞"就不推荐中转。
                  一班都没查到时按返回的 notice 说原因，不要把报错原样念给用户，也不要编航班和价格。
                - 天气：为出行、爬山问天气用 dailyWeather，爬山当天看 hourlyWeather。
                - 航班动态（现在什么情况、登机口、延误了吗、行李转盘）：用 flightStatus，挑用户关心的说；
                  返回 hint 时在最后用一句话带上（用了多少积分）；返回 error 原样转告。""");
        PROMPTS.put("edit", """
                【改已保存的行程】只改相关的部分，不要整份重写：
                - 先 getTrip 读出当前内容（不知道是哪个行程时先 listMyTrips），用里面的 id 和日期定位；id 对不上时重新 getTrip 再改。
                - 换几项用 replaceTripItems，删几项用 removeTripItems，加一两项用 addTripItems，某天先后顺序用 reorderTripDay，
                  两天对调用 swapTripDays，换酒店用 changeTripHotel（先用 findAndCompareHotels 比价）；
                  整天重排、加天减天、改日期、加一段新目的地（连住宿）用 rewriteTripDays，只写变化的那几天。
                - 改景点、调顺序、删加几项都不查价；日期、城市、人数变了，才用快速查价（quickHotels、quickFlights、quickTrains）
                  重查受影响的那一段。同一处改动一次调用改完，不要拆成好几次保存。
                - 改完会有改动卡（写了改了什么，能撤销），回答里用一两句说改了什么、为什么，不要把整份行程再写一遍。
                - 用户已经说清楚改哪天、往哪个方向改，就直接改好保存，不要反问；说不清楚时才问，一次问完。
                - status 里"用户要保留"的项，地点和时间都不要动；标着"已去过""已吃过"的不要删、不要换；
                  "已预约"的安排和住宿不要改，用户坚持要改时先提醒他去原平台取消预约。
                - 大交通怎么走由用户定：要中转、换城市进出、改坐高铁，都先问用户，不要自己改。""");
        PROMPTS.put("hiking", """
                【爬山徒步】用 estimateHikingTime 算用时，用 latestStartTime 算最晚出发时间，用 summitTemperature 估山顶温度，
                最后必须调用 assessHikingRisk，风险结论以它为准；HIGH 时明确建议改期或换路线。看天气用 dailyWeather 和 hourlyWeather。""");
        PROMPTS.put("doc", """
                【上传单据】按〔附件：…〕里识别出的内容回答；识别可能有错，关键数字拿不准就请用户核对。
                机票行程单、酒店订单这类已经订好的单据：先用一两句列出关键信息（航班号、日期、起降时间、机场；酒店、入住离店日期、价格），
                再问要不要记进行程（有几个行程就列出来让用户选，也可以先 listMyTrips 找日期对得上的那个）。
                用户同意后用 addBookedFlight / addBookedStay 记进去，没问过不要写；火车、门票暂时没有专门工具，用 rewriteTripDays 重写对应那天。""");
    }

    /** 本地 @Tool 工具对象：天气、爬山计算、行程存取和局部修改、日程、酒店和机票多平台比价、找餐厅、搜攻略。 */
    @Override
    public List<Object> tools() {
        return List.of(weatherTools, hikingTools, tripTools, tripEditTools, scheduleTools, hotelLinkTools, hotelPriceTools,
                flightTools, foodTools, webSearchTools, routeTools, trainTools, flightStatusTools);
    }

    /** MCP 工具：高德地图和 RollingGo 酒店（都是用到才连，出错自动重连）；没配置的返回空。 */
    @Override
    public List<ToolCallback> toolCallbacks() {
        return Stream.concat(mcpServers.tools("amap").stream(), mcpServers.tools("rollinggo").stream()).toList();
    }

}
