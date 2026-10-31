package com.lifeassistant.core.card;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 推给前端的一张卡片：kind 决定前端用哪个组件，data 是组件要的数据；summary 是文字版，
 * 下一轮对话时附在助手消息后面给模型看（用户说"就第二家"时模型知道是哪家）。
 * offset 是卡片出现时回答已经输出的字数，前端据此把卡片插在文字中间。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Card(String kind, Object data, String summary, Integer offset) {

    /** 记下卡片在回答里的位置。 */
    public Card at(int offset) {
        return new Card(kind, data, summary, offset);
    }
}
