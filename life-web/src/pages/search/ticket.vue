<template>
  <Shell active="ticket">
  <view class="page">
    <view class="head">
      <text class="title">{{ city ? city + " · " : "" }}景点门票</text>
      <text class="muted">查门票价格、票种和预约规则，从行程进来会列出每天的门票</text>
    </view>
    <TicketBoard v-if="ready" :query="query" />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Shell from "../../components/Shell.vue";
import TicketBoard from "../../components/TicketBoard.vue";

/** 景点门票（左侧独立菜单）：支持 ?city=&keyword=&trip=&day=，带 trip 时右边列出行程某天的门票。 */
const query = ref<Record<string, string | undefined>>({});
const ready = ref(false);
const city = ref("");

onLoad((q) => {
  query.value = q || {};
  city.value = q?.city ? decodeURIComponent(q.city) : "";
  ready.value = true;
});
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.title { font-size: 18px; font-weight: 800; }
.muted { color: var(--muted); font-size: 13px; }
@media (min-width: 1024px) {
  .page { max-width: 1600px; padding: 20px 24px; }
  .title { font-size: 22px; }
}
</style>
