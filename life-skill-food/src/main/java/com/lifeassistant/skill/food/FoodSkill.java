package com.lifeassistant.skill.food;

import com.lifeassistant.core.food.FoodTools;
import com.lifeassistant.core.schedule.ScheduleTools;
import com.lifeassistant.core.skill.LifeSkill;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 美食技能：找餐厅、吃什么。餐厅数据来自美团（大众点评）和携程，查询工具在 life-core，出游技能也用它。
 */
@Component
public class FoodSkill implements LifeSkill {

    private final FoodTools foodTools;
    private final ScheduleTools scheduleTools;
    private final ObjectProvider<ToolCallbackProvider> mcpToolProviders;

    public FoodSkill(FoodTools foodTools, ScheduleTools scheduleTools,
                     ObjectProvider<ToolCallbackProvider> mcpToolProviders) {
        this.foodTools = foodTools;
        this.scheduleTools = scheduleTools;
        this.mcpToolProviders = mcpToolProviders;
    }

    /** 技能 id，路由结果用它定位技能。 */
    @Override
    public String id() {
        return "food";
    }

    /** 中文名，给前端和日志看。 */
    @Override
    public String name() {
        return "美食";
    }

    /** 给路由 Agent 看的职责说明。 */
    @Override
    public String description() {
        return "吃什么、找餐厅：按位置、菜系、人均、场景推荐餐厅，问某家店的评分、人均、招牌菜、营业时间，"
                + "带孩子或约会去哪吃，以及约饭时间的提醒";
    }

    /** 示例问法，提高路由准确率，也是评测集的种子。 */
    @Override
    public List<String> routingExamples() {
        return List.of("今晚南山吃什么火锅", "钟楼附近有什么好吃的陕菜", "周末带娃去哪吃饭，人均一百以内", "海底捞人均多少");
    }

    /** 找餐厅的提示词：补齐城市和位置、按忌口筛、输出表格、不编造店名。 */
    @Override
    public String systemPrompt() {
        return """
                你现在负责吃的。
                - 找餐厅用 findRestaurants。城市没说时用记忆里的常住城市，位置没说就不限；实在不知道城市再问。
                - 用户记忆里有忌口（不吃辣、海鲜过敏等）时，查询时带上（比如 cuisine 写"不辣的"），
                  结果里明显不合适的店（比如不吃辣时的川湘菜、火锅红锅）剔除或提醒。减脂期优先清淡、高蛋白的。
                - 返回值里有 card 字段时，餐厅列表已经以卡片展示给用户（评分、人均、招牌菜、点评链接都在卡片上），
                  不要再列表格，只用一两句话按用户的场景（带孩子、约会、宴请）挑一家首选并说理由。
                - 没有 card 字段时输出表格：餐厅 | 评分 | 人均 | 招牌菜 | 位置。餐厅一列原样照抄 display 字段（已带好点评链接），
                  不要自己拼或挪用链接；评分后注明来源（点评/携程），没有的格写"—"。默认 5 家以内，两个平台都推荐的优先。
                - 某个平台没查到时简单说明；restaurants 为空但有 raw 时，从 raw 里整理，不要说查不到。
                - 店名、评分、人均只能来自工具结果，不要编造；营业时间和人均可能变动，提醒以点评页为准。
                - 订座、排队、团购要用户自己在点评或美团 App 里操作，不要说已经订好。
                - 用户约好时间（"周六晚上 7 点去这家""记一下"）时，用 createEvent 建日程，标题写店名，地点写位置。
                - 问怎么去、离某地多远时，用地图工具查。""";
    }

    /** 找餐厅和日程工具。 */
    @Override
    public List<Object> tools() {
        return List.of(foodTools, scheduleTools);
    }

    /** 高德地图 MCP（路线和距离），没配置时为空。 */
    @Override
    public List<ToolCallback> toolCallbacks() {
        return mcpToolProviders.orderedStream().flatMap(provider -> Arrays.stream(provider.getToolCallbacks())).toList();
    }
}
