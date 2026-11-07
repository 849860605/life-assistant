package com.lifeassistant.core.orchestrator;

import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.ModelSpec;
import com.lifeassistant.core.skill.GeneralSkill;
import com.lifeassistant.core.skill.LifeSkill;
import com.lifeassistant.core.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 顶层路由：用便宜的小模型判断这句话交给哪个技能。
 */
@Slf4j
@Service
public class RouterService {

    /** 简短确认的开头词，配合长度上限使用 */
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(可以|好的?|行|嗯|ok|OK|确认|确定|没问题|就这样|就按|按这个|同意|对的?|是的|保存|存|都存|只存|记录|记一下|记下|帮我记|帮我存)");

    private final ChatModelRegistry chatModels;
    private final SkillRegistry skillRegistry;
    private final LifeProperties properties;
    private final UsageRecorder usageRecorder;

    public RouterService(ChatModelRegistry chatModels, SkillRegistry skillRegistry,
                         LifeProperties properties, UsageRecorder usageRecorder) {
        this.chatModels = chatModels;
        this.skillRegistry = skillRegistry;
        this.properties = properties;
        this.usageRecorder = usageRecorder;
    }

    /** 路由结果，模型按这个结构输出 JSON。 */
    public record RouteDecision(String skillId, String reason) {
    }

    /**
     * 用路由模型（temperature=0）做结构化输出并记录用量；结果无效或调用失败时走 fallback，不抛异常。
     *
     * @param lastSkillId 上一轮的技能，用户追问时（"那周日呢"）通常沿用
     */
    public RouteDecision route(String input, String lastSkillId, Long userId, String conversationId) {
        if (isFollowUpConfirmation(input) && lastSkillId != null && skillRegistry.contains(lastSkillId)) {
            return new RouteDecision(lastSkillId, "确认上一轮的方案，沿用上一轮技能");
        }
        try {
            ModelSpec model = chatModels.resolve(chatModels.models().getRouter());
            ResponseEntity<ChatResponse, RouteDecision> response = chatModels.quickClient(model).prompt()
                    .system(systemPrompt(lastSkillId))
                    .user(input)
                    .options(ChatModelRegistry.options(model, 0.0))
                    .call()
                    .responseEntity(RouteDecision.class);
            ChatResponse chatResponse = response.response();
            if (chatResponse != null) {
                usageRecorder.record(userId, conversationId, "ROUTER", model.id(), chatResponse.getMetadata().getUsage());
            }
            RouteDecision decision = response.entity();
            if (decision == null || !skillRegistry.contains(decision.skillId())) {
                return fallback(lastSkillId, "路由结果无效");
            }
            return decision;
        } catch (RuntimeException e) {
            log.warn("路由失败，使用兜底技能：{}", e.getMessage());
            return fallback(lastSkillId, "路由失败");
        }
    }

    /**
     * 是不是对上一轮方案的简短确认（可以、好的、记录一下、都存……）。这类回复离开上下文看不出领域，
     * 交给模型容易被分到通用技能，结果只记成记忆、没保存行程，所以直接按规则沿用上一轮技能。
     */
    static boolean isFollowUpConfirmation(String input) {
        if (input == null) {
            return false;
        }
        String text = input.trim();
        return text.length() <= 12 && FOLLOW_UP.matcher(text).find();
    }

    /** 兜底：上一轮技能仍存在就沿用，否则交给通用技能。 */
    private RouteDecision fallback(String lastSkillId, String reason) {
        String id = lastSkillId != null && skillRegistry.contains(lastSkillId) ? lastSkillId : GeneralSkill.ID;
        return new RouteDecision(id, reason);
    }

    /** 拼路由提示词：列出所有技能的说明和示例问法，并带上上一轮技能以便追问时沿用。 */
    private String systemPrompt(String lastSkillId) {
        String skills = skillRegistry.all().stream()
                .map(LifeSkill.class::cast)
                .map(s -> "- %s：%s。例如：%s".formatted(s.id(), s.description(), String.join("；", s.routingExamples())))
                .collect(Collectors.joining("\n"));
        return """
                你是个人生活助手的路由器。根据用户这句话，判断应该交给哪个技能处理。
                可选技能：
                %s

                上一轮使用的技能：%s。
                判断规则（按顺序）：
                1. 追问或补充上一轮的内容（"那周日呢""换个便宜点的""回程呢""第二家有含早的吗"），沿用上一轮的技能。
                2. 认可、保存或记录上一轮的方案（"可以""记录一下""都存"），沿用上一轮的技能。
                3. 话题换了就按新话题判断，不要因为上一轮是某个技能就沿用。
                4. 让助手"在某个时间提醒我做某事"、新建或查询日程，归日程；但规划行程时顺带建提醒归出游，
                   选定上一轮推荐的餐厅并约时间（"就这家，周六晚上去"）归美食。
                5. 告诉助手关于自己的长期信息并要求记住或忘掉（忌口、伤病、家人），归通用。
                6. 只问天气、不涉及出行安排，归通用；为出行、爬山问天气或问适不适合去，归出游。
                7. 订机票、酒店，问交通路线、景点和攻略，都归出游。
                8. 找餐厅、吃什么、问某家店怎么样，归美食；规划行程时顺带安排吃饭归出游，
                   上一轮在出游里追问"附近有什么好吃的"按规则 1 沿用出游。
                只输出 JSON，skillId 必须是上面列出的 id 之一，reason 用一句中文说明理由。"""
                .formatted(skills, lastSkillId == null ? "无" : lastSkillId);
    }
}
