package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.core.place.AmapPlaceService;

/**
 * 大交通的智能推荐：按两地直线距离给一种交通方式和一句理由，需求卡上默认勾"智能推荐"时显示，用户可以自己改。
 * 规则：去港澳且很近走地铁过口岸，稍远坐高铁到西九龙；同城或 60 公里内地铁公交；60～800 公里高铁；更远坐飞机。
 */
public record TransportAdvice(String mode, String reason) {

    public static final String METRO = "地铁公交";
    public static final String TRAIN = "高铁火车";
    public static final String FLIGHT = "飞机";

    /** 出发地或目的地没填、高德查不到时返回 null（卡片上就不显示推荐理由）。 */
    public static TransportAdvice of(AmapPlaceService places, String origin, String destination) {
        if (places == null || origin == null || destination == null || origin.isBlank() || destination.isBlank()
                || !places.available()) {
            return null;
        }
        double[] from;
        double[] to;
        try {
            from = places.center(origin.trim()).orElse(null);
            to = places.center(destination.trim()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
        if (from == null || to == null) {
            return null;
        }
        int km = (int) Math.round(distanceKm(from, to));
        boolean hkMacau = destination.contains("香港") || destination.contains("澳门");
        if (hkMacau && km <= 60) {
            return new TransportAdvice(METRO, "约 " + km + " 公里，坐地铁过口岸最方便");
        }
        if (hkMacau && km <= 800) {
            return new TransportAdvice(TRAIN, "约 " + km + " 公里，高铁直达" + (destination.contains("香港") ? "香港西九龙" : "珠海再过关"));
        }
        if (km <= 60) {
            return new TransportAdvice(METRO, "约 " + km + " 公里，地铁公交或打车就到");
        }
        if (km <= 800) {
            return new TransportAdvice(TRAIN, "约 " + km + " 公里，高铁省时又不用提前到机场");
        }
        return new TransportAdvice(FLIGHT, "约 " + km + " 公里，坐飞机最快");
    }

    /** 两点直线距离（公里），坐标是经度、纬度。 */
    static double distanceKm(double[] a, double[] b) {
        double r = 6371;
        double dLat = Math.toRadians(b[1] - a[1]);
        double dLng = Math.toRadians(b[0] - a[0]);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[1])) * Math.cos(Math.toRadians(b[1])) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
