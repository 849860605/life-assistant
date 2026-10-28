package com.lifeassistant.core.notify;

/**
 * 一条与渠道无关的推送内容，各渠道按需取用其中的字段。
 *
 * @param group 通知分组，例如"出游""健身"
 * @param url   点击通知打开的页面
 * @param level Bark 的提醒级别：active / timeSensitive（专注模式下也弹出）/ passive / critical
 */
public record Notice(String title, String body, String group, String url, String level) {

    /** 普通级别（active）的简单通知，不分组、不带跳转链接。 */
    public static Notice of(String title, String body) {
        return new Notice(title, body, null, null, "active");
    }
}
