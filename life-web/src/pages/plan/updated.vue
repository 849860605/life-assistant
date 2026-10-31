<template>
  <Shell active="plan">
  <view class="page">
    <view class="hero">
      <view class="party"><Icon name="party" :size="34" color="var(--price)" /></view>
      <view class="title">行程已更新！</view>
      <view class="sub">{{ change }}，已重新规划路线、时间和预算</view>
    </view>
    <view class="compare">
      <view class="side before">
        <view class="side-title">调整前</view>
        <view class="kv"><text>总里程</text><text>28 km</text></view>
        <view class="kv"><text>总时长</text><text>6.5 小时</text></view>
        <view class="kv"><text>预计费用</text><text>¥520/人</text></view>
      </view>
      <Icon name="right" :size="20" color="var(--muted)" />
      <view class="side after">
        <view class="side-title">调整后</view>
        <view class="kv"><text>总里程</text><text class="better">24 km</text></view>
        <view class="kv"><text>总时长</text><text class="better">6.2 小时</text></view>
        <view class="kv"><text>预计费用</text><text class="better">¥500/人</text></view>
      </view>
    </view>
    <image class="map" :src="ROUTE_MAP" mode="aspectFill" />
    <view class="note">示例数据：接入后由高德路径规划重新计算。</view>
    <button class="btn primary" @click="view">查看新的行程</button>
    <button class="btn ghost" @click="again">继续调整</button>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Icon from "../../components/Icon.vue";
import { ROUTE_MAP } from "../../demo/xian";

/** 生成新方案 / 确认（原型⑪）：调整前后的里程、时长、费用对比。 */
const from = ref("");
const to = ref("");
const change = computed(() => (from.value && to.value ? `「${from.value}」已换成「${to.value}」` : "已按你的调整"));

onLoad((query) => {
  from.value = query?.from ? decodeURIComponent(query.from) : "";
  to.value = query?.to ? decodeURIComponent(query.to) : "";
});

function view() {
  uni.redirectTo({ url: "/pages/trips/detail?id=demo" });
}

function again() {
  uni.redirectTo({ url: "/pages/plan/edit" });
}
</script>

<style scoped>
.page { max-width: 520px; margin: 0 auto; padding: 20px 16px 28px; box-sizing: border-box; display: flex; flex-direction: column; gap: 14px; }
.hero { text-align: center; }
.party { width: 64px; height: 64px; margin: 0 auto 8px; border-radius: 50%; background: #fff4ea; display: flex; align-items: center; justify-content: center; }
.title { font-size: 22px; font-weight: 800; }
.sub { font-size: 13px; color: var(--muted); margin-top: 4px; }
.compare { display: flex; align-items: center; gap: 8px; }
.side { flex: 1; border-radius: 14px; padding: 12px; background: var(--surface); border: 1px solid var(--line); }
.side.after { background: var(--accent-soft); border-color: var(--accent); }
.side-title { font-weight: 700; margin-bottom: 6px; }
.after .side-title { color: var(--accent); }
.kv { display: flex; justify-content: space-between; font-size: 13px; margin-top: 3px; font-variant-numeric: tabular-nums; }
.kv text:first-child { color: var(--muted); }
.better { color: var(--accent); font-weight: 600; }
.map { width: 100%; height: 180px; border-radius: 14px; }
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: -6px; }
.btn { margin: 0; width: 100%; }
.btn.primary { height: 46px; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
