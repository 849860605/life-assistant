package com.lifeassistant.core.executor;

/** 执行层级，执行路由按顺序选第一个可用的方式，失败后降级。 */
public enum ExecTier {
    /** 官方接口或 Skill */
    OFFICIAL,
    /** AI 做决定，生成跳转链接，用户点一下自己完成 */
    SEMI_AUTO,
    /** 手机自动操作（延后） */
    GUI_AGENT
}
