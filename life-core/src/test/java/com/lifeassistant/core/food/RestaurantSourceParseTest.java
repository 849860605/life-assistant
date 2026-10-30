package com.lifeassistant.core.food;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 用美团、携程真实返回的餐厅数据（2026-10-29 抓的西安钟楼附近陕菜）验证解析和合并。 */
class RestaurantSourceParseTest {

    @Test
    void meituanShopsWithDianpingLinks() throws IOException {
        RestaurantSource.Result result = MeituanRestaurantSource.parse(read("meituan.md"));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.shops()).hasSize(4);
        RestaurantSource.Shop first = result.shops().getFirst();
        assertThat(first.name()).isEqualTo("传承长安陕菜馆（钟楼地标店）");
        assertThat(first.rating()).isEqualTo(4.7);
        assertThat(first.location()).isEqualTo("约0.5公里");
        assertThat(first.url()).isEqualTo("http://dpurl.cn/8nXoYhfz");
        assertThat(first.highlight()).doesNotContain("**");
    }

    @Test
    void meituanAvgPrice() {
        RestaurantSource.Result result = MeituanRestaurantSource.parse("""
                [**海底捞火锅（南山茂业店）**](http://dpurl.cn/CxfASnuz)
                这家店位于南山中心区茂业百货，**评分高达4.7分**，**人均约116元**。""");

        RestaurantSource.Shop shop = result.shops().getFirst();
        assertThat(shop.rating()).isEqualTo(4.7);
        assertThat(shop.avgPrice()).isEqualTo(116);
    }

    @Test
    void ctripShopsFromFields() throws IOException {
        RestaurantSource.Result result = CtripRestaurantSource.parse(read("ctrip.md"));

        assertThat(result.shops()).extracting(RestaurantSource.Shop::name).containsExactly(
                "西安饭庄·非遗陕菜(钟楼店)", "德发长饺子·非遗(钟楼店)", "西安贾三灌汤包·回坊小吃·清真炒菜(北院门总店)",
                "长安记·传承陕菜·长安三万里(钟楼店)", "奔跑吧陕菜·常来长安(钟楼地标店)");
        RestaurantSource.Shop first = result.shops().getFirst();
        assertThat(first.rating()).isEqualTo(4.6);
        assertThat(first.avgPrice()).isEqualTo(100);
        assertThat(first.dishes()).startsWith("葫芦鸡");
        assertThat(first.hours()).isEqualTo("11:00-14:00，17:00-21:00");
        // "人均：网络参考约80-100元" 取第一个数
        assertThat(result.shops().get(3).avgPrice()).isEqualTo(80);
    }

    @Test
    void ctripNameKeepsBranchDropsTag() {
        assertThat(CtripRestaurantSource.cleanName("<西安饭庄·非遗陕菜(钟楼店)>（首选）")).isEqualTo("西安饭庄·非遗陕菜(钟楼店)");
        assertThat(CtripRestaurantSource.cleanName("长安记(钟楼店)（强烈推荐）")).isEqualTo("长安记(钟楼店)");
    }

    @Test
    void unparsableKeepsRaw() {
        RestaurantSource.Result result = MeituanRestaurantSource.parse("附近没有找到符合条件的餐厅");

        assertThat(result.status()).isEqualTo("EMPTY");
        assertThat(result.raw()).contains("没有找到");
    }

    @Test
    void mergeSameShopAcrossPlatforms() throws IOException {
        List<FoodTools.Restaurant> merged = FoodTools.merge(List.of(
                MeituanRestaurantSource.parse(read("meituan.md")),
                CtripRestaurantSource.parse(read("ctrip.md"))));

        // "奔跑吧陕菜·常来长安" 两个平台都推荐，排最前；评分用点评的，招牌菜用携程的
        FoodTools.Restaurant both = merged.getFirst();
        assertThat(both.name()).isEqualTo("奔跑吧陕菜·常来长安（钟楼地标店）");
        assertThat(both.platforms()).containsExactly("美团", "携程");
        assertThat(both.rating()).isEqualTo(4.5);
        assertThat(both.ratingFrom()).isEqualTo("大众点评");
        assertThat(both.dishes()).contains("葫芦鸡");
        assertThat(both.dianpingUrl()).isEqualTo("http://dpurl.cn/wvgup40z");
        assertThat(both.display()).isEqualTo("[奔跑吧陕菜·常来长安（钟楼地标店）](http://dpurl.cn/wvgup40z)");
        // 只有携程推荐的没有点评链接，display 就是纯店名
        assertThat(merged).filteredOn(r -> r.name().startsWith("西安饭庄")).singleElement()
                .extracting(FoodTools.Restaurant::display).isEqualTo("西安饭庄·非遗陕菜(钟楼店)");
        assertThat(merged).hasSize(8);
    }

    @Test
    void sameShopMatchesBrandAndBranch() {
        assertThat(FoodTools.sameShop("西安饭庄·非遗陕菜（钟楼店）", "西安饭庄(钟楼店)")).isTrue();
        assertThat(FoodTools.sameShop("海底捞火锅（南山茂业店）", "海底捞火锅（海岸城店）")).isFalse();
    }

    private String read(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/food/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
