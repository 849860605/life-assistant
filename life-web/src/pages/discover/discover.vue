<template>
  <Shell active="fav">
  <view class="page">
    <view class="intro">
      <view class="title">去哪儿玩</view>
      <view class="sub">挑一个目的地，看看三套不同风格的方案</view>
    </view>
    <view class="grid">
      <view v-for="d in DESTINATIONS" :key="d.name" class="dest" @click="open(d.name)">
        <image class="img" :src="d.image" mode="aspectFill" />
        <view class="shade" />
        <view class="text">
          <view class="name">{{ d.name }}</view>
          <view class="tagline">{{ d.tagline }}</view>
        </view>
      </view>
    </view>
    <view class="section">换个玩法</view>
    <view class="ideas">
      <view v-for="i in IDEAS" :key="i" class="idea" @click="ask(i)">
        <Icon name="sparkle" :size="16" color="var(--accent)" />
        <text>{{ i }}</text>
        <Icon name="right" :size="16" color="var(--muted)" />
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import Icon from "../../components/Icon.vue";
import { DESTINATIONS } from "../../demo/xian";

/** 发现：热门目的地（点开看三套方案）和几个玩法灵感。 */
const IDEAS = ["周末两天，开车 3 小时内能到的地方", "带老人出游，不用爬山的城市", "预算 3000 以内的海边", "适合拍照的古镇"];

function open(name: string) {
  if (name === "西安") {
    uni.navigateTo({ url: "/pages/plan/plans" });
  } else {
    ask(`帮我规划去${name}玩3天`);
  }
}

function ask(q: string) {
  uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent(q) });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 16px; box-sizing: border-box; }
.intro { margin-bottom: 12px; }
.title { font-size: 22px; font-weight: 800; }
.sub { font-size: 13px; color: var(--muted); margin-top: 2px; }
.grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.dest { position: relative; height: 180px; border-radius: 16px; overflow: hidden; cursor: pointer; }
.img { position: absolute; inset: 0; width: 100%; height: 100%; }
.shade { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(0, 0, 0, 0) 45%, rgba(0, 0, 0, 0.6)); }
.text { position: absolute; left: 12px; right: 12px; bottom: 10px; color: #fff; }
.name { font-size: 18px; font-weight: 800; }
.tagline { font-size: 12px; opacity: 0.9; }
.section { font-size: 16px; font-weight: 700; margin: 20px 2px 10px; }
.ideas { background: var(--surface); border-radius: 16px; border: 1px solid var(--line); }
.idea { display: flex; align-items: center; gap: 10px; padding: 14px; border-bottom: 1px solid var(--line); font-size: 14px; cursor: pointer; }
.idea:last-child { border-bottom: none; }
.idea text { flex: 1; }
@media (min-width: 700px) { .grid { grid-template-columns: repeat(4, 1fr); } }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
