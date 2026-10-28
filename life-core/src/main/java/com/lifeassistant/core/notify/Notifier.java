package com.lifeassistant.core.notify;

/**
 * 推送渠道。先接 Bark，以后加 ntfy、小程序订阅消息、Web Push 时新增实现即可。
 */
public interface Notifier {

    /** 渠道类型，对应 t_notify_channel.type */
    String type();

    /**
     * 按渠道配置发送一条通知，失败时抛异常。
     *
     * @param channelConfig 解密后的渠道配置 JSON，例如 Bark 的设备 key
     */
    void send(String channelConfig, Notice notice);
}
