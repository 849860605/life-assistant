<template>
  <Shell active="home">
    <view v-if="wide" class="desk">
      <view class="banner">
        <image v-if="hero" class="banner-img" :src="hero" mode="aspectFill" />
        <view v-else class="banner-img blank" />
        <view class="banner-shade" />
        <view class="banner-body">
          <view class="banner-title">用 AI 规划更好的{{ city }}之旅</view>
          <view class="banner-sub">告诉我你的想法，AI 为你定制行程、推荐景点美食、查询机票酒店、规划路线</view>
          <view class="features">
            <view v-for="f in FEATURES" :key="f.label" class="feature"><Icon :name="f.icon" :size="14" color="#fff" /><text>{{ f.label }}</text></view>
          </view>
          <view class="big-ask">
            <input id="desk-ask" v-model="question" class="big-input" confirm-type="send" :placeholder="`例如：帮我规划${city}4天3晚，预算5000元，带父母，想看历史文化和美食`" @confirm="ask" />
            <view class="big-send" @click="ask"><Icon name="send" :size="20" color="#fff" /></view>
          </view>
          <view class="quick">
            <text v-for="q in quick" :key="q" class="quick-chip" @click="openChat(q)">{{ q }}</text>
          </view>
        </view>
      </view>

      <view class="grid">
        <view class="col-main">
          <view class="panel entries-row">
            <view v-for="e in deskEntries" :key="e.label" class="d-entry" @click="e.go()">
              <view class="d-entry-icon" :style="{ background: e.bg }"><Icon :name="e.icon" :size="24" :color="e.color" /></view>
              <text class="d-entry-label">{{ e.label }}</text>
              <text class="d-entry-sub">{{ e.sub }}</text>
            </view>
          </view>

          <view class="two">
            <view class="panel">
              <view class="panel-head"><text class="panel-title">{{ city }}热门景点</text><text class="more" @click="openChat(`${city}有哪些值得去的景点？`)">查看更多 ›</text></view>
              <view v-if="spots.length" class="spots">
                <view v-for="sp in spots" :key="sp.id" class="spot" @click="openChat(`介绍一下${sp.name}，适合怎么玩`)">
                  <image v-if="sp.photo" class="spot-img" :src="sp.photo" mode="aspectFill" />
                  <view v-else class="spot-img blank"><Icon name="pin" :size="22" color="#fff" /></view>
                  <view class="spot-body">
                    <view class="spot-name">{{ sp.name }}</view>
                    <view v-if="sp.rating" class="spot-rating">★ {{ sp.rating }} <text class="muted">高德评分</text></view>
                    <view class="spot-tags"><text v-for="t in tagsOf(sp)" :key="t" class="mini-tag">{{ t }}</text></view>
                  </view>
                </view>
              </view>
              <view v-else class="empty-line">{{ spotsLoaded ? `没找到${city}的景点` : "加载中…" }}</view>
            </view>

            <view class="panel">
              <view class="panel-head"><text class="panel-title">最近的行程</text><text class="more" @click="goTrips">全部行程 ›</text></view>
              <template v-if="trip">
                <view class="plan-title-row">
                  <view class="plan-title-box">
                    <text class="plan-title">{{ trip.title }}</text>
                    <view class="plan-meta">{{ dateRange(trip) }} · {{ days.length }} 天 <text class="state" :class="trip.status === 'DRAFT' ? 'draft' : 'ok'">{{ statusText(trip) }}</text></view>
                  </view>
                  <view class="save" @click="openTrip(trip.id)"><Icon name="right" :size="13" color="var(--accent)" /><text>打开</text></view>
                </view>
                <view v-if="days.length" class="mini-days" :style="{ gridTemplateColumns: `repeat(${Math.min(days.length, 5)}, 1fr)` }">
                  <view v-for="(d, i) in days.slice(0, 5)" :key="d.date" class="mini-day" :class="{ on: i === miniDay }" @click="miniDay = i">
                    <text class="mini-day-no">D{{ i + 1 }}</text><text class="mini-day-date">{{ shortDate(d.date) }}</text>
                  </view>
                </view>
                <view class="mini-timeline">
                  <view v-for="(it, i) in dayStops" :key="it.id" class="mini-stop" @click="openTrip(trip.id)">
                    <view class="mini-no">{{ i + 1 }}</view>
                    <image v-if="it.photo" class="mini-img" :src="it.photo" mode="aspectFill" />
                    <view class="mini-body">
                      <view class="mini-name">{{ it.name }}</view>
                      <view class="mini-time">{{ it.start }}<text v-if="it.end"> – {{ it.end }}</text></view>
                      <view v-if="it.note" class="mini-note">{{ it.note }}</view>
                    </view>
                  </view>
                  <view v-if="!dayStops.length" class="empty-line">{{ days.length ? "这一天还没有安排" : "这个行程没有逐日安排，打开看正文" }}</view>
                </view>
              </template>
              <view v-else-if="tripLoaded" class="empty-trip">
                <Icon name="trip" :size="34" color="var(--muted)" />
                <view class="empty-title">说出想去哪，AI 帮你规划</view>
                <view class="muted small">规划好的行程会出现在这里，可以按天查看和调整</view>
                <button class="btn" @click="openChat(`帮我规划${city}3天2晚的行程`)">开始规划</button>
              </view>
              <view v-else class="empty-line">加载中…</view>
            </view>
          </view>
        </view>

        <view class="col-side">
          <view class="panel">
            <view class="panel-head">
              <text class="panel-title">{{ trip && dayMap ? `D${miniDay + 1} 路线` : `${city}热门景点分布` }}</text>
              <text v-if="trip && dayMap" class="more" @click="go(`/pages/plan/map?id=${trip.id}&day=${miniDay + 1}`)">全屏地图 ›</text>
            </view>
            <image v-if="homeMap" class="home-map" :src="homeMap" mode="aspectFill" />
            <view v-else class="home-map blank-map">{{ spotsLoaded ? "暂无地图" : "加载中…" }}</view>
            <view v-if="trip && dayMap" class="modes">
              <view class="mode on"><Icon name="route" :size="18" color="#fff" /><view><view>全天路程</view><view class="mode-sub">{{ travel.km }} 公里</view></view></view>
              <view v-if="legCount.drive" class="mode"><Icon name="car" :size="18" color="var(--ink)" /><view><view>打车 {{ legCount.drive }} 段</view><view class="mode-sub">{{ duration(legCount.driveMin) }}</view></view></view>
              <view v-if="legCount.walk" class="mode"><Icon name="users" :size="18" color="var(--ink)" /><view><view>步行 {{ legCount.walk }} 段</view><view class="mode-sub">{{ duration(legCount.walkMin) }}</view></view></view>
            </view>
            <view v-else-if="spots.length" class="map-caption">标号是上面的热门景点：{{ spots.slice(0, 6).map((s, i) => `${i + 1} ${s.name}`).join("、") }}</view>
          </view>

          <view class="panel">
            <view class="panel-head"><text class="panel-title">{{ city }}美食推荐</text><text class="more" @click="go('/pages/search/food')">查看更多 ›</text></view>
            <view v-if="foods.length" class="foods">
              <view v-for="f in foods.slice(0, 4)" :key="f.id" class="food" @click="go('/pages/search/food')">
                <image v-if="f.photo" class="food-img" :src="f.photo" mode="aspectFill" />
                <view v-else class="food-img blank"><Icon name="food" :size="20" color="#fff" /></view>
                <view class="food-name">{{ f.name }}</view>
                <view class="food-meta"><text v-if="f.rating" class="star">★ {{ f.rating }}</text><text v-if="f.cost"> ¥{{ f.cost }}/人</text></view>
                <text class="mini-tag">{{ f.category }}</text>
              </view>
            </view>
            <view v-else class="empty-line">{{ foodsLoaded ? `没找到${city}的餐厅` : "加载中…" }}</view>
          </view>

          <view class="panel">
            <view class="seg">
              <text v-for="t in BOOK_TABS" :key="t" class="seg-item" :class="{ on: bookTab === t }" @click="bookTab = t">{{ t }}</text>
            </view>
            <view v-if="bookTab === '机票比价'" class="form">
              <view class="form-row">
                <input id="home-origin" v-model="origin" class="field" placeholder="出发城市" />
                <view class="swap" @click="swapCities"><Icon name="swap" :size="16" color="var(--accent)" /></view>
                <input id="home-dest" v-model="destination" class="field" placeholder="到达城市" />
              </view>
              <picker mode="date" :value="flightDate" :start="today" @change="flightDate = $event.detail.value">
                <view class="field picker"><Icon name="calendar" :size="15" color="var(--muted)" /><text>{{ flightDate }} {{ weekday(flightDate) }}</text></view>
              </picker>
              <button class="btn" @click="searchFlights">查询机票比价</button>
            </view>
            <view v-else-if="bookTab === '酒店比价'" class="form">
              <input id="home-hotel-place" v-model="hotelPlace" class="field" :placeholder="`${city}的位置，例如：钟楼（不填就是全市）`" />
              <view class="form-row">
                <picker mode="date" :value="checkIn" :start="today" class="half" @change="setCheckIn($event.detail.value)">
                  <view class="field picker"><text class="muted">入住</text><text>{{ shortDate(checkIn) }} {{ weekday(checkIn) }}</text></view>
                </picker>
                <picker mode="date" :value="checkOut" :start="checkIn" class="half" @change="checkOut = $event.detail.value">
                  <view class="field picker"><text class="muted">离店</text><text>{{ shortDate(checkOut) }} {{ weekday(checkOut) }}</text></view>
                </picker>
              </view>
              <button class="btn" @click="searchHotels">查询酒店比价</button>
            </view>
            <view v-else class="muted ticket-empty">门票比价还在找数据源</view>
            <view v-if="bookTab !== '门票'" class="sample-note">同时查飞猪、携程、美团等平台，按价格排，约需 15–60 秒</view>
          </view>
        </view>
      </view>
    </view>

    <view v-else class="home">
    <view class="topbar">
      <view class="brand">
        <view class="logo"><Icon name="chat" :size="16" color="#fff" /></view>
        <text class="brand-name">生活助手</text>
      </view>
      <view class="topbar-right">
        <view class="city-chip" @click="picking = true"><Icon name="pin" :size="14" color="var(--ink)" /><text>{{ city }}</text><Icon name="down" :size="12" color="var(--muted)" /></view>
        <view class="topbar-link" @click="openChat()">
          <Icon name="chat" :size="20" color="var(--muted)" />
        </view>
      </view>
    </view>

    <view class="page">
      <view class="hero">
        <image v-if="hero" class="hero-img" :src="hero" mode="aspectFill" />
        <view v-else class="hero-img blank" />
        <view class="hero-text">
          <view class="hero-title">用 AI 规划更好的{{ city }}之旅</view>
          <view class="hero-sub">告诉我你想去哪、什么时候出发、预算多少</view>
          <view class="hero-sub tight">剩下的交给 AI</view>
        </view>
        <view class="ask">
          <input
            id="home-ask"
            v-model="question"
            class="ask-input"
            confirm-type="send"
            :placeholder="`例如：帮我规划${city}4天3晚，带父母，预算每人5000元`"
            @confirm="ask"
          />
          <view class="ask-send" @click="ask"><Icon name="send" :size="18" color="#fff" /></view>
        </view>
      </view>

      <view class="entries">
        <view v-for="e in entries" :key="e.label" class="entry" @click="e.go()">
          <view class="entry-icon" :style="{ background: e.bg }"><Icon :name="e.icon" :size="22" :color="e.color" /></view>
          <text class="entry-label">{{ e.label }}</text>
          <text class="entry-sub">{{ e.sub }}</text>
        </view>
      </view>

      <view class="section-head">
        <text class="section-name">{{ city }}热门景点</text>
        <text class="section-more" @click="openChat(`${city}有哪些值得去的景点？`)">更多</text>
      </view>
      <scroll-view v-if="spots.length" scroll-x class="m-spots">
        <view v-for="sp in spots" :key="sp.id" class="m-spot" @click="openChat(`介绍一下${sp.name}，适合怎么玩`)">
          <image v-if="sp.photo" class="m-spot-img" :src="sp.photo" mode="aspectFill" />
          <view v-else class="m-spot-img blank" />
          <view class="m-spot-name">{{ sp.name }}</view>
          <view v-if="sp.rating" class="m-spot-rating">★ {{ sp.rating }}</view>
        </view>
      </scroll-view>
      <view v-else class="empty-line">{{ spotsLoaded ? `没找到${city}的景点` : "加载中…" }}</view>

      <view class="section-head">
        <text class="section-name">大家都在问</text>
      </view>
      <view class="asks">
        <text v-for="q in popular" :key="q" class="ask-chip" @click="openChat(q)">{{ q }}</text>
      </view>

      <view class="section-head">
        <text class="section-name">最近的行程</text>
        <text class="section-more" @click="goTrips">全部</text>
      </view>
      <view v-if="trip" class="recent" @click="openTrip(trip.id)">
        <image v-if="tripCover" class="recent-img" :src="tripCover" mode="aspectFill" />
        <view v-else class="recent-img blank" />
        <view class="recent-body">
          <view class="recent-title">{{ trip.title }}</view>
          <view class="recent-meta">{{ dateRange(trip) }}<text v-if="days.length"> · {{ days.length }} 天</text></view>
          <view class="recent-tags"><text class="tag" :class="{ blue: trip.status !== 'DRAFT' }">{{ statusText(trip) }}</text><text v-if="trip.currentVer > 1" class="tag">第 {{ trip.currentVer }} 版</text></view>
        </view>
        <Icon name="right" :size="18" color="var(--muted)" />
      </view>
      <view v-else-if="tripLoaded" class="recent empty-recent" @click="openChat(`帮我规划${city}3天2晚的行程`)">
        <Icon name="trip" :size="26" color="var(--accent)" />
        <view class="recent-body">
          <view class="recent-title">说出想去哪，AI 帮你规划</view>
          <view class="recent-meta">规划好的行程会出现在这里</view>
        </view>
        <Icon name="right" :size="18" color="var(--muted)" />
      </view>
    </view>
    <CityPicker v-if="picking" :current="city" @close="picking = false" @pick="changeCity" />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../../api";
import { getToken } from "../../api/http";
import type { ItineraryDay, Place, Trip } from "../../api/types";
import CityPicker from "../../components/CityPicker.vue";
import Icon from "../../components/Icon.vue";
import Shell from "../../components/Shell.vue";
import { cityCover, citySpots } from "../../utils/cover";
import { shortDate, weekday, ymd } from "../../utils/format";
import { cityOf, currentCity, dayMapUrl, dayTravel, duration, mapUrl, setCurrentCity } from "../../utils/place";
import { useWide } from "../../utils/screen";

/**
 * 首页：电脑上是总览面板（大图提问、入口、热门景点、最近行程、地图、美食、机票酒店快捷查询），手机上是原型①的单列布局。
 * 景点、美食来自高德，行程是自己最近的一个，城市跟顶栏选的走。
 */
const wide = useWide();
const city = currentCity();
const question = ref("");
const miniDay = ref(0);
const bookTab = ref("机票比价");
const picking = ref(false);

const spots = ref<Place[]>([]);
const spotsLoaded = ref(false);
const foods = ref<Place[]>([]);
const foodsLoaded = ref(false);
const trip = ref<Trip | null>(null);
const tripLoaded = ref(false);
const tripCover = ref("");
const days = ref<ItineraryDay[]>([]);

const hero = computed(() => spots.value.find((s) => s.photo)?.photo || "");
const dayStops = computed(() => (days.value[miniDay.value]?.items || []).filter((i) => i.type !== "TRANSPORT").slice(0, 3));
const dayMap = computed(() => dayMapUrl(days.value[miniDay.value], 420, 190));
const homeMap = computed(() => {
  if (trip.value && dayMap.value) return dayMap.value;
  const stops = spots.value.filter((s) => s.lng != null && s.lat != null).slice(0, 6).map((s) => [s.lng!, s.lat!]);
  return stops.length ? mapUrl({ stops, w: 420, h: 190 }) : "";
});
const travel = computed(() => dayTravel(days.value[miniDay.value]));
const legCount = computed(() => {
  const legs = days.value[miniDay.value]?.legs || [];
  const drive = legs.filter((l) => l.mode === "DRIVE");
  const walk = legs.filter((l) => l.mode === "WALK");
  return {
    drive: drive.length,
    driveMin: drive.reduce((s, l) => s + l.minutes, 0),
    walk: walk.length,
    walkMin: walk.reduce((s, l) => s + l.minutes, 0),
  };
});

// 机票、酒店快捷查询的表单
const today = ymd(new Date());
const ORIGIN_KEY = "life_origin";
const origin = ref<string>(uni.getStorageSync(ORIGIN_KEY) || "");
const destination = ref(city);
const flightDate = ref(ymd(addDays(new Date(), 7)));
const hotelPlace = ref("");
const checkIn = ref(ymd(addDays(new Date(), 7)));
const checkOut = ref(ymd(addDays(new Date(), 8)));

const FEATURES = [
  { label: "个性化行程", icon: "checkCircle" },
  { label: "智能路线规划", icon: "route" },
  { label: "机票酒店比价", icon: "plane" },
  { label: "美食推荐", icon: "food" },
  { label: "实时天气", icon: "weather" },
];
const BOOK_TABS = ["机票比价", "酒店比价", "门票"];

const quick = computed(() => [
  `${city}3天2晚经典路线`,
  `${city}亲子游攻略`,
  `${city}美食之旅`,
  ...(spots.value[0] ? [`${spots.value[0].name}怎么玩`] : []),
  "预算3000元方案",
  "带老人出行",
]);
const popular = computed(() => [
  `帮我规划${city}3天2晚`,
  `${city}周末去哪玩？`,
  `${city}有什么必吃的？`,
  "帮我比较机票和酒店价格",
]);

const deskEntries = [
  { label: "行程规划", sub: "AI 定制路线", icon: "umbrella", color: "#16a34a", bg: "#e7f6ec", go: () => openChat(`帮我规划${city}的旅行`) },
  { label: "机票比价", sub: "多平台价格对比", icon: "plane", color: "#2563eb", bg: "#e6eefd", go: () => go("/pages/search/flight") },
  { label: "酒店比价", sub: "多平台房价对比", icon: "bed", color: "#7c3aed", bg: "#f0e9fd", go: () => go("/pages/search/flight?tab=hotel") },
  { label: "景点推荐", sub: "热门景点攻略", icon: "pin", color: "#e11d48", bg: "#fde8ec", go: () => openChat(`${city}有哪些值得去的景点？`) },
  { label: "美食推荐", sub: "地道本地美食", icon: "food", color: "#ea580c", bg: "#fff1e6", go: () => go("/pages/search/food") },
  { label: "地图路线", sub: "可视化行程", icon: "nav", color: "#0d9488", bg: "#e2f5f2", go: () => go(trip.value ? `/pages/plan/map?id=${trip.value.id}&day=1` : "/pages/plan/map") },
  { label: "天气查询", sub: "实时天气建议", icon: "weather", color: "#0284c7", bg: "#e3f2fb", go: () => openChat(`${city}这周天气怎么样？`) },
];

const entries = [
  { label: "旅行规划", sub: "智能生成行程", icon: "umbrella", color: "#16a34a", bg: "#e7f6ec", go: () => openChat(`帮我规划${city}的旅行`) },
  { label: "机票比价", sub: "多平台价格对比", icon: "plane", color: "#2563eb", bg: "#e6eefd", go: () => go("/pages/search/flight") },
  { label: "酒店比价", sub: "多平台房价对比", icon: "bed", color: "#7c3aed", bg: "#f0e9fd", go: () => go("/pages/search/flight?tab=hotel") },
  { label: "美食推荐", sub: "当地特色美食", icon: "pin", color: "#e11d48", bg: "#fde8ec", go: () => go("/pages/search/food") },
  { label: "地图出行", sub: "路线规划导航", icon: "nav", color: "#0d9488", bg: "#e2f5f2", go: () => go(trip.value ? `/pages/plan/map?id=${trip.value.id}&day=1` : "/pages/plan/map") },
  { label: "天气查询", sub: "实时天气提醒", icon: "weather", color: "#0284c7", bg: "#e3f2fb", go: () => openChat(`${city}这周天气怎么样，适合出去玩吗？`) },
];

onMounted(() => {
  if (!getToken()) return;
  citySpots(city, 6).then((list) => (spots.value = list)).finally(() => (spotsLoaded.value = true));
  api.places({ kind: "food", city, size: 8 }).then((list) => (foods.value = list)).catch(() => {}).finally(() => (foodsLoaded.value = true));
  loadTrip();
});

/** 最近的一个行程：取当前版本的逐日安排，还没补坐标就让后端补（补完才有照片和路线图）。 */
async function loadTrip() {
  try {
    const list = await api.trips();
    trip.value = list[0] || null;
    if (!trip.value) return;
    cityCover(cityOf(trip.value.destination || trip.value.title)).then((url) => (tripCover.value = url));
    let detail = await api.trip(trip.value.id);
    days.value = detail.version.itinerary?.days || [];
    if (detail.version.itinerary && !detail.version.itinerary.geo) {
      detail = await api.tripGeo(trip.value.id);
      days.value = detail.version.itinerary?.days || [];
    }
  } catch {
    // 首页上取不到行程只显示空状态，不弹错
  } finally {
    tripLoaded.value = true;
  }
}

/** 景点的小标签：高德分类和商圈。 */
function tagsOf(p: Place): string[] {
  return [p.category, p.area].filter((t): t is string => !!t).slice(0, 2);
}

function dateRange(t: Trip): string {
  if (!t.startDate) return "日期待定";
  return `${shortDate(t.startDate)} – ${shortDate(t.endDate || t.startDate)}`;
}

function statusText(t: Trip): string {
  if (t.status === "DRAFT") return "草稿";
  if (t.status === "FINISHED" || (t.endDate && t.endDate < today)) return "已完成";
  return "进行中";
}

function addDays(d: Date, n: number): Date {
  const out = new Date(d);
  out.setDate(out.getDate() + n);
  return out;
}

function go(url: string) {
  uni.navigateTo({ url });
}

/** 去对话页，带上问题就直接发出去。 */
function openChat(q?: string) {
  go("/pages/chat/chat" + (q ? `?q=${encodeURIComponent(q)}` : ""));
}

function ask() {
  const q = question.value.trim();
  if (!q) return;
  question.value = "";
  openChat(q);
}

function goTrips() {
  uni.switchTab({ url: "/pages/trips/trips" });
}

function openTrip(id: number) {
  go(`/pages/trips/detail?id=${id}`);
}

function swapCities() {
  [origin.value, destination.value] = [destination.value, origin.value];
}

function searchFlights() {
  if (!origin.value.trim() || !destination.value.trim()) {
    uni.showToast({ title: "填一下出发和到达城市", icon: "none" });
    return;
  }
  uni.setStorageSync(ORIGIN_KEY, origin.value.trim());
  go(`/pages/search/flight?origin=${encodeURIComponent(origin.value.trim())}&destination=${encodeURIComponent(destination.value.trim())}&date=${flightDate.value}`);
}

function setCheckIn(v: string) {
  checkIn.value = v;
  if (checkOut.value <= v) checkOut.value = ymd(addDays(new Date(`${v}T00:00:00`), 1));
}

function searchHotels() {
  const place = hotelPlace.value.trim();
  go(`/pages/search/hotel?city=${encodeURIComponent(city)}&checkIn=${checkIn.value}&checkOut=${checkOut.value}` + (place ? `&place=${encodeURIComponent(place)}` : ""));
}

/** 手机上换城市：存下来，重新打开首页。 */
function changeCity(c: string) {
  picking.value = false;
  setCurrentCity(c);
  uni.reLaunch({ url: "/pages/home/home" });
}
</script>

<style scoped>
/* ===== 电脑：总览面板 ===== */
.desk { padding: 20px 24px 28px; display: flex; flex-direction: column; gap: 18px; }
.banner { position: relative; border-radius: 20px; overflow: hidden; min-height: 340px; }
.banner-img { position: absolute; inset: 0; width: 100%; height: 100%; }
.banner-shade { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(10, 18, 40, 0.78), rgba(10, 18, 40, 0.35) 60%, rgba(10, 18, 40, 0.1)); }
.banner-body { position: relative; padding: 40px 44px 30px; color: #fff; display: flex; flex-direction: column; gap: 14px; max-width: 860px; }
.banner-title { font-size: 40px; font-weight: 800; letter-spacing: 0.02em; text-wrap: balance; }
.banner-sub { font-size: 17px; opacity: 0.92; }
.features { display: flex; flex-wrap: wrap; gap: 10px; }
.feature { display: flex; align-items: center; gap: 6px; font-size: 13px; padding: 6px 12px; border-radius: 999px; background: rgba(255, 255, 255, 0.16); border: 1px solid rgba(255, 255, 255, 0.3); }
.big-ask { display: flex; align-items: center; gap: 10px; background: #fff; border-radius: 16px; padding: 10px 10px 10px 20px; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25); }
.big-input { flex: 1; font-size: 16px; color: #111827; height: 40px; }
.big-send { width: 48px; height: 48px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; cursor: pointer; }
.quick { display: flex; flex-wrap: wrap; gap: 10px; }
.quick-chip { font-size: 13px; padding: 7px 14px; border-radius: 999px; background: rgba(255, 255, 255, 0.92); color: #1b2333; cursor: pointer; }

.grid { display: grid; grid-template-columns: minmax(0, 1fr) 420px; gap: 18px; align-items: start; }
.col-main, .col-side { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.panel { background: var(--surface); border-radius: 16px; padding: 16px; border: 1px solid var(--line); }
.panel-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }
.panel-title { font-size: 16px; font-weight: 700; }
.more { font-size: 13px; color: var(--accent); cursor: pointer; }
.entries-row { display: grid; grid-template-columns: repeat(7, 1fr); gap: 8px; }
.d-entry { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 12px; border-radius: 12px; cursor: pointer; }
.d-entry:hover { background: var(--surface-2); }
.d-entry-icon { width: 44px; height: 44px; border-radius: 12px; display: flex; align-items: center; justify-content: center; margin-bottom: 4px; }
.d-entry-label { font-weight: 700; font-size: 15px; }
.d-entry-sub { font-size: 12px; color: var(--muted); }

.two { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; }
.spots { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.spot { border-radius: 12px; overflow: hidden; border: 1px solid var(--line); cursor: pointer; }
.spot-img { width: 100%; height: 100px; display: block; }
.spot-body { padding: 8px; }
.spot-name { font-weight: 700; font-size: 14px; }
.spot-rating { font-size: 12px; color: var(--price); }
.spot-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.mini-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); color: var(--muted); }

.plan-title-row { display: flex; align-items: center; justify-content: space-between; margin: 14px 0 10px; }
.plan-title { font-weight: 700; }
.save { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--accent); border: 1px solid var(--accent); padding: 4px 10px; border-radius: 8px; cursor: pointer; }
.mini-days { display: grid; gap: 6px; margin-bottom: 10px; }
.mini-day { display: flex; flex-direction: column; align-items: center; padding: 6px 0; border-radius: 10px; background: var(--surface-2); cursor: pointer; }
.mini-day.on { background: var(--accent); color: #fff; }
.mini-day-no { font-weight: 700; font-size: 14px; }
.mini-day-date { font-size: 11px; opacity: 0.8; }
.mini-timeline { display: flex; flex-direction: column; gap: 8px; }
.mini-stop { display: flex; gap: 10px; align-items: center; padding: 8px; border-radius: 12px; border: 1px solid var(--line); cursor: pointer; }
.mini-no { width: 20px; height: 20px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.mini-img { width: 72px; height: 54px; border-radius: 8px; flex-shrink: 0; }
.mini-body { min-width: 0; }
.mini-name { font-weight: 700; font-size: 14px; }
.mini-time { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.mini-note { font-size: 12px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.home-map { width: 100%; height: 190px; border-radius: 12px; display: block; }
.modes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.mode { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 10px; border: 1px solid var(--line); font-size: 13px; }
.mode.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.mode-sub { font-size: 11px; opacity: 0.8; }
.foods { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.food { min-width: 0; cursor: pointer; display: flex; flex-direction: column; gap: 3px; align-items: flex-start; }
.food-img { width: 100%; height: 76px; border-radius: 10px; }
.food-name { font-size: 13px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.food-meta { font-size: 11px; color: var(--muted); }
.star { color: var(--price); }
.seg { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; margin-bottom: 10px; }
.seg-item { flex: 1; text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; cursor: pointer; color: var(--muted); }
.seg-item.on { background: var(--accent); color: #fff; font-weight: 600; }
.ticket-empty { text-align: center; padding: 24px 0; font-size: 13px; }
.sample-note { font-size: 11px; color: var(--muted); text-align: right; margin-top: 8px; }
.banner-img.blank, .hero-img.blank, .recent-img.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); }
.spot-img.blank, .food-img.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); display: flex; align-items: center; justify-content: center; }
.empty-line { font-size: 13px; color: var(--muted); padding: 18px 0; text-align: center; }
.empty-trip { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 28px 0 12px; text-align: center; }
.empty-trip .btn { margin: 6px 0 0; }
.empty-title { font-weight: 700; }
.small { font-size: 12px; }
.plan-title-box { min-width: 0; }
.plan-meta { font-size: 12px; color: var(--muted); margin-top: 2px; font-variant-numeric: tabular-nums; }
.state { font-size: 11px; padding: 1px 8px; border-radius: 999px; margin-left: 6px; }
.state.ok { background: var(--ok-soft); color: var(--ok); }
.state.draft { background: #fff4ea; color: #c2560c; }
.blank-map { display: flex; align-items: center; justify-content: center; background: var(--surface-2); color: var(--muted); font-size: 13px; }
.map-caption { font-size: 12px; color: var(--muted); margin-top: 8px; line-height: 1.6; }
.form { display: flex; flex-direction: column; gap: 10px; }
.form .btn { margin: 0; }
.form-row { display: flex; align-items: center; gap: 8px; }
.field { flex: 1; min-width: 0; height: 38px; padding: 0 12px; border-radius: 10px; background: var(--surface-2); font-size: 14px; box-sizing: border-box; }
.field.picker { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.half { flex: 1; min-width: 0; }
.swap { width: 30px; height: 30px; border-radius: 50%; border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.topbar-right { display: flex; align-items: center; gap: 10px; }
.city-chip { display: flex; align-items: center; gap: 4px; font-size: 14px; padding: 4px 10px; border-radius: 999px; background: var(--surface-2); cursor: pointer; }
.m-spots { white-space: nowrap; }
.m-spot { display: inline-block; width: 132px; margin-right: 10px; vertical-align: top; cursor: pointer; }
.m-spot-img { width: 132px; height: 92px; border-radius: 12px; display: block; }
.m-spot-name { font-size: 13px; font-weight: 600; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-spot-rating { font-size: 12px; color: var(--price); }
.empty-recent { border: 1px dashed var(--line); }
@media (max-width: 1280px) {
  .grid { grid-template-columns: 1fr; }
  .entries-row { grid-template-columns: repeat(4, 1fr); }
}

/* ===== 手机：原型① ===== */
.home { min-height: 100vh; background: var(--bg); }
.topbar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(10px + env(safe-area-inset-top, 0px)) max(16px, env(safe-area-inset-right)) 10px max(16px, env(safe-area-inset-left));
  background: var(--surface);
}
.brand { display: flex; align-items: center; gap: 8px; }
.logo {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-name { font-weight: 700; font-size: 16px; }
.topbar-link { padding: 4px; cursor: pointer; }

.page { max-width: 760px; margin: 0 auto; padding: 12px 16px 24px; box-sizing: border-box; }

.hero { position: relative; border-radius: 18px; overflow: hidden; height: 300px; }
.hero-img { position: absolute; inset: 0; width: 100%; height: 100%; }
.hero-text {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  padding: 22px 20px 40px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0));
}
.hero-title { font-size: 24px; font-weight: 800; color: #111827; text-wrap: balance; }
.hero-sub { font-size: 13px; color: #374151; margin-top: 6px; line-height: 1.6; }
.hero-sub.tight { margin-top: 0; }
.ask {
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border-radius: 14px;
  padding: 8px 8px 8px 14px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.15);
}
.ask-input { flex: 1; min-width: 0; font-size: 14px; color: #111827; height: 36px; }
.ask-send {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}

.entries {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  margin-top: 14px;
  background: var(--surface);
  border-radius: 16px;
  padding: 14px 6px;
}
.entry { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 8px 2px; cursor: pointer; }
.entry-icon { width: 46px; height: 46px; border-radius: 14px; display: flex; align-items: center; justify-content: center; }
.entry-label { font-size: 14px; font-weight: 600; margin-top: 2px; }
.entry-sub { font-size: 11px; color: var(--muted); }

.section-head { display: flex; align-items: baseline; justify-content: space-between; margin: 20px 2px 10px; }
.section-name { font-size: 16px; font-weight: 700; }
.section-more { font-size: 13px; color: var(--muted); cursor: pointer; }
.asks { display: flex; flex-wrap: wrap; gap: 8px; }
.ask-chip {
  font-size: 13px;
  padding: 7px 12px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--line);
  cursor: pointer;
}

.recent {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--surface);
  border-radius: 16px;
  padding: 10px;
  cursor: pointer;
}
.recent-img { width: 84px; height: 64px; border-radius: 10px; flex-shrink: 0; }
.recent-body { flex: 1; min-width: 0; }
.recent-title { font-weight: 600; }
.recent-meta { font-size: 12px; color: var(--muted); margin-top: 2px; }
.recent-tags { display: flex; gap: 6px; margin-top: 6px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.tag.blue { background: var(--accent-soft); color: var(--accent); }

@media (min-width: 700px) {
  .entries { grid-template-columns: repeat(6, 1fr); }
  .hero { height: 340px; }
}
</style>
