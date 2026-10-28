package com.lifeassistant.core.memory;

/** 记忆来源，决定置信度。 */
public enum MemorySource {
    /** 用户明确说的 */
    USER_STATED,
    /** AI 从对话中推断的，用户可以在"我的"页面纠正 */
    AI_INFERRED,
    /** 从外部数据导入的 */
    IMPORTED
}
