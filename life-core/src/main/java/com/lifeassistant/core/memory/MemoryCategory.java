package com.lifeassistant.core.memory;

/** 记忆分类，同时决定是否按敏感数据处理。 */
public enum MemoryCategory {
    PROFILE(false),
    PREFERENCE(false),
    RELATION(false),
    HEALTH(true),
    FINANCE(true);

    /** 健康、财务类默认按敏感数据处理：加密存储，默认不进 Prompt */
    private final boolean sensitive;

    MemoryCategory(boolean sensitive) {
        this.sensitive = sensitive;
    }

    /** 是否敏感：敏感类加密存储，且不进 Prompt。 */
    public boolean sensitive() {
        return sensitive;
    }
}
