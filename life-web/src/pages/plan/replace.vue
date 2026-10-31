<template>
  <Shell active="plan">
  <view class="page">
    <view class="head">
      <view class="avatar"><Icon name="sparkle" :size="18" color="#fff" /></view>
      <view>
        <view class="title">替换景点：{{ name }}</view>
        <view class="sub">已去过？不想去？推荐类似的地方 <text class="tag">示例</text></view>
      </view>
    </view>
    <view class="tabs">
      <text v-for="t in TABS" :key="t" class="tab" :class="{ active: tab === t }" @click="tab = t">{{ t }}</text>
    </view>
    <view class="grid">
      <view v-for="r in list" :key="r.name" class="place">
        <image class="img" :src="r.image" mode="aspectFill" />
        <view class="info">
          <view class="name">{{ r.name }}</view>
          <view class="meta"><text class="rating">★ {{ r.rating }}</text><text class="muted"> · {{ r.tags[0] }}</text></view>
          <view class="meta muted">距原地点 {{ r.distance }}</view>
          <button class="btn ghost small pick" @click="pick(r.name)">替换为该景点</button>
        </view>
      </view>
    </view>
    <view class="note">评分来自高德地图，距离为示例值。</view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Icon from "../../components/Icon.vue";
import { REPLACEMENTS } from "../../demo/xian";

/** 景点替换（原型⑤）：推荐相似地点，选一个替换后回到行程并重算路线。 */
const TABS = ["推荐替代", "同区域景点", "小众推荐"];
const tab = ref(TABS[0]);
const name = ref("回民街钟鼓楼");
const list = computed(() => REPLACEMENTS.filter((r) => r.name !== name.value));

onLoad((query) => {
  if (query?.name) name.value = decodeURIComponent(query.name);
});

function pick(to: string) {
  uni.redirectTo({ url: `/pages/plan/updated?from=${encodeURIComponent(name.value)}&to=${encodeURIComponent(to)}` });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.avatar { width: 38px; height: 38px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.title { font-weight: 700; font-size: 16px; }
.sub { font-size: 12px; color: var(--muted); }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 999px; background: var(--surface-2); }
.tabs { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; margin-bottom: 12px; }
.tab { flex: 1; text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; color: var(--muted); cursor: pointer; }
.tab.active { background: var(--surface); color: var(--accent); font-weight: 600; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08); }
.grid { display: flex; flex-direction: column; gap: 10px; }
.place { display: flex; gap: 10px; background: var(--surface); border-radius: 14px; padding: 10px; border: 1px solid var(--line); }
.img { width: 104px; height: 96px; border-radius: 10px; flex-shrink: 0; }
.info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.name { font-weight: 700; }
.meta { font-size: 12px; margin-top: 2px; }
.rating { color: var(--price); }
.pick { margin: auto 0 0; align-self: flex-start; color: var(--accent); border-color: var(--accent); }
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
@media (min-width: 700px) {
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); }
  .place { flex-direction: column; }
  .img { width: 100%; height: 120px; }
}
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
