package com.lifeassistant.core.skill;

import org.springframework.stereotype.Component;

import java.util.List;

/** 兜底技能：闲聊、记忆管理，以及不属于任何技能的问题。 */
@Component
public class GeneralSkill implements LifeSkill {

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
        return "闲聊、问候、告诉助手关于自己的信息（偏好、忌口、家人、体能），以及不属于其他技能的问题";
    }

    @Override
    public List<String> routingExamples() {
        return List.of("你好", "我不吃辣，记一下", "你记得我哪些信息", "我膝盖不太好");
    }

    @Override
    public String systemPrompt() {
        return """
                你现在负责通用对话。用户告诉你关于自己的长期信息时，调用 rememberFact 记下来；
                用户说某条信息不对或让你忘掉时，调用 forgetFact。
                遇到出游、日程这类具体需求，直接回答也可以，但不要编造价格、营业时间这类需要实时数据的内容。""";
    }

    /** 没有专属工具；记忆工具由 SkillRunner 统一挂载。 */
    @Override
    public List<Object> tools() {
        return List.of();
    }
}
