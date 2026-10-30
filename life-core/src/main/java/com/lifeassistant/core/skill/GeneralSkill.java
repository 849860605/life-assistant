package com.lifeassistant.core.skill;

import com.lifeassistant.core.search.WebSearchTools;
import com.lifeassistant.core.weather.WeatherTools;
import org.springframework.stereotype.Component;

import java.util.List;

/** 兜底技能：闲聊、记忆管理、单纯的天气和常识问题，以及不属于任何技能的问题。 */
@Component
public class GeneralSkill implements LifeSkill {

    private final WeatherTools weatherTools;
    private final WebSearchTools webSearchTools;

    public GeneralSkill(WeatherTools weatherTools, WebSearchTools webSearchTools) {
        this.weatherTools = weatherTools;
        this.webSearchTools = webSearchTools;
    }

    public static final String ID = "general";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "通用";
    }

    @Override
    public String description() {
        return "闲聊和问候；记住或忘掉关于用户的信息（偏好、忌口、家人、伤病、体能）；单纯问天气（不涉及出行安排）；"
                + "常识、汇率、换算等其他问题";
    }

    @Override
    public List<String> routingExamples() {
        return List.of("你好", "我不吃辣，记一下", "你记得我哪些信息", "我膝盖不太好", "明天深圳会下雨吗", "你能做什么");
    }

    @Override
    public String systemPrompt() {
        return """
                你现在负责通用对话。用户告诉你关于自己的长期信息时，调用 rememberFact 记下来；
                用户说某条信息不对或让你忘掉时，调用 forgetFact。
                问天气用 dailyWeather 或 hourlyWeather 查，不要凭印象回答；需要最新信息时可以用 searchGuides 搜。
                用户问你能做什么时，简单介绍：出游规划（含酒店和机票比价）、找餐厅（大众点评和携程的评分、人均）、日程提醒、记住个人信息。
                不要编造价格、营业时间这类需要实时数据的内容。""";
    }

    /** 天气和联网搜索；记忆工具由 SkillRunner 统一挂载。 */
    @Override
    public List<Object> tools() {
        return List.of(weatherTools, webSearchTools);
    }
}
