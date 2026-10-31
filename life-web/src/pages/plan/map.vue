<template>
  <Shell active="map">
  <view class="page">
    <view class="head">
      <text class="title">第1天 · 3月10日</text>
      <text class="tag">示例</text>
    </view>
    <view class="layout">
      <view class="stops">
        <view v-for="s in DAY1_ROUTE" :key="s.no" class="stop">
          <view class="no">{{ s.no }}</view>
          <view class="stop-body">
            <view class="name">{{ s.name }}</view>
            <view class="time">{{ s.time }}</view>
            <view v-if="s.next" class="next"><Icon name="car" :size="12" color="var(--muted)" /> 到下一站 {{ s.next }}</view>
          </view>
          <image class="thumb" :src="s.image" mode="aspectFill" />
        </view>
      </view>
      <view class="map-wrap">
        <image class="map" :src="ROUTE_MAP" mode="widthFix" />
        <view class="map-tools">
          <view class="tool"><Icon name="locate" :size="18" color="var(--ink)" /></view>
          <view class="tool"><Icon name="plus" :size="18" color="var(--ink)" /></view>
        </view>
      </view>
    </view>
    <view class="actions">
      <view class="action" @click="toast('路线已按距离优化')"><Icon name="route" :size="16" color="var(--accent)" /><text>路线优化</text></view>
      <view class="action" @click="toast('公共交通约 52 分钟')"><Icon name="bus" :size="16" color="var(--accent)" /><text>公共交通</text></view>
      <view class="action" @click="toast('自驾约 45 分钟')"><Icon name="car" :size="16" color="var(--accent)" /><text>自驾导航</text></view>
    </view>
    <view class="note">地图和驾车时长来自高德；实时导航在阶段二接入。</view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import Icon from "../../components/Icon.vue";
import { DAY1_ROUTE, ROUTE_MAP } from "../../demo/xian";

/** 地图路线（原型⑥）：当天的站点列表 + 路线地图（高德静态地图）。 */
function toast(title: string) {
  uni.showToast({ title, icon: "none" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.title { font-size: 18px; font-weight: 700; color: var(--accent); }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.layout { display: flex; flex-direction: column; gap: 12px; }
.stops { background: var(--surface); border-radius: 14px; padding: 4px 12px; border: 1px solid var(--line); }
.stop { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.stop:last-child { border-bottom: none; }
.no {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ok);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stop-body { flex: 1; min-width: 0; }
.name { font-weight: 600; }
.time, .next { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.next { display: flex; align-items: center; gap: 4px; }
.thumb { width: 64px; height: 48px; border-radius: 8px; flex-shrink: 0; }
.map-wrap { position: relative; border-radius: 14px; overflow: hidden; border: 1px solid var(--line); }
.map { width: 100%; display: block; }
.map-tools { position: absolute; right: 10px; top: 10px; display: flex; flex-direction: column; gap: 8px; }
.tool { width: 34px; height: 34px; border-radius: 8px; background: #fff; display: flex; align-items: center; justify-content: center; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12); }
.actions { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 12px; }
.action {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 0;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  font-size: 13px;
  cursor: pointer;
}
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
@media (min-width: 700px) {
  .layout { flex-direction: row; }
  .stops { width: 300px; flex-shrink: 0; align-self: flex-start; }
  .map-wrap { flex: 1; }
}
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
