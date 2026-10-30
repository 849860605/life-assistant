package com.lifeassistant.core.food;

import java.util.List;

/** 一个平台的餐厅查询来源（美团/大众点评、携程……），按城市、位置、口味等条件查餐厅。 */
public interface RestaurantSource {

    String platform();

    boolean available();

    /** 查餐厅；出错时返回 status=FAILED 的结果，不抛异常。 */
    Result search(Query query);

    /**
     * 查询条件：city 城市，near 位置（地标、商圈、酒店，可空），cuisine 菜系或想吃的（可空），
     * maxPrice 人均上限（元，可空），scene 场景（带孩子、约会、宴请，可空）。
     */
    record Query(String city, String near, String cuisine, Integer maxPrice, String scene) {

        /** 拼成给平台的完整问题，要求列出评分、人均、招牌菜和位置。 */
        public String question() {
            StringBuilder q = new StringBuilder(shortQuestion()).append("推荐");
            if (maxPrice != null) {
                q.append("，人均").append(maxPrice).append("元以内");
            }
            if (hasText(scene)) {
                q.append("，适合").append(scene);
            }
            return q.append("，列出评分、人均、招牌菜和位置").toString();
        }

        /** 简短问法，例如"西安钟楼附近陕菜餐厅"。 */
        public String shortQuestion() {
            return (city == null ? "" : city) + (hasText(near) ? near + "附近" : "")
                    + (hasText(cuisine) ? cuisine : "") + "餐厅";
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * 一家餐厅。rating 是平台评分（5 分制），avgPrice 是人均（元），dishes 招牌菜，location 地址或距离，
     * hours 营业时间，highlight 平台给的亮点描述，url 是该平台的店铺页（没有为 null）。
     */
    record Shop(String name, Double rating, Integer avgPrice, String dishes, String location, String hours,
                String highlight, String url) {
    }

    /** 一个平台的查询结果：status 为 OK / EMPTY / FAILED；解析不出店铺但有原文时 raw 带原文，交给模型自己读。 */
    record Result(String platform, String status, List<Shop> shops, String note, String raw) {

        static Result of(String platform, List<Shop> shops, String raw) {
            return shops.isEmpty() ? new Result(platform, "EMPTY", shops, "没解析出店铺，原文见 raw", raw)
                    : new Result(platform, "OK", shops, null, null);
        }

        static Result failed(String platform, String reason) {
            return new Result(platform, "FAILED", List.of(), reason, null);
        }
    }
}
