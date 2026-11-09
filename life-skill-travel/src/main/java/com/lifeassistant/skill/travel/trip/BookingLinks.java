package com.lifeassistant.skill.travel.trip;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 模型写进行程的链接只收常用订票、点评、地图平台的 https 地址：前端会直接打开它，
 * 防止网页或附件里的内容让模型塞进钓鱼链接。
 */
public final class BookingLinks {

    /** 允许的域名（本身或子域名） */
    static final List<String> HOSTS = List.of("ctrip.com", "trip.com", "fliggy.com", "taobao.com", "tmall.com",
            "meituan.com", "dianping.com", "dpurl.cn", "amap.com", "autonavi.com", "12306.cn", "tuniu.com", "qunar.com",
            "ly.com", "mafengwo.cn", "xiaohongshu.com", "ctrip.cn");

    /** 美团 MCP 给的点评短链是 http 的，这一个域名放行 http，其余只收 https */
    private static final String HTTP_OK = "dpurl.cn";

    private BookingLinks() {
    }

    /** 是允许平台的 https 链接（点评短链也可以是 http）就原样返回（去掉首尾空白），否则返回 null。 */
    public static String safe(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        if (u.length() > 1000 || !u.matches("(?i)^https?://\\S+$")) {
            return null;
        }
        try {
            URI uri = URI.create(u);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) {
                return null;
            }
            String h = host.toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !h.equals(HTTP_OK)) {
                return null;
            }
            return HOSTS.stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d)) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
