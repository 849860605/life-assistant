<template>
  <Shell active="flight">
  <view class="page">
    <view class="seg">
      <text v-for="t in TABS" :key="t.key" class="seg-item" :class="{ on: tab === t.key }" @click="tab = t.key">{{ t.label }}</text>
    </view>

    <template v-if="tab === 'flight'">
      <view class="search">
        <view class="route">
          <text class="city">深圳</text>
          <view class="swap"><Icon name="swap" :size="16" color="var(--accent)" /></view>
          <text class="city right">西安</text>
        </view>
        <view class="fields">
          <view class="field"><Icon name="calendar" :size="14" color="var(--muted)" /><text>3月10日 去</text></view>
          <view class="field"><Icon name="calendar" :size="14" color="var(--muted)" /><text>3月13日 回</text></view>
          <view class="field"><Icon name="users" :size="14" color="var(--muted)" /><text>2成人 1儿童</text></view>
        </view>
        <button class="btn search-btn" @click="search"><Icon name="search" :size="16" color="#fff" /><text>搜索</text></button>
      </view>
      <view class="filters">
        <view v-for="f in ['直飞优先', '价格优先', '航空公司']" :key="f" class="filter">{{ f }}<Icon name="down" :size="12" color="var(--muted)" /></view>
        <text class="tag">示例</text>
      </view>
      <view class="list">
        <view v-for="f in FLIGHTS" :key="f.flightNo" class="flight">
          <view class="logo" :style="{ background: f.color }">{{ f.short }}</view>
          <view class="info">
            <view class="airline">{{ f.airline }} <text class="muted">{{ f.flightNo }}</text></view>
            <view class="times">
              <view class="t"><text class="time">{{ f.dep }}</text><text class="ap">{{ f.depAirport }}</text></view>
              <view class="dur"><text>{{ f.duration }}</text><view class="line" /></view>
              <view class="t"><text class="time">{{ f.arr }}</text><text class="ap">{{ f.arrAirport }}</text></view>
            </view>
          </view>
          <view class="side">
            <text class="price">¥{{ f.price }}<text class="qi">起</text></text>
            <text class="platform">{{ f.platform }}最低</text>
            <button class="btn small view" @click="book(f.platform)">查看</button>
          </view>
        </view>
      </view>
      <view class="foot">按最低价排序，比较飞猪、携程、美团三个平台；部分链接含推广参数，不影响排序。</view>
    </template>

    <template v-else-if="tab === 'hotel'">
      <HotelList />
    </template>

    <view v-else class="empty">
      <Icon name="ticket" :size="36" color="var(--muted)" />
      <view>门票比价还在找数据源</view>
      <view class="muted small">先在行程里看景点开放时间和门票提醒</view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Icon from "../../components/Icon.vue";
import HotelList from "../../components/HotelList.vue";
import { FLIGHTS } from "../../demo/xian";

/** 机票比价（原型⑧），和酒店、门票放在同一页用顶部切换。 */
const TABS = [
  { key: "flight", label: "机票" },
  { key: "hotel", label: "酒店" },
  { key: "ticket", label: "门票" },
];
const tab = ref("flight");

onLoad((query) => {
  if (query?.tab) tab.value = query.tab;
  uni.setNavigationBarTitle({ title: tab.value === "hotel" ? "酒店比价" : "机票比价" });
});

/** 示例页的搜索交给助手，真实比价由对话里的工具完成。 */
function search() {
  uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent("查一下3月10日深圳飞西安的机票") });
}

function book(platform: string) {
  uni.showToast({ title: `将跳转到${platform}预订页（示例）`, icon: "none" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.seg { display: flex; background: var(--surface); border-radius: 12px; padding: 4px; border: 1px solid var(--line); margin-bottom: 12px; }
.seg-item { flex: 1; text-align: center; padding: 8px 0; border-radius: 9px; font-size: 14px; color: var(--muted); cursor: pointer; }
.seg-item.on { background: var(--accent); color: #fff; font-weight: 600; }
.search { background: var(--surface); border-radius: 14px; padding: 14px; border: 1px solid var(--line); }
.route { display: flex; align-items: center; }
.city { flex: 1; font-size: 22px; font-weight: 800; }
.city.right { text-align: right; }
.swap { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; }
.fields { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.field { flex: 1; min-width: 90px; display: flex; align-items: center; gap: 4px; font-size: 13px; background: var(--surface-2); border-radius: 8px; padding: 8px 10px; }
.search-btn { margin: 12px 0 0; width: 100%; gap: 6px; }
.filters { display: flex; gap: 14px; align-items: center; margin: 12px 2px; font-size: 13px; color: var(--muted); }
.filter { display: flex; align-items: center; gap: 2px; cursor: pointer; }
.tag { margin-left: auto; font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); }
.list { display: flex; flex-direction: column; gap: 10px; }
.flight { display: flex; align-items: center; gap: 10px; background: var(--surface); border-radius: 14px; padding: 12px; border: 1px solid var(--line); }
.logo { width: 36px; height: 36px; border-radius: 50%; color: #fff; font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.info { flex: 1; min-width: 0; }
.airline { font-size: 13px; font-weight: 600; }
.times { display: flex; align-items: center; gap: 8px; margin-top: 4px; }
.t { display: flex; flex-direction: column; }
.time { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; }
.ap { font-size: 11px; color: var(--muted); }
.dur { flex: 1; display: flex; flex-direction: column; align-items: center; font-size: 11px; color: var(--muted); }
.line { width: 100%; border-top: 1px solid var(--line); margin-top: 2px; }
.side { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.price { color: var(--price); font-size: 18px; font-weight: 800; font-variant-numeric: tabular-nums; }
.qi { font-size: 11px; font-weight: 400; }
.platform { font-size: 11px; color: var(--muted); }
.view { margin: 4px 0 0; }
.foot { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 60px 0; }
.small { font-size: 12px; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
