<template>
  <view>
    <view class="sorts">
      <text v-for="s in SORTS" :key="s" class="sort" :class="{ on: sort === s }" @click="sort = s">{{ s }}</text>
      <text class="tag">示例</text>
    </view>
    <view class="list">
      <view v-for="h in list" :key="h.name" class="hotel">
        <image class="img" :src="h.image" mode="aspectFill" />
        <view class="info">
          <view class="name">{{ h.name }}</view>
          <view class="meta"><text class="rating">★ {{ h.rating }}</text><text class="muted"> 高德评分</text></view>
          <view class="meta muted">{{ h.distance }}</view>
          <view class="tags"><text v-for="t in h.tags" :key="t" class="tag-chip">{{ t }}</text></view>
        </view>
        <view class="side">
          <text class="price">¥{{ h.price }}<text class="qi">起</text></text>
          <text class="platform">{{ h.platform }}最低</text>
          <button class="btn small view" @click="book(h.platform)">查看</button>
        </view>
      </view>
    </view>
    <view class="foot">"推荐排序"按评分和距离，不看佣金；价格以预订页为准，部分链接含推广参数。</view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { HOTELS } from "../demo/xian";

/** 酒店列表（原型⑨）：推荐（评分高、离得近）、价格、评分三种排序。 */
const SORTS = ["推荐排序", "价格排序", "评分优先"];
const sort = ref(SORTS[0]);
const list = computed(() => {
  const l = [...HOTELS];
  if (sort.value === "价格排序") l.sort((a, b) => a.price - b.price);
  if (sort.value === "评分优先") l.sort((a, b) => b.rating - a.rating);
  return l;
});

function book(platform: string) {
  uni.showToast({ title: `将跳转到${platform}预订页（示例）`, icon: "none" });
}
</script>

<style scoped>
.sorts { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.sort { font-size: 13px; padding: 5px 12px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.sort.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); font-weight: 600; }
.tag { margin-left: auto; font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.list { display: flex; flex-direction: column; gap: 10px; }
.hotel { display: flex; gap: 10px; background: var(--surface); border-radius: 14px; padding: 10px; border: 1px solid var(--line); }
.img { width: 96px; height: 96px; border-radius: 10px; flex-shrink: 0; }
.info { flex: 1; min-width: 0; }
.name { font-weight: 700; font-size: 14px; }
.meta { font-size: 12px; margin-top: 2px; }
.rating { color: var(--price); font-weight: 600; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.tag-chip { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--ok-soft); color: var(--ok); }
.side { display: flex; flex-direction: column; align-items: flex-end; justify-content: space-between; }
.price { color: var(--price); font-size: 18px; font-weight: 800; font-variant-numeric: tabular-nums; }
.qi { font-size: 11px; font-weight: 400; }
.platform { font-size: 11px; color: var(--muted); }
.view { margin: 0; }
.foot { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
</style>
