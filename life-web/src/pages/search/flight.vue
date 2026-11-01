<template>
  <Shell :active="tab === 'hotel' ? 'hotel' : tab === 'ticket' ? 'ticket' : 'flight'">
  <view class="page">
    <view v-if="!wide" class="seg">
      <text v-for="t in TABS" :key="t.key" class="seg-item" :class="{ on: tab === t.key }" @click="tab = t.key">{{ t.label }}</text>
    </view>

    <FlightBoard v-if="tab === 'flight' && ready" :query="pageQuery" />

    <template v-else-if="tab === 'hotel'">
      <HotelBoard v-if="ready" :query="pageQuery" />
    </template>

    <TicketBoard v-else-if="ready" :query="pageQuery" />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { ref } from "vue";
import { useWide } from "../../utils/screen";
import { onLoad } from "@dcloudio/uni-app";
import HotelBoard from "../../components/HotelBoard.vue";
import TicketBoard from "../../components/TicketBoard.vue";
import FlightBoard from "../../components/FlightBoard.vue";

/**
 * 机票比价（原型⑧）：飞猪、携程、美团比价，可筛选、看推荐理由和退改行李，带 trip 参数时和行程联动；
 * 和酒店、门票放在同一页用顶部切换。
 */
const TABS = [
  { key: "flight", label: "机票" },
  { key: "hotel", label: "酒店" },
  { key: "ticket", label: "门票" },
];
const tab = ref("flight");
/** 电脑上左侧菜单已经有机票、酒店、门票，顶部切换只在手机上显示 */
const wide = useWide();
/** 页面参数，酒店标签用它预填表单 */
const pageQuery = ref<Record<string, string | undefined>>({});
/** 页面参数到了再渲染机票比价，免得组件拿不到 trip 等参数 */
const ready = ref(false);

onLoad((query) => {
  pageQuery.value = query || {};
  if (query?.tab) tab.value = query.tab;
  uni.setNavigationBarTitle({ title: tab.value === "hotel" ? "酒店比价" : tab.value === "ticket" ? "门票" : "机票比价" });
  ready.value = true;
});

</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.seg { display: flex; background: var(--surface); border-radius: 12px; padding: 4px; border: 1px solid var(--line); margin-bottom: 12px; }
.seg-item { flex: 1; text-align: center; padding: 8px 0; border-radius: 9px; font-size: 14px; color: var(--muted); cursor: pointer; }
.seg-item.on { background: var(--accent); color: #fff; font-weight: 600; }
.search { background: var(--surface); border-radius: 14px; padding: 14px; border: 1px solid var(--line); }
.route { display: flex; align-items: center; gap: 8px; }
.city { flex: 1; min-width: 0; font-size: 22px; font-weight: 800; height: 36px; }
.city.right { text-align: right; }
.swap { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.fields { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.fields picker { flex: 1; min-width: 140px; }
.field { flex: 1; min-width: 90px; display: flex; align-items: center; gap: 4px; font-size: 13px; background: var(--surface-2); border-radius: 8px; padding: 8px 10px; cursor: pointer; }
.search-btn { margin: 12px 0 0; width: 100%; gap: 6px; }
.loading, .error { font-size: 13px; color: var(--muted); text-align: center; padding: 24px 0; }
.error { color: #c2560c; }
.result { margin-top: 12px; }
.foot { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 60px 0; }
.ask-btn { margin-top: 8px; }
.small { font-size: 12px; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1500px; padding: 24px 28px; }
}
</style>
