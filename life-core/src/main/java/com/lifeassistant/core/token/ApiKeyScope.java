package com.lifeassistant.core.token;

/** 令牌用途，每个令牌只授予一种。 */
public enum ApiKeyScope {
    /** 系统日历订阅日程（ICS） */
    CALENDAR_FEED,
    /** iOS 快捷指令同步 Apple 健康（S1） */
    HEALTH_SYNC
}
