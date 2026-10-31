<template>
  <view class="shell" :class="{ wide }">
    <view v-if="wide" class="sidebar">
      <view class="brand" @click="go(NAV[0])">
        <view class="logo"><Icon name="chat" :size="16" color="#fff" /></view>
        <text class="brand-name">生活助手</text>
      </view>
      <view class="nav">
        <view v-for="n in NAV" :key="n.key" class="nav-item" :class="{ on: n.key === active }" @click="go(n)">
          <Icon :name="n.icon" :size="18" :color="n.key === active ? 'var(--accent)' : 'var(--ink)'" />
          <text>{{ n.label }}</text>
        </view>
      </view>
      <view v-if="showRecent && recent.length" class="recent">
        <view class="recent-title">最近行程</view>
        <view v-for="t in recent" :key="t.key" class="recent-item" @click="openTrip(t.id)">
          <image v-if="t.cover" class="recent-img" :src="t.cover" mode="aspectFill" />
          <view v-else class="recent-img blank" />
          <view class="recent-body">
            <view class="recent-name">{{ t.title }}</view>
            <view class="recent-date">{{ t.dates }}</view>
          </view>
        </view>
        <view class="recent-all" @click="go(NAV[8])">查看全部 ›</view>
      </view>
    </view>

    <view class="main">
      <view v-if="wide && !hideTopbar" class="topbar">
        <view class="search">
          <Icon name="search" :size="16" color="var(--muted)" />
          <input id="shell-search" v-model="keyword" class="search-input" placeholder="搜索目的地、景点、酒店、美食…" @confirm="search" />
        </view>
        <view class="top-right">
          <slot name="topbar" />
          <view class="top-item"><Icon name="pin" :size="16" color="var(--ink)" /><text>{{ city }}</text></view>
          <view v-if="weather" class="top-item"><Icon name="weather" :size="16" color="#f59e0b" /><text>{{ weather }}</text></view>
          <view class="top-item" @click="go(NAV[8])"><Icon name="trip" :size="16" color="var(--ink)" /><text>历史行程</text></view>
          <view class="top-item" @click="go(NAV[9])"><Icon name="heart" :size="16" color="var(--ink)" /><text>收藏</text></view>
          <view class="top-item"><Icon name="bell" :size="17" color="var(--ink)" /></view>
          <view class="me" @click="goMe">
            <view class="avatar">{{ initial }}</view>
            <text>{{ nickname }}</text>
            <Icon name="down" :size="14" color="var(--muted)" />
          </view>
        </view>
      </view>
      <view class="content">
        <slot />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { getToken } from "../api/http";
import Icon from "./Icon.vue";
import { CITY, coverFor } from "../demo/xian";
import { useWide } from "../utils/screen";

/**
 * 页面外壳：宽屏（电脑）时左边是导航栏和最近行程，上面是搜索和城市天气；窄屏（手机）时只显示内容，
 * 导航交给底部标签栏。每个页面都包一层，active 是当前高亮的导航项。
 */
const props = withDefaults(defineProps<{ active?: string; showRecent?: boolean; hideTopbar?: boolean }>(), {
  active: "",
  showRecent: true,
  hideTopbar: false,
});

interface NavItem {
  key: string;
  label: string;
  icon: string;
  url: string;
}

const NAV: NavItem[] = [
  { key: "home", label: "首页", icon: "home", url: "/pages/home/home" },
  { key: "chat", label: "AI 对话", icon: "chat", url: "/pages/chat/chat" },
  { key: "plan", label: "旅行规划", icon: "users", url: "/pages/trips/detail?id=demo" },
  { key: "flight", label: "机票酒店", icon: "plane", url: "/pages/search/flight" },
  { key: "food", label: "美食推荐", icon: "food", url: "/pages/search/food" },
  { key: "ticket", label: "景点门票", icon: "ticket", url: "/pages/search/flight?tab=ticket" },
  { key: "map", label: "地图出行", icon: "nav", url: "/pages/plan/map" },
  { key: "weather", label: "天气查询", icon: "weather", url: "/pages/chat/chat?q=" + encodeURIComponent(`${CITY}这周天气怎么样，适合出去玩吗？`) },
  { key: "trips", label: "我的行程", icon: "trip", url: "/pages/trips/trips" },
  { key: "fav", label: "我的收藏", icon: "heart", url: "/pages/discover/discover" },
];

const wide = useWide();
const keyword = ref("");
const city = CITY;
const weather = ref("");
const nickname = ref("");
const recent = ref<{ key: string; id: number | "demo"; title: string; dates: string; cover: string }[]>([]);
const initial = computed(() => (nickname.value || "我").slice(0, 1).toUpperCase());

onMounted(async () => {
  if (!wide.value || !getToken()) return;
  // 顶栏和最近行程都是锦上添花，失败不提示
  api.me().then((u) => (nickname.value = u.nickname || u.username)).catch(() => {});
  api.weatherToday(city).then((w) => (weather.value = `${w.tempMin}~${w.tempMax}°C ${w.text}`)).catch(() => {});
  if (props.showRecent) {
    api.trips().then((list) => {
      recent.value = [
        { key: "demo", id: "demo", title: "西安 4 天（示例）", dates: "3.10 – 3.13", cover: coverFor("西安") },
        ...list.slice(0, 3).map((t) => ({
          key: String(t.id),
          id: t.id,
          title: t.title,
          dates: t.startDate ? `${t.startDate.slice(5).replace("-", ".")} – ${(t.endDate || t.startDate).slice(5).replace("-", ".")}` : "日期待定",
          cover: coverFor(t.destination || t.title),
        })),
      ];
    }).catch(() => {});
  }
});

/** 侧栏跳转用 reLaunch，不在电脑上堆出很深的页面栈。 */
function go(n: NavItem) {
  uni.reLaunch({ url: n.url });
}

function openTrip(id: number | "demo") {
  uni.reLaunch({ url: `/pages/trips/detail?id=${id}` });
}

function goMe() {
  uni.reLaunch({ url: "/pages/me/me" });
}

function search() {
  const q = keyword.value.trim();
  if (!q) return;
  keyword.value = "";
  uni.reLaunch({ url: "/pages/chat/chat?q=" + encodeURIComponent(q) });
}
</script>

<style scoped>
.shell.wide { display: flex; min-height: 100vh; background: var(--bg); }
.sidebar {
  width: 200px;
  flex-shrink: 0;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  padding: 16px 12px;
  box-sizing: border-box;
  background: var(--surface);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
}
.brand { display: flex; align-items: center; gap: 8px; padding: 4px 8px 18px; cursor: pointer; }
.logo { width: 30px; height: 30px; border-radius: 9px; background: var(--accent); display: flex; align-items: center; justify-content: center; }
.brand-name { font-size: 17px; font-weight: 800; }
.nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 10px; font-size: 14px; cursor: pointer; }
.nav-item:hover { background: var(--surface-2); }
.nav-item.on { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.recent { margin-top: auto; padding-top: 18px; }
.recent-title { font-size: 13px; color: var(--muted); padding: 0 8px 8px; }
.recent-item { display: flex; gap: 8px; align-items: center; padding: 6px 8px; border-radius: 10px; cursor: pointer; }
.recent-item:hover { background: var(--surface-2); }
.recent-img { width: 40px; height: 40px; border-radius: 8px; flex-shrink: 0; }
.recent-img.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); }
.recent-body { min-width: 0; }
.recent-name { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recent-date { font-size: 11px; color: var(--muted); font-variant-numeric: tabular-nums; }
.recent-all { font-size: 13px; color: var(--accent); padding: 8px; cursor: pointer; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}
.search {
  width: min(420px, 40%);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  height: 38px;
  border-radius: 10px;
  background: var(--surface-2);
}
.search-input { flex: 1; font-size: 14px; }
.top-right { margin-left: auto; display: flex; align-items: center; gap: 20px; font-size: 14px; }
.top-item { display: flex; align-items: center; gap: 6px; cursor: pointer; white-space: nowrap; }
.me { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.content { flex: 1; min-width: 0; }
</style>
