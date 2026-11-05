<template>
  <Shell active="home">
    <view v-if="wide" class="desk">
      <view class="d-main">
        <view class="hero">
          <image v-if="hero" class="hero-bg" :src="hero" mode="aspectFill" />
          <view class="hero-body">
            <view class="hero-brand">
              <BrandMark :size="44" />
              <view>
                <view class="hero-brand-name">星络 <text class="en">Xinglo</text></view>
                <view class="hero-brand-sub">你的生活助理</view>
              </view>
            </view>
            <view class="hero-title">告诉星络你想做什么</view>
            <view class="hero-links">
              <view v-for="(l, i) in HERO_LINKS" :key="l.label" class="hero-link" @click="l.go()">
                <text v-if="i" class="hero-sep">|</text>
                <Icon :name="l.icon" :size="15" color="var(--muted)" /><text>{{ l.label }}</text>
              </view>
            </view>
            <view class="ask-box">
              <textarea
                id="desk-ask"
                v-model="question"
                class="ask-input"
                :auto-height="true"
                :maxlength="2000"
                :show-confirm-bar="false"
                confirm-type="send"
                :placeholder="`和星络聊天，规划旅行、安排日程、查询机票酒店、发现${city}美食…`"
                @confirm="ask"
              />
              <view class="ask-tools">
                <view class="ask-tool" @click="openChat()"><Icon name="plus" :size="14" color="var(--muted)" /><text>上传文件</text></view>
                <view class="ask-right">
                  <ModelPicker :wide="wide" />
                  <view class="ask-send" :class="{ off: !question.trim() }" @click="ask"><Icon name="send" :size="18" color="#fff" /></view>
                </view>
              </view>
            </view>
          </view>
        </view>

        <view class="sec-head"><text class="sec-title">试试这些，和星络聊聊</text><Icon name="sparkle" :size="14" color="var(--accent)" /></view>
        <view class="examples">
          <view v-for="x in examples" :key="x.title" class="example" @click="openChat(x.ask)">
            <DuoIcon :name="x.icon" :size="24" :tile="44" />
            <view class="ex-body">
              <view class="ex-title">{{ x.title }}</view>
              <view class="ex-sub">{{ x.ask }}</view>
            </view>
            <Icon name="right" :size="16" color="var(--muted)" />
          </view>
        </view>

        <view class="sec-head">
          <text class="sec-title">{{ city }}推荐</text>
          <text v-if="spots.length > 4" class="sec-more" @click="nextSpots"><Icon name="refresh" :size="12" color="var(--accent)" /> 换一换</text>
        </view>
        <view v-if="spotPage.length" class="recs">
          <view v-for="sp in spotPage" :key="sp.id" class="rec" @click="openChat(`介绍一下${sp.name}，适合怎么玩`)">
            <image v-if="sp.photo" class="rec-img" :src="sp.photo" mode="aspectFill" />
            <view v-else class="rec-img blank"><Icon name="pin" :size="22" color="#fff" /></view>
            <view class="rec-name">{{ sp.name }}</view>
            <view class="rec-meta"><text v-if="sp.rating" class="star">★ {{ sp.rating }}</text><text> {{ sp.category }}</text></view>
          </view>
        </view>
        <view v-else class="muted small">{{ spotsLoaded ? `没找到${city}的景点` : "加载中…" }}</view>
      </view>

      <view class="d-side">
        <view class="card weather" @click="go('/pages/weather/weather')">
          <Icon name="weather" :size="34" color="#F5A623" />
          <view class="w-body">
            <view class="w-main">{{ city }} {{ weather ? `${weather.tempMin}~${weather.tempMax}°C` : "" }} <text class="w-text">{{ weather?.text || "" }}</text></view>
            <view class="muted small">{{ weather ? "看看这几天的天气和出行建议" : "点开看天气" }}</view>
          </view>
          <Icon name="right" :size="14" color="var(--muted)" />
        </view>

        <view class="card"><TodayCard :agenda="agenda" :error="agendaError" wide @ask="openChat" /></view>

        <view v-if="tripCard" class="card trip-card" @click="openTrip(tripCard.id)">
          <view class="tc-head"><text class="tc-label">{{ agenda?.trip ? "进行中的行程" : "最近的行程" }}</text><Icon name="right" :size="14" color="var(--muted)" /></view>
          <view class="tc-row">
            <image v-if="tripCover" class="tc-img" :src="tripCover" mode="aspectFill" />
            <view v-else class="tc-img blank"><Icon name="trip" :size="20" color="#fff" /></view>
            <view class="tc-body">
              <view class="tc-title">{{ tripCard.title }}</view>
              <view class="muted small">{{ tripCard.sub }}</view>
            </view>
          </view>
          <view v-if="nextStop" class="tc-next" @click.stop>
            <text class="muted small">下一站</text>
            <text class="tc-next-name">{{ nextStop.time }} {{ nextStop.title }}</text>
            <text v-if="canGo(nextStop)" class="tc-btn" @click="navigate(nextStop)">导航</text>
          </view>
        </view>
        <view v-else-if="tripLoaded" class="card trip-card empty" @click="openChat(`帮我规划${city}3天2晚的行程`)">
          <Icon name="trip" :size="22" color="var(--accent)" />
          <view class="tc-body"><view class="tc-title">说出想去哪，星络帮你规划</view><view class="muted small">规划好的行程会出现在这里</view></view>
        </view>

        <view class="card">
          <view class="qf-head"><text class="qf-title">快捷功能</text></view>
          <view class="qf-grid">
            <view v-for="q in quickDesk" :key="q.label" class="qf" @click="q.go()">
              <DuoIcon :name="q.icon" :size="22" :tile="40" />
              <text class="qf-label">{{ q.label }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <view v-else class="home">
      <view class="m-top">
        <view class="m-brand">
          <BrandMark :size="34" />
          <view>
            <view class="m-brand-name">星络 <text class="en">Xinglo</text></view>
            <view class="m-brand-sub">你的生活助理</view>
          </view>
        </view>
        <view class="city-chip" @click="picking = true"><Icon name="pin" :size="13" color="var(--ink)" /><text>{{ city }}</text><Icon name="down" :size="11" color="var(--muted)" /></view>
      </view>

      <view class="greet">
        <view class="greet-title">{{ greeting }}{{ nickname ? `，${nickname}` : "" }}</view>
        <view class="greet-sub" @click="go('/pages/weather/weather')">
          <Icon name="weather" :size="15" color="#F5A623" />
          <text>{{ city }} {{ weather ? `${weather.tempMin}~${weather.tempMax}°C ${weather.text}` : "" }}</text>
        </view>
      </view>

      <view class="m-ask">
        <view class="m-ask-row">
          <Icon name="sparkle" :size="18" color="var(--accent)" />
          <textarea
            id="home-ask"
            v-model="question"
            class="m-ask-input"
            :auto-height="true"
            :maxlength="2000"
            :show-confirm-bar="false"
            confirm-type="send"
            placeholder="告诉我你想做什么…"
            @confirm="ask"
          />
        </view>
        <scroll-view scroll-x class="m-chips">
          <text v-for="c in chips" :key="c" class="m-chip" @click="openChat(c)">{{ c }}</text>
        </scroll-view>
        <view class="m-ask-tools">
          <view class="m-tool" @click="openChat()"><Icon name="plus" :size="18" color="var(--muted)" /></view>
          <view class="ask-right">
            <ModelPicker small />
            <view class="m-send" :class="{ off: !question.trim() }" @click="ask"><Icon name="send" :size="18" color="#fff" /></view>
          </view>
        </view>
      </view>

      <view class="m-entries">
        <view v-for="q in quickMobile" :key="q.label" class="m-entry" @click="q.go()">
          <DuoIcon :name="q.icon" :size="24" :tile="44" />
          <text class="m-entry-label">{{ q.label }}</text>
        </view>
      </view>

      <!-- 新用户还没有日程和行程：先给几句能直接点的话，告诉他星络能干嘛 -->
      <view v-if="fresh" class="m-card m-start">
        <view class="m-start-title">第一次用？点一句试试</view>
        <view v-for="x in examples" :key="x.title" class="m-start-row" @click="openChat(x.ask)">
          <DuoIcon :name="x.icon" :size="20" :tile="36" />
          <view class="ex-body">
            <view class="m-start-name">{{ x.title }}</view>
            <view class="ex-sub">{{ x.ask }}</view>
          </view>
          <Icon name="right" :size="14" color="var(--muted)" />
        </view>
      </view>
      <view v-else class="m-card"><TodayCard :agenda="agenda" :error="agendaError" @ask="openChat" /></view>

      <view v-if="tripCard" class="m-card m-trip" @click="openTrip(tripCard.id)">
        <image v-if="tripCover" class="tc-img" :src="tripCover" mode="aspectFill" />
        <view v-else class="tc-img blank"><Icon name="trip" :size="20" color="#fff" /></view>
        <view class="tc-body">
          <view class="tc-label">{{ agenda?.trip ? "进行中的行程" : "最近的行程" }}</view>
          <view class="tc-title">{{ tripCard.title }}</view>
          <view class="muted small">{{ nextStop ? `下一站 ${nextStop.time} ${nextStop.title}` : tripCard.sub }}</view>
        </view>
        <text v-if="nextStop && canGo(nextStop)" class="tc-btn" @click.stop="navigate(nextStop)">导航</text>
        <Icon v-else name="right" :size="16" color="var(--muted)" />
      </view>

      <view class="m-sec"><text class="sec-title">{{ city }}推荐</text><text class="sec-more" @click="openChat(`${city}有哪些值得去的地方？`)">更多</text></view>
      <scroll-view v-if="spots.length" scroll-x class="m-recs">
        <view v-for="sp in spots" :key="sp.id" class="m-rec" @click="openChat(`介绍一下${sp.name}，适合怎么玩`)">
          <image v-if="sp.photo" class="m-rec-img" :src="sp.photo" mode="aspectFill" />
          <view v-else class="m-rec-img blank" />
          <view class="m-rec-name">{{ sp.name }}</view>
          <view v-if="sp.rating" class="m-rec-meta">★ {{ sp.rating }}</view>
        </view>
      </scroll-view>
      <view v-else class="muted small m-pad">{{ spotsLoaded ? `没找到${city}的景点` : "加载中…" }}</view>
      <CityPicker v-if="picking" :current="city" @close="picking = false" @pick="changeCity" />
    </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { getToken } from "../../api/http";
import type { Place, TodayAgenda, Trip } from "../../api/types";
import BrandMark from "../../components/BrandMark.vue";
import CityPicker from "../../components/CityPicker.vue";
import DuoIcon from "../../components/DuoIcon.vue";
import ModelPicker from "../../components/ModelPicker.vue";
import Icon from "../../components/Icon.vue";
import Shell from "../../components/Shell.vue";
import TodayCard from "../../components/TodayCard.vue";
import { cityCover, citySpots } from "../../utils/cover";
import { shortDate, ymd } from "../../utils/format";
import { cityOf, currentCity, setCurrentCity } from "../../utils/place";
import { useWide } from "../../utils/screen";
import { canGo, navigate } from "../../utils/today";

/**
 * 首页：以和星络对话为中心。电脑上中间是提问框、示例和城市推荐，右栏是天气、今日安排、行程、快捷功能；
 * 手机上是问候和天气、提问框、功能入口、今日安排、行程卡、城市推荐。今日安排的每项能看详情、直接导航或打车。
 */
const wide = useWide();
const city = currentCity();
const question = ref("");
const picking = ref(false);
const nickname = ref("");
const weather = ref<{ text: string; tempMin: number; tempMax: number } | null>(null);
const agenda = ref<TodayAgenda | null>(null);
const agendaError = ref("");
const spots = ref<Place[]>([]);
const spotsLoaded = ref(false);
const spotStart = ref(0);
const trip = ref<Trip | null>(null);
const tripLoaded = ref(false);
const tripCover = ref("");
const today = ymd(new Date());

const hero = computed(() => spots.value.find((s) => s.photo)?.photo || "");
const spotPage = computed(() => {
  const list = spots.value;
  if (list.length <= 4) return list;
  return [0, 1, 2, 3].map((i) => list[(spotStart.value + i) % list.length]);
});

const greeting = computed(() => {
  const h = new Date().getHours();
  if (h < 5) return "夜深了";
  if (h < 11) return "早上好";
  if (h < 13) return "中午好";
  if (h < 18) return "下午好";
  return "晚上好";
});

/** 行程卡：旅行中显示进行中的那个，否则最近的一个 */
const tripCard = computed(() => {
  const t = agenda.value?.trip;
  if (t) return { id: t.id, title: t.title, sub: `第 ${t.dayNo}/${t.totalDays} 天` };
  if (trip.value) return { id: trip.value.id, title: trip.value.title, sub: `${dateRange(trip.value)} · ${statusText(trip.value)}` };
  return null;
});
/** 新用户：没有行程，今天也没有任何安排（手机首页把今日安排换成示例） */
const fresh = computed(() => tripLoaded.value && !trip.value && !!agenda.value && !agenda.value.trip && !agenda.value.entries.length);
/** 旅行中的下一站：今天还没开始的第一项行程安排 */
const nextStop = computed(() => (agenda.value?.trip ? agenda.value.entries.find((e) => e.kind === "TRIP" && !e.past) : undefined));

const HERO_LINKS = [
  { label: "旅行规划", icon: "plane", go: () => openChat(`帮我规划${city}3天2晚的行程`) },
  { label: "日程安排", icon: "calendar", go: () => uni.reLaunch({ url: "/pages/schedule/schedule" }) },
  { label: "机票酒店", icon: "bed", go: () => go("/pages/search/flight") },
  { label: "周边美食", icon: "food", go: () => go("/pages/search/food") },
];

const examples = computed(() => [
  { title: "规划一次旅行", icon: "plane", ask: `帮我规划一个${city}3天2晚的行程` },
  { title: "安排我的日程", icon: "calendar", ask: "明天下午 3 点提醒我去取快递" },
  { title: "查询机票酒店", icon: "bed", ask: `帮我找${spots.value[0]?.name || city}附近的酒店比比价` },
  { title: "附近有啥好吃的", icon: "food", ask: `${city}有什么必吃的本地菜？` },
]);
const chips = computed(() => [`周末${city}怎么玩`, `${city}必吃美食`, "明早 8 点提醒我", "帮我比比机票"]);

const quickDesk = [
  { label: "旅行规划", icon: "plane", go: () => openChat(`帮我规划${city}的旅行`) },
  { label: "我的日程", icon: "calendar", go: () => uni.reLaunch({ url: "/pages/schedule/schedule" }) },
  { label: "机票比价", icon: "flyCloud", go: () => go("/pages/search/flight") },
  { label: "酒店比价", icon: "bed", go: () => go("/pages/search/hotel") },
  { label: "附近美食", icon: "food", go: () => go("/pages/search/food") },
  { label: "景点门票", icon: "ticket", go: () => go("/pages/search/ticket") },
  { label: "地图出行", icon: "map", go: () => go(tripCard.value ? `/pages/plan/map?id=${tripCard.value.id}&day=1` : "/pages/plan/map") },
  { label: "天气", icon: "weather", go: () => go("/pages/weather/weather") },
];
const quickMobile = [
  { label: "旅行规划", icon: "plane", go: () => openChat(`帮我规划${city}的旅行`) },
  { label: "附近美食", icon: "food", go: () => go("/pages/search/food") },
  { label: "地图出行", icon: "map", go: () => go(tripCard.value ? `/pages/plan/map?id=${tripCard.value.id}&day=1` : "/pages/plan/map") },
  { label: "景点门票", icon: "ticket", go: () => go("/pages/search/ticket") },
  { label: "机票酒店", icon: "suitcase", go: () => go("/pages/search/flight") },
  { label: "去哪儿玩", icon: "mountain", go: () => go("/pages/discover/discover") },
];

onMounted(() => {
  if (!getToken()) return;
  api.me().then((u) => (nickname.value = u.nickname || u.username)).catch(() => {});
  // 天气偶尔超时，失败了隔两秒再试一次
  api.weatherToday(city).catch(() => new Promise((r) => setTimeout(r, 2000)).then(() => api.weatherToday(city)))
    .then((w) => (weather.value = w as typeof weather.value)).catch(() => {});
  citySpots(city, 8).then((list) => (spots.value = list)).finally(() => (spotsLoaded.value = true));
  loadTrip();
});

// 今日安排每次回到首页都刷新（可能刚在对话里加了日程）
onShow(() => {
  if (getToken()) loadAgenda();
});

async function loadAgenda() {
  try {
    agenda.value = await api.today(city);
    agendaError.value = "";
  } catch (e) {
    if (!agenda.value) agendaError.value = "今日安排没加载出来，下拉或稍后再试";
  }
}

/** 最近的一个行程（不在旅行中时显示） */
async function loadTrip() {
  try {
    const list = await api.trips();
    trip.value = list[0] || null;
    if (trip.value) cityCover(cityOf(trip.value.destination || trip.value.title)).then((url) => (tripCover.value = url));
  } catch {
    // 首页取不到行程只显示空状态
  } finally {
    tripLoaded.value = true;
  }
}

function nextSpots() {
  spotStart.value = (spotStart.value + 4) % spots.value.length;
}

function dateRange(t: Trip): string {
  if (!t.startDate) return "日期待定";
  return `${shortDate(t.startDate)} – ${shortDate(t.endDate || t.startDate)}`;
}

function statusText(t: Trip): string {
  if (t.status === "DRAFT") return "草稿";
  if (t.status === "FINISHED" || (t.endDate && t.endDate < today)) return "已完成";
  if (!t.startDate) return "日期待定";
  if (t.startDate <= today) return "进行中";
  const n = Math.round((Date.parse(t.startDate) - Date.parse(today)) / 86400000);
  return n === 1 ? "明天出发" : `${n} 天后出发`;
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

function openTrip(id: number) {
  go(`/pages/trips/detail?id=${id}`);
}

function changeCity(c: string) {
  picking.value = false;
  setCurrentCity(c);
}
</script>

<style scoped>
/* ===== 电脑 ===== */
.desk { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 18px; padding: 18px 22px 28px; }
.d-main { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.d-side { display: flex; flex-direction: column; gap: 14px; }

.hero { position: relative; overflow: hidden; border-radius: 22px; background: linear-gradient(180deg, var(--accent-soft), var(--surface) 75%); border: 1px solid var(--line); }
.hero-bg { position: absolute; left: 0; right: 0; bottom: 0; width: 100%; height: 45%; opacity: 0.18; -webkit-mask-image: linear-gradient(to top, #000 20%, transparent); mask-image: linear-gradient(to top, #000 20%, transparent); }
.hero-body { position: relative; display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 34px 40px 30px; }
.hero-brand { display: flex; align-items: center; gap: 12px; }
.hero-brand-name { font-size: 20px; font-weight: 800; }
.hero-brand-name .en, .m-brand-name .en { color: var(--accent); }
.hero-brand-sub { font-size: 12px; color: var(--muted); }
.hero-title { font-size: 34px; font-weight: 800; letter-spacing: 1px; text-align: center; }
.hero-links { display: flex; flex-wrap: wrap; justify-content: center; gap: 4px 0; }
.hero-link { display: flex; align-items: center; gap: 5px; font-size: 14px; color: var(--muted); cursor: pointer; }
.hero-link:hover { color: var(--accent); }
.hero-sep { color: var(--line); margin: 0 14px; }
.ask-box { width: 100%; max-width: 860px; background: var(--surface); border: 1.5px solid var(--accent); border-radius: 18px; padding: 14px 16px 10px; box-shadow: 0 10px 30px rgba(47, 107, 242, 0.12); display: flex; flex-direction: column; gap: 8px; }
.ask-input { width: auto; font-size: 16px; line-height: 24px; min-height: 48px; max-height: 120px; overflow-y: auto; color: var(--ink); }
.ask-tools { display: flex; align-items: center; justify-content: space-between; }
.ask-right { display: flex; align-items: center; gap: 8px; }
.ask-tool { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--muted); padding: 5px 10px; border: 1px solid var(--line); border-radius: 999px; cursor: pointer; }
.ask-send, .m-send { width: 42px; height: 42px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.ask-send.off, .m-send.off { opacity: 0.45; }

.sec-head, .m-sec { display: flex; align-items: center; gap: 6px; }
.sec-title { font-size: 16px; font-weight: 700; }
.sec-more { margin-left: auto; font-size: 13px; color: var(--accent); cursor: pointer; display: flex; align-items: center; gap: 4px; }
.examples { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.example { display: flex; align-items: center; gap: 12px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px 16px; cursor: pointer; }
.example:hover { border-color: var(--accent); }
.ex-body { flex: 1; min-width: 0; }
.ex-title { font-size: 15px; font-weight: 700; }
.ex-sub { font-size: 12px; color: var(--muted); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.recs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.rec { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; overflow: hidden; cursor: pointer; padding-bottom: 10px; }
.rec-img { width: 100%; height: 110px; display: block; }
.rec-img.blank, .m-rec-img.blank, .tc-img.blank { background: var(--accent); opacity: 0.7; display: flex; align-items: center; justify-content: center; }
.rec-name { font-size: 14px; font-weight: 600; padding: 8px 10px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rec-meta { font-size: 12px; color: var(--muted); padding: 2px 10px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.star { color: var(--price); font-weight: 700; }

.card { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 14px 16px; }
.weather { display: flex; align-items: center; gap: 12px; cursor: pointer; }
.w-body { flex: 1; min-width: 0; }
.w-main { font-size: 18px; font-weight: 800; }
.w-text { font-size: 13px; font-weight: 400; color: var(--muted); }
.trip-card { display: flex; flex-direction: column; gap: 10px; cursor: pointer; }
.trip-card.empty { flex-direction: row; align-items: center; }
.tc-head { display: flex; justify-content: space-between; align-items: center; }
.tc-label { font-size: 12px; color: var(--muted); }
.tc-row { display: flex; gap: 10px; align-items: center; }
.tc-img { width: 64px; height: 48px; border-radius: 10px; flex-shrink: 0; }
.tc-body { flex: 1; min-width: 0; }
.tc-title { font-size: 14px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tc-next { display: flex; align-items: center; gap: 8px; padding-top: 8px; border-top: 1px solid var(--line); }
.tc-next-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tc-btn { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: var(--accent-soft); color: var(--accent); font-weight: 600; white-space: nowrap; }
.qf-head { margin-bottom: 10px; }
.qf-title { font-size: 15px; font-weight: 700; }
.qf-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px 6px; }
.qf { display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer; }
.qf-label { font-size: 12px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }

/* ===== 手机 ===== */
.home { display: flex; flex-direction: column; gap: 14px; padding: 12px 14px 24px; background: linear-gradient(180deg, var(--accent-soft), var(--bg) 320px); min-height: 100vh; box-sizing: border-box; }
.m-top { display: flex; align-items: center; justify-content: space-between; }
.m-brand { display: flex; align-items: center; gap: 10px; }
.m-brand-name { font-size: 17px; font-weight: 800; }
.m-brand-sub { font-size: 11px; color: var(--muted); }
.city-chip { display: flex; align-items: center; gap: 4px; padding: 6px 10px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); font-size: 13px; }
.greet { display: flex; flex-direction: column; gap: 4px; padding: 4px 2px 0; }
.greet-title { font-size: 24px; font-weight: 800; }
.greet-sub { display: flex; align-items: center; gap: 6px; font-size: 14px; color: var(--muted); }
.m-ask { background: var(--surface); border: 1.5px solid var(--accent); border-radius: 18px; padding: 12px 12px 10px; display: flex; flex-direction: column; gap: 10px; box-shadow: 0 8px 24px rgba(47, 107, 242, 0.12); }
.m-ask-row { display: flex; align-items: flex-start; gap: 8px; }
.m-ask-input { flex: 1; width: auto; font-size: 15px; line-height: 22px; min-height: 22px; max-height: 88px; overflow-y: auto; color: var(--ink); }
.m-chips { white-space: nowrap; }
.m-chip { display: inline-block; font-size: 12px; padding: 6px 12px; margin-right: 8px; border-radius: 999px; background: var(--surface-2); color: var(--ink); }
.m-ask-tools { display: flex; align-items: center; justify-content: space-between; }
.m-tool { width: 34px; height: 34px; border-radius: 50%; border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; }
.m-entries { display: grid; grid-template-columns: repeat(6, 1fr); gap: 4px; background: var(--surface); border-radius: 16px; padding: 12px 4px; border: 1px solid var(--line); }
.m-entry { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.m-entry-label { font-size: 11px; white-space: nowrap; }
.m-card { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 12px 14px; }
.m-trip { display: flex; align-items: center; gap: 10px; }
.m-start { display: flex; flex-direction: column; }
.m-start-title { font-size: 15px; font-weight: 700; padding-bottom: 4px; }
.m-start-row { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-top: 1px solid var(--line); }
.m-start-title + .m-start-row { border-top: none; }
.m-start-name { font-size: 14px; font-weight: 600; }
.m-sec { padding: 0 2px; }
.m-recs { white-space: nowrap; }
.m-rec { display: inline-block; width: 140px; margin-right: 10px; background: var(--surface); border-radius: 12px; overflow: hidden; border: 1px solid var(--line); vertical-align: top; padding-bottom: 8px; }
.m-rec-img { width: 140px; height: 90px; display: block; }
.m-rec-name { font-size: 13px; font-weight: 600; padding: 6px 8px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-rec-meta { font-size: 11px; color: var(--price); padding: 0 8px; }
.m-pad { padding: 0 4px; }
</style>
