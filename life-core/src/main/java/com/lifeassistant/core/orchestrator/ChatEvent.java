package com.lifeassistant.core.orchestrator;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 推给前端的流式事件。
 * <ul>
 *   <li>route：路由到了哪个技能</li>
 *   <li>delta：回答的增量文本</li>
 *   <li>done：本轮结束</li>
 *   <li>error：出错，message 是给用户看的说明</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(String type, String skillId, String skillName, String text, String message) {

    public static ChatEvent route(String skillId, String skillName) {
        return new ChatEvent("route", skillId, skillName, null, null);
    }

    public static ChatEvent delta(String text) {
        return new ChatEvent("delta", null, null, text, null);
    }

    public static ChatEvent done() {
        return new ChatEvent("done", null, null, null, null);
    }

    public static ChatEvent error(String message) {
        return new ChatEvent("error", null, null, null, message);
    }
}
