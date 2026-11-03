package com.lifeassistant.core.brief;

import java.time.LocalDate;
import java.util.List;

/**
 * 一份每日简报：几段内容，每段一个小标题和几行字。全部来自天气、日程、行程这些数据，不经过模型，数字不会编。
 */
public record Brief(String date, String title, List<Section> sections) {

    /** 一段：key 给前端选图标，url 是点开去的页面。 */
    public record Section(String key, String title, List<String> lines, String url) {
    }

    /**
     * 生成简报时各段共享的信息：homeCity 是看天气的城市；away 由行程段设置，
     * 人在外地旅行时就不再报家里的天气。
     */
    public static final class Context {
        private final long userId;
        private final LocalDate today;
        private final String homeCity;
        private boolean away;

        public Context(long userId, LocalDate today, String homeCity) {
            this.userId = userId;
            this.today = today;
            this.homeCity = homeCity;
        }

        public long userId() {
            return userId;
        }

        public LocalDate today() {
            return today;
        }

        public String homeCity() {
            return homeCity;
        }

        public boolean away() {
            return away;
        }

        public void markAway() {
            this.away = true;
        }
    }
}
