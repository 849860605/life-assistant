package com.lifeassistant.skill.travel.trip;

import com.lifeassistant.core.place.AmapPlaceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripGeoTest {

    private final AmapPlaceService places = mock(AmapPlaceService.class);
    private final TripGeo geo = new TripGeo(places);

    @Test
    void retriesFarAwayMatchByAddressAndDropsItIfStillFar() {
        when(places.available()).thenReturn(true);
        when(places.driving(any(), any())).thenReturn(Optional.empty());
        // 同名的"龙井村"在一百公里外，按地址能找到西湖边那个
        when(places.find(eq("龙井村茶山漫步"), any())).thenReturn(Optional.of(place("龙井村", 119.22, 30.16)));
        when(places.search(eq("西湖区龙井村"), any(), any(), anyInt())).thenReturn(List.of(place("龙井村", 120.13, 30.23)));
        // 另一个在远处、又没有地址，只能不要坐标
        when(places.find(eq("某某古镇"), any())).thenReturn(Optional.of(place("某某古镇", 118.5, 29.6)));

        Itinerary.Day day = new Itinerary.Day("2026-11-28", null, List.of(
                located("d2-1", "灵隐寺", 120.101, 30.239),
                located("d2-2", "河坊街", 120.168, 30.240),
                new Itinerary.Item("d2-3", "13:00", "15:00", "SPOT", "龙井村茶山漫步", "西湖区龙井村（龙井路两侧）", null, null, null, null),
                new Itinerary.Item("d2-4", "16:00", "17:00", "SPOT", "某某古镇", null, null, null, null, null)));

        List<Itinerary.Item> items = geo.relink(day, "杭州", List.of()).items();

        assertThat(items.get(2).lng()).isEqualTo(120.13);
        assertThat(items.get(3).located()).isFalse();
    }

    private static Itinerary.Item located(String id, String name, double lng, double lat) {
        return new Itinerary.Item(id, "09:00", "10:00", "SPOT", name, null, null, null, null, null, "p", lng, lat, null, null);
    }

    private static AmapPlaceService.Place place(String name, double lng, double lat) {
        return new AmapPlaceService.Place("id-" + name, name, "景点", null, null, lng, lat, 4.5, null, null, List.of(),
                null, null);
    }
}
