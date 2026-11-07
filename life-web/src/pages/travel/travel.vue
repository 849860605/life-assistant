<template>
  <Shell active="travel">
    <view class="page-wrap">
      <view>
        <view class="page-title">旅行服务</view>
        <view class="page-sub">查机票酒店、找美食和景点门票、看地图和天气。比价结果来自已接入的平台，下单在平台完成。</view>
      </view>
      <view class="list">
        <view v-for="s in SERVICES" :key="s.key" class="row" @click="go(s.url)">
          <Icon :name="s.icon" :size="20" color="var(--accent)" />
          <view class="row-body">
            <view class="row-title">{{ s.label }}</view>
            <view class="row-sub">{{ s.desc }}</view>
          </view>
          <Icon name="right" :size="16" color="var(--muted)" />
        </view>
      </view>
      <view v-if="trip" class="trip-hint" @click="go(`/pages/trips/detail?id=${trip.id}`)">
        <text class="muted">正在准备的行程：</text><text class="strong">{{ trip.title }}</text><text class="link">查看 ›</text>
      </view>
    </view>
  </Shell>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../../api";
import { getToken } from "../../api/http";
import type { Trip } from "../../api/types";
import Icon from "../../components/Icon.vue";
import Shell from "../../components/Shell.vue";
import { ymd } from "../../utils/format";

/** 旅行服务：机票、酒店、美食、景点门票、地图、天气六个入口（手机底部栏和电脑顶部导航都进这里）。 */
const SERVICES = [
  { key: "flight", label: "机票比价", icon: "plane", desc: "按日期查航班，比较各平台价格和退改规则", url: "/pages/search/flight" },
  { key: "hotel", label: "酒店比价", icon: "bed", desc: "按城市和入住日期找酒店，比较同房型报价", url: "/pages/search/hotel" },
  { key: "food", label: "美食", icon: "food", desc: "附近和目的地的餐厅，按菜系、人均、距离筛选", url: "/pages/search/food" },
  { key: "ticket", label: "景点门票", icon: "ticket", desc: "开放时间、预约和退改规则，比较门票价格", url: "/pages/search/ticket" },
  { key: "map", label: "地图", icon: "map", desc: "查地点、看路线，跳转高德导航或打车", url: "/pages/plan/map" },
  { key: "weather", label: "天气", icon: "weather", desc: "未来几天的天气和出行建议", url: "/pages/weather/weather" },
  { key: "discover", label: "热门目的地", icon: "compass", desc: "挑一个城市，看景点美食或让星络规划", url: "/pages/discover/discover" },
  { key: "fav", label: "我的收藏", icon: "heart", desc: "收藏的方案、酒店、餐厅和景点", url: "/pages/fav/fav" },
];

/** 最近一个还没结束的行程，提示可以按行程去查 */
const trip = ref<Trip | null>(null);

onMounted(() => {
  if (!getToken()) return;
  const today = ymd(new Date());
  api.trips().then((list) => {
    trip.value = list.find((t) => t.status !== "FINISHED" && (!t.endDate || t.endDate >= today)) || null;
  }).catch(() => {});
});

function go(url: string) {
  uni.navigateTo({ url });
}
</script>

<style scoped>
.list { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.row { display: flex; align-items: center; gap: 14px; padding: 18px 20px; cursor: pointer; border-bottom: 1px solid var(--line); }
.row:nth-child(odd) { border-right: 1px solid var(--line); }
.row:hover { background: var(--surface-2); }
.row-body { flex: 1; min-width: 0; }
.row-title { font-size: 16px; font-weight: 600; }
.row-sub { font-size: 13px; color: var(--muted); margin-top: 2px; }
.trip-hint { font-size: 14px; display: flex; gap: 4px; cursor: pointer; }
.strong { font-weight: 600; }
.muted { color: var(--muted); }
@media (max-width: 767px) {
  .list { grid-template-columns: 1fr; }
  .row:nth-child(odd) { border-right: none; }
}
</style>
