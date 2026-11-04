package com.lifeassistant.core.integration;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 按配置缓存一个对象（比如带地址和 Key 的 RestClient）：配置没变就复用，后台改了配置下次取时重建。
 */
public final class ByConfig<T> {

    private Object key;
    private T value;

    /** 取对象；key 是决定这个对象的配置（比如地址 + Key），和上次不同就用 build 重建。 */
    public synchronized T get(Object key, Supplier<T> build) {
        if (value == null || !Objects.equals(this.key, key)) {
            value = build.get();
            this.key = key;
        }
        return value;
    }
}
