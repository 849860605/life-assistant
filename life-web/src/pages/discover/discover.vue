<template>
  <Shell active="fav">
  <view class="page">
    <view class="intro">
      <view class="title">去哪儿玩</view>
      <view class="sub">点卡片看当地的景点美食，或者让 AI 帮你规划行程</view>
    </view>
    <view class="grid">
      <view v-for="c in CITIES" :key="c" class="dest" @click="open(c)">
        <image v-if="info[c]?.photo" class="img" :src="info[c].photo" mode="aspectFill" />
        <view v-else class="img blank" />
        <view class="shade" />
        <view v-if="c === current" class="here">当前城市</view>
        <view class="text">
          <view class="name">{{ c }}</view>
          <view class="tagline">{{ info[c]?.spots || "加载中…" }}</view>
          <!-- 点整张卡片就是看这个城市，这里只放规划 -->
          <view class="dest-actions">
            <text class="dest-btn solid" @click.stop="ask(`帮我规划去${c}玩3天`)">规划行程 ›</text>
          </view>
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
import { onMounted, ref } from "vue";
import { getToken } from "../../api/http";
import { citySpots } from "../../utils/cover";
import { currentCity, setCurrentCity } from "../../utils/place";

/**
 * 发现：热门目的地和几个玩法灵感。城市列表是固定的导航入口，封面和介绍来自高德：
 * 第一个有照片的热门景点的照片、评分最高的三个景点名。
 */
const CITIES = ["北京", "上海", "成都", "重庆", "西安", "杭州", "厦门", "大理"];
const current = currentCity();
const info = ref<Record<string, { photo: string; spots: string }>>({});

onMounted(() => {
  if (!getToken()) return;
  for (const c of CITIES) {
    citySpots(c, 6).then((list) => {
      info.value = {
        ...info.value,
        [c]: { photo: list.find((p) => p.photo)?.photo || "", spots: list.slice(0, 3).map((p) => p.name).join(" · ") || "暂无景点数据" },
      };
    });
  }
});
const IDEAS = ["周末两天，开车 3 小时内能到的地方", "带老人出游，不用爬山的城市", "预算 3000 以内的海边", "适合拍照的古镇"];

/** 点卡片：切到这个城市，回首页看它的景点和美食。 */
function open(city: string) {
  useCity(city);
}

function useCity(city: string) {
  setCurrentCity(city);
  uni.reLaunch({ url: "/pages/chat/chat" });
}

function ask(q: string) {
  uni.reLaunch({ url: "/pages/chat/chat?q=" + encodeURIComponent(q) });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 16px; box-sizing: border-box; }
.intro { margin-bottom: 12px; }
.title { font-size: 22px; font-weight: 800; }
.sub { font-size: 13px; color: var(--muted); margin-top: 2px; }
.grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.dest { position: relative; height: 200px; border-radius: 16px; overflow: hidden; cursor: pointer; }
.img { position: absolute; inset: 0; width: 100%; height: 100%; }
.shade { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(0, 0, 0, 0) 35%, rgba(0, 0, 0, 0.65)); }
.text { position: absolute; left: 12px; right: 12px; bottom: 10px; color: #fff; }
.name { font-size: 18px; font-weight: 800; }
.tagline { font-size: 12px; opacity: 0.9; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.img.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); }
.here { position: absolute; top: 10px; left: 10px; font-size: 11px; padding: 2px 8px; border-radius: 999px; background: rgba(255, 255, 255, 0.9); color: var(--accent); font-weight: 600; }
.dest-actions { display: flex; gap: 6px; margin-top: 8px; }
/* 微信里字号会被调大，按钮不许折行 */
.dest-btn { flex-shrink: 0; white-space: nowrap; font-size: 12px; line-height: 1.4; padding: 5px 12px; border-radius: 999px; border: 1px solid rgba(255, 255, 255, 0.8); cursor: pointer; }
.dest-btn.solid { background: var(--accent); border-color: var(--accent); }
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
