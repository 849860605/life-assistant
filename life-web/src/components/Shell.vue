<template>
  <view class="shell" :class="{ wide, fill: fill && wide }">
    <view v-if="wide" class="sidebar">
      <view class="brand" @click="goKey('home')">
        <BrandMark :size="40" />
        <view class="brand-text">
          <view class="brand-name">星络 <text class="brand-en">Xinglo</text></view>
          <view class="brand-sub">旅行 · 日程 · 出行</view>
        </view>
      </view>
      <view class="nav">
        <template v-for="n in NAV" :key="n.key">
          <view class="nav-item" :class="{ on: n.key === active || (n.children && !opened(n) && n.children.some((c) => c.key === active)) }" @click="n.children ? toggleGroup(n.key) : go(n)">
            <DuoIcon :name="n.icon" :size="20" :tile="30" />
            <text>{{ n.label }}</text>
            <Icon v-if="n.children" class="nav-caret" :class="{ open: opened(n) }" name="down" :size="14" color="var(--muted)" />
          </view>
          <template v-if="n.children && opened(n)">
            <view v-for="c in n.children" :key="c.key" class="nav-item sub" :class="{ on: c.key === active }" @click="go(c)">
              <DuoIcon :name="c.icon" :size="17" :tile="26" />
              <text>{{ c.label }}</text>
            </view>
          </template>
        </template>
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
        <view class="recent-all" @click="goKey('trips')">查看全部 ›</view>
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
          <view class="top-item" @click="picking = true"><Icon name="pin" :size="16" color="var(--ink)" /><text>{{ city }}</text><Icon name="down" :size="12" color="var(--muted)" /></view>
          <view v-if="weather" class="top-item"><Icon name="weather" :size="16" color="#f59e0b" /><text>{{ weather }}</text></view>
          <view class="top-item" @click="goKey('trips')"><Icon name="trip" :size="16" color="var(--ink)" /><text>历史行程</text></view>
          <view class="top-item" @click="goKey('fav')"><Icon name="heart" :size="16" color="var(--ink)" /><text>收藏</text></view>
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
    <CityPicker v-if="picking" :current="city" @close="picking = false" @pick="changeCity" />
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { getToken } from "../api/http";
import CityPicker from "./CityPicker.vue";
import Icon from "./Icon.vue";
import BrandMark from "./BrandMark.vue";
import DuoIcon from "./DuoIcon.vue";
import { cityCover } from "../utils/cover";
import { cityOf, currentCity, setCurrentCity } from "../utils/place";
import { useWide } from "../utils/screen";

/**
 * 页面外壳：宽屏（电脑）时左边是导航栏和最近行程，上面是搜索和城市天气；窄屏（手机）时只显示内容，
 * 导航交给底部标签栏。每个页面都包一层，active 是当前高亮的导航项。
 * fill 为 true 时（电脑）整个外壳正好一屏高，内容区填满顶栏下面的空间，给对话这类自己滚动的页面用。
 */
const props = withDefaults(defineProps<{ active?: string; showRecent?: boolean; hideTopbar?: boolean; fill?: boolean }>(), {
  active: "",
  showRecent: true,
  hideTopbar: false,
  fill: false,
});

interface NavItem {
  key: string;
  label: string;
  icon: string;
  url: string;
  /** 有子菜单时点它只展开收起 */
  children?: NavItem[];
}

const city = currentCity();
/** 最近一个行程的 id，侧栏"旅行规划"打开它；没有行程时去对话 */
const latestId = ref<number | null>(null);

const NAV = computed<NavItem[]>(() => [
  { key: "home", label: "首页", icon: "home", url: "/pages/home/home" },
  { key: "chat", label: "问星络", icon: "chat", url: "/pages/chat/chat" },
  { key: "schedule", label: "我的日程", icon: "calendar", url: "/pages/schedule/schedule" },
  { key: "plan", label: "旅行规划", icon: "flyCloud", url: latestId.value ? `/pages/trips/detail?id=${latestId.value}` : "/pages/chat/chat" },
  {
    key: "travel",
    label: "机票酒店",
    icon: "suitcase",
    url: "",
    children: [
      { key: "flight", label: "机票比价", icon: "plane", url: latestId.value ? `/pages/search/flight?trip=${latestId.value}` : "/pages/search/flight" },
      { key: "hotel", label: "酒店比价", icon: "bed", url: latestId.value ? `/pages/search/hotel?trip=${latestId.value}` : "/pages/search/hotel" },
    ],
  },
  { key: "food", label: "美食推荐", icon: "food", url: latestId.value ? `/pages/plan/food?id=${latestId.value}` : "/pages/search/food" },
  { key: "ticket", label: "景点门票", icon: "mountain", url: latestId.value ? `/pages/search/ticket?trip=${latestId.value}` : "/pages/search/ticket" },
  { key: "map", label: "地图出行", icon: "map", url: "/pages/plan/map" },
  { key: "weather", label: "天气查询", icon: "weather", url: `/pages/weather/weather?city=${encodeURIComponent(city)}` + (latestId.value ? `&trip=${latestId.value}` : "") },
  { key: "trips", label: "我的行程", icon: "route", url: "/pages/trips/trips" },
  { key: "fav", label: "我的收藏", icon: "heart", url: "/pages/discover/discover" },
]);

const wide = useWide();
const keyword = ref("");
const picking = ref(false);
const weather = ref("");
const nickname = ref("");
const recent = ref<{ key: string; id: number; title: string; dates: string; cover: string }[]>([]);
const initial = computed(() => (nickname.value || "我").slice(0, 1).toUpperCase());

onMounted(async () => {
  if (!wide.value || !getToken()) return;
  // 顶栏和最近行程都是锦上添花，失败不提示
  api.me().then((u) => (nickname.value = u.nickname || u.username)).catch(() => {});
  api.weatherToday(city).then((w) => (weather.value = `${w.tempMin}~${w.tempMax}°C ${w.text}`)).catch(() => {});
  api.trips().then((list) => {
    latestId.value = list[0]?.id ?? null;
    if (!props.showRecent) return;
    recent.value = list.slice(0, 3).map((t) => ({
      key: String(t.id),
      id: t.id,
      title: t.title,
      dates: t.startDate ? `${t.startDate.slice(5).replace("-", ".")} – ${(t.endDate || t.startDate).slice(5).replace("-", ".")}` : "日期待定",
      cover: "",
    }));
    // 封面用目的地城市热门景点的照片，取到一张换一张
    recent.value.forEach((r, i) => {
      cityCover(cityOf(list[i].destination || list[i].title)).then((url) => (r.cover = url));
    });
  }).catch(() => {});
});

/** 手动展开、收起的菜单组；当前页在组里时默认展开 */
const groups = ref<Record<string, boolean>>({});

function opened(n: NavItem) {
  return groups.value[n.key] ?? !!n.children?.some((c) => c.key === props.active);
}

function toggleGroup(key: string) {
  const n = NAV.value.find((x) => x.key === key);
  if (n) groups.value = { ...groups.value, [key]: !opened(n) };
}

/** 侧栏跳转用 reLaunch，不在电脑上堆出很深的页面栈。 */
function go(n: NavItem) {
  uni.reLaunch({ url: n.url });
}

function goKey(key: string) {
  const n = NAV.value.find((x) => x.key === key);
  if (n) go(n);
}

function openTrip(id: number) {
  uni.reLaunch({ url: `/pages/trips/detail?id=${id}` });
}

function goMe() {
  uni.reLaunch({ url: "/pages/me/me" });
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
.brand { display: flex; align-items: center; gap: 10px; padding: 4px 8px 18px; cursor: pointer; }
.brand-text { min-width: 0; }
.brand-name { font-size: 17px; font-weight: 800; line-height: 1.2; }
.brand-en { font-size: 14px; font-weight: 700; color: var(--accent); }
.brand-sub { font-size: 11px; color: var(--muted); margin-top: 2px; }
.nav { display: flex; flex-direction: column; gap: 2px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border-radius: 12px; font-size: 14px; cursor: pointer; }
.nav-item:hover { background: var(--surface-2); }
.nav-item.on { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.nav-item.sub { padding-left: 24px; font-size: 13px; }
.nav-caret { margin-left: auto; transition: transform 0.15s; }
.nav-caret.open { transform: rotate(180deg); }
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
.shell.fill { height: 100vh; min-height: 0; }
.shell.fill .main { min-height: 0; }
.shell.fill .content { min-height: 0; display: flex; flex-direction: column; }
</style>
