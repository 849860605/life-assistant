<template>
  <Shell active="hotel">
  <view class="page">
    <view class="head">
      <text class="title">机票酒店 · 酒店比价</text>
      <view class="views">
        <text class="view-tab" @click="toFlight">机票比价</text>
        <text class="view-tab on">酒店比价</text>
      </view>
    </view>
    <HotelBoard v-if="ready" :query="query" />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Shell from "../../components/Shell.vue";
import HotelBoard from "../../components/HotelBoard.vue";

/**
 * 酒店比价（原型⑨）：左侧"机票酒店 → 酒店比价"进来是独立查询；从行程里点"比价换酒店"会带上 trip（和 stay），
 * 自动填目的地、日期和地标，比完可以预览后替换行程里的酒店。支持 ?city=&place=&checkIn=&checkOut=&trip=&stay=
 */
const query = ref<Record<string, string | undefined>>({});
const ready = ref(false);

onLoad((q) => {
  query.value = q || {};
  ready.value = true;
});

function toFlight() {
  const trip = query.value.trip;
  uni.redirectTo({ url: "/pages/search/flight?tab=flight" + (trip ? `&trip=${trip}` : "") });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.title { font-size: 18px; font-weight: 800; flex: 1; min-width: 0; }
.views { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; }
.view-tab { font-size: 13px; padding: 6px 12px; border-radius: 8px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.view-tab.on { background: var(--accent); color: #fff; font-weight: 600; }
@media (min-width: 1024px) {
  .page { max-width: 1600px; padding: 20px 24px; }
  .title { font-size: 22px; }
}
</style>
