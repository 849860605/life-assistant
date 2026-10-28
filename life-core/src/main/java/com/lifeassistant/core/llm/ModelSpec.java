package com.lifeassistant.core.llm;

import java.util.Set;

/** 一个用途要用的模型：哪个提供方的哪个型号。 */
public record ModelSpec(String provider, String model) {

    /**
     * 解析"提供方:型号"。冒号前是已配置的提供方才切分，否则整串都是型号、提供方用 defaultProvider，
     * 这样 qwen2.5:7b 这类自带冒号的型号名也不会被拆错。
     */
    public static ModelSpec parse(String spec, String defaultProvider, Set<String> providers) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("模型配置为空");
        }
        String text = spec.trim();
        int colon = text.indexOf(':');
        if (colon > 0 && providers.contains(text.substring(0, colon))) {
            return new ModelSpec(text.substring(0, colon), text.substring(colon + 1));
        }
        return new ModelSpec(defaultProvider, text);
    }

    /** 记用量和日志用的完整写法，例如 deepseek:deepseek-chat。 */
    public String id() {
        return provider + ":" + model;
    }
}
