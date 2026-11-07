<template>
  <view class="shell" :class="{ wide, fill: fill && wide }">
    <view v-if="wide && !hideTopbar" class="topnav">
      <view class="topnav-inner">
        <view class="brand" @click="go('/pages/home/home')">
          <BrandMark :size="28" />
          <text class="brand-name">星络</text>
          <text class="brand-en">Xinglo</text>
        </view>

        <view class="nav">
          <view class="nav-item" :class="{ on: group === 'ask' }" @click="go('/pages/home/home')">问星络</view>
          <view class="nav-item" :class="{ on: group === 'arrange' }" @click="go('/pages/schedule/schedule')">我的安排</view>
          <view class="nav-drop" @mouseenter="menu = true" @mouseleave="menu = false">
            <view class="nav-item" :class="{ on: group === 'travel' }" @click="go('/pages/travel/travel')">
              旅行服务 <Icon name="down" :size="12" :color="group === 'travel' ? 'var(--ink)' : 'var(--muted)'" />
            </view>
            <view v-if="menu" class="menu">
              <view v-for="s in TRAVEL" :key="s.key" class="menu-item" :class="{ on: s.key === active }" @click="go(s.url)">
                <Icon :name="s.icon" :size="16" color="var(--muted)" />
                <text>{{ s.label }}</text>
              </view>
            </view>
          </view>
        </view>

        <view class="right">
          <slot name="topbar" />
          <view class="r-item" @click="picking = true">
            <Icon name="pin" :size="15" color="var(--muted)" /><text>{{ city }}</text>
          </view>
          <view class="r-item" :class="{ on: group === 'fav' }" @click="go('/pages/fav/fav')">
            <Icon name="heart" :size="15" :color="group === 'fav' ? 'var(--accent)' : 'var(--muted)'" /><text>收藏</text>
          </view>
          <view class="me" :class="{ on: group === 'me' }" @click="go('/pages/me/me')">
            <view class="avatar">{{ initial }}</view>
          </view>
        </view>
      </view>
    </view>
    <view class="content">
      <slot />
    </view>
    <CityPicker v-if="picking" :current="city" @close="picking = false" @pick="changeCity" />
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { getToken } from "../api/http";
import BrandMark from "./BrandMark.vue";
import CityPicker from "./CityPicker.vue";
import Icon from "./Icon.vue";
import { currentCity, setCurrentCity } from "../utils/place";
import { useWide } from "../utils/screen";

/**
 * 页面外壳：电脑上是顶部导航（问星络、我的安排、旅行服务三个主入口，右边位置、收藏、头像），
 * 手机上不显示，导航交给底部标签栏。active 是当前页面的细分 key，按它算出高亮哪个主入口。
 * fill 为 true 时（电脑）整个外壳正好一屏高，内容区填满导航下面的空间，给对话这类自己滚动的页面用。
 */
const props = withDefaults(defineProps<{ active?: string; showRecent?: boolean; hideTopbar?: boolean; fill?: boolean }>(), {
  active: "",
  showRecent: false,
  hideTopbar: false,
  fill: false,
});

/** 旅行服务下拉菜单的各项 */
const TRAVEL = [
  { key: "flight", label: "机票", icon: "plane", url: "/pages/search/flight" },
  { key: "hotel", label: "酒店", icon: "bed", url: "/pages/search/hotel" },
  { key: "food", label: "美食", icon: "food", url: "/pages/search/food" },
  { key: "ticket", label: "景点门票", icon: "ticket", url: "/pages/search/ticket" },
  { key: "map", label: "地图", icon: "map", url: "/pages/plan/map" },
  { key: "weather", label: "天气", icon: "weather", url: "/pages/weather/weather" },
];

/** 页面 key → 高亮的主入口 */
const GROUP: Record<string, string> = {
  home: "ask",
  chat: "ask",
  schedule: "arrange",
  trips: "arrange",
  plan: "arrange",
  flight: "travel",
  hotel: "travel",
  food: "travel",
  ticket: "travel",
  map: "travel",
  weather: "travel",
  travel: "travel",
  fav: "fav",
  me: "me",
  member: "me",
};

const wide = useWide();
const city = currentCity();
const group = computed(() => GROUP[props.active] || "");
const menu = ref(false);
const picking = ref(false);
const nickname = ref("");
const initial = computed(() => (nickname.value || "我").slice(0, 1).toUpperCase());

onMounted(() => {
  if (!wide.value || !getToken()) return;
  api.me().then((u) => (nickname.value = u.nickname || u.username)).catch(() => {});
});

/** 顶部导航跳转用 reLaunch，不在电脑上堆出很深的页面栈。 */
function go(url: string) {
  menu.value = false;
  uni.reLaunch({ url });
}

/** 换城市：存下来，重新打开当前页，让页面按新城市取数据。 */
function changeCity(c: string) {
  picking.value = false;
  setCurrentCity(c);
  const pages = getCurrentPages();
  const page = pages[pages.length - 1] as any;
  const query = page?.options ? Object.entries(page.options).map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join("&") : "";
  uni.reLaunch({ url: "/" + (page?.route || "pages/home/home") + (query ? "?" + query : "") });
}
</script>

<style scoped>
.shell.wide { min-height: 100vh; background: var(--bg); display: flex; flex-direction: column; }
.topnav {
  position: sticky;
  top: 0;
  z-index: 50;
  height: 64px;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}
@media (prefers-color-scheme: dark) { .topnav { background: rgba(28, 28, 30, 0.86); } }
.topnav-inner { max-width: 1200px; height: 100%; margin: 0 auto; padding: 0 24px; box-sizing: border-box; display: flex; align-items: center; gap: 32px; }
.brand { display: flex; align-items: center; gap: 8px; cursor: pointer; flex-shrink: 0; }
.brand-name { font-size: 17px; font-weight: 700; }
.brand-en { font-size: 13px; color: var(--muted); }
.nav { display: flex; align-items: center; gap: 4px; flex: 1; justify-content: center; }
.nav-item { display: flex; align-items: center; gap: 4px; height: 36px; padding: 0 14px; border-radius: 8px; font-size: 15px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.nav-item:hover { color: var(--ink); }
.nav-item.on { color: var(--ink); font-weight: 600; background: var(--surface-2); }
.nav-drop { position: relative; }
.menu {
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  min-width: 168px;
  padding: 6px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--shadow);
}
.menu::before { content: ""; position: absolute; left: 0; right: 0; top: -8px; height: 8px; }
.menu-item { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-radius: 8px; font-size: 14px; cursor: pointer; }
.menu-item:hover { background: var(--surface-2); }
.menu-item.on { color: var(--accent); font-weight: 600; }
.right { display: flex; align-items: center; gap: 20px; font-size: 14px; flex-shrink: 0; }
.r-item { display: flex; align-items: center; gap: 4px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.r-item:hover, .r-item.on { color: var(--ink); }
.me { cursor: pointer; border-radius: 50%; padding: 2px; }
.me.on { box-shadow: 0 0 0 2px var(--accent); }
.avatar { width: 30px; height: 30px; border-radius: 50%; background: var(--ink); color: var(--surface); font-size: 13px; font-weight: 600; display: flex; align-items: center; justify-content: center; }
.content { flex: 1; min-width: 0; }
.shell.fill { height: 100vh; min-height: 0; }
.shell.fill .content { min-height: 0; display: flex; flex-direction: column; }
</style>
