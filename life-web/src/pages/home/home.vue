<template>
  <Shell active="home" :fill="wide">
    <view class="home-wrap" :class="{ wide }">
      <!-- 电脑：左边对话栏（和对话页同一个），点开接着聊 -->
      <ConvSidebar v-if="wide" @open="(c) => openConv(c.id)" @new="newChat" />

      <view class="home">
        <view v-if="!wide" class="m-top">
          <view class="m-city" @click="picking = true"><Icon name="pin" :size="13" color="var(--muted)" /><text>{{ city }}</text></view>
        </view>

        <view class="head">
          <view class="eyebrow"><BrandMark :size="18" /><text>星络 · 旅行规划与日常安排</text></view>
          <text class="title">你的 AI 旅行与日程助手</text>
          <text class="subtitle">告诉我目的地和预算，帮你规划路线、比较机票酒店，把安排保存下来。</text>
        </view>

        <view v-if="next" class="next" @click="openSchedule">
          <Icon name="bell" :size="15" color="var(--accent)" />
          <text class="next-when">{{ next.when }}</text>
          <text class="next-title">{{ next.title }}</text>
          <text v-if="next.location" class="next-loc">· {{ next.location }}</text>
          <Icon name="right" :size="14" color="var(--muted)" />
        </view>

        <view class="composer" :class="{ focus: focused }">
          <view v-if="pending.length" class="att-list">
            <view v-for="(a, i) in pending" :key="a.key" class="att" :class="a.state">
              <Icon :name="fileIcon(a.name)" :size="14" color="var(--muted)" />
              <text class="att-name">{{ a.name }}</text>
              <text class="att-state">{{ a.state === "uploading" ? "识别中…" : a.state === "error" ? a.error : a.view?.error ? "没识别出来" : "已识别" }}</text>
              <view class="att-x" @click="pending.splice(i, 1)"><Icon name="close" :size="12" color="var(--muted)" /></view>
            </view>
          </view>
          <textarea
            id="home-ask"
            v-model="question"
            class="c-input"
            :maxlength="2000"
            :focus="focused"
            :show-confirm-bar="false"
            confirm-type="send"
            :placeholder="`从${city}出发，周末玩两天，预算1500元，帮我安排一下…`"
            @focus="focused = true"
            @blur="focused = false"
            @confirm="ask"
          />
          <view class="c-tools">
            <view class="c-entry" @click="pickFiles"><Icon name="plus" :size="15" color="var(--ink)" /><text>添加附件</text></view>
            <text class="c-sep" />
            <view class="c-entry" @click="go('/pages/search/flight')"><Icon name="depart" :size="15" color="var(--ink)" /><text>机票比价</text></view>
            <text class="c-sep" />
            <view class="c-entry" @click="go('/pages/search/hotel')"><Icon name="bed" :size="15" color="var(--ink)" /><text>酒店比价</text></view>
            <view class="c-right">
              <VoiceButton @text="addVoice" />
              <button class="c-send" :disabled="!canSend" aria-label="发送" @click="ask"><Icon name="send" :size="17" color="#fff" /></button>
            </view>
          </view>
        </view>

        <!-- 今天有航班：直接放动态卡片（免费试查 / 会员不限） -->
        <view v-if="todayFlight" class="today-flight">
          <view class="sec-title">今天的航班 · {{ todayFlight.time }} {{ todayFlight.title }}</view>
          <FlightStatusCard :flight-no="todayFlight.no" :date="today" />
        </view>

        <view class="starts">
          <view v-for="c in STARTS" :key="c.title" class="start" @click="c.go()">
            <view class="start-ic"><Icon :name="c.icon" :size="18" color="var(--accent)" /></view>
            <view class="start-body">
              <text class="start-title">{{ c.title }}</text>
              <text class="start-desc">{{ c.desc }}</text>
            </view>
            <Icon name="right" :size="14" color="var(--muted)" />
          </view>
        </view>

        <view v-if="plans.length" class="plans">
          <view class="sec-title">未完成的计划</view>
          <view class="plan-list">
            <view v-for="p in plans" :key="p.id" class="plan" @click="openTrip(p.id)">
              <view class="plan-body">
                <view class="plan-title">{{ p.title }}</view>
                <view class="plan-meta">{{ p.meta }}</view>
              </view>
              <view class="plan-acts" @click.stop>
                <text v-if="p.next && canGo(p.next)" class="plan-act" @click="navigate(p.next)">导航去{{ p.next.title }}</text>
                <text class="plan-act" @click="adjust(p.title)">调整</text>
                <text class="plan-act strong" @click="openTrip(p.id)">查看</text>
              </view>
            </view>
          </view>
        </view>

        <!-- 示例：固定内容，只用来展示星络能排成什么样，不写进用户数据 -->
        <view class="demo">
          <view class="demo-head">
            <text class="demo-title">先看看，星络能帮你安排什么</text>
            <text class="demo-tag">行程示例</text>
          </view>
          <view class="ex">
            <view class="ex-main">
              <view class="ex-photo">
                <svg viewBox="0 0 400 150" preserveAspectRatio="xMidYMax slice" aria-hidden="true">
                  <rect class="sky" width="400" height="150" />
                  <circle class="sun" cx="318" cy="44" r="16" />
                  <path class="hill" d="M0 92 C60 58 110 66 160 80 C210 92 250 60 300 66 C340 70 370 84 400 78 V150 H0 Z" />
                  <g class="towers">
                    <rect x="40" y="70" width="14" height="40" /><rect x="58" y="58" width="12" height="52" /><rect x="74" y="76" width="16" height="34" />
                    <rect x="150" y="40" width="16" height="70" /><rect x="170" y="62" width="12" height="48" /><rect x="186" y="72" width="18" height="38" />
                    <polygon points="226,110 230,30 238,30 242,110" /><rect x="248" y="66" width="14" height="44" /><rect x="266" y="78" width="20" height="32" />
                    <rect x="300" y="70" width="12" height="40" /><rect x="316" y="60" width="16" height="50" /><rect x="336" y="82" width="22" height="28" />
                  </g>
                  <rect class="sea" y="108" width="400" height="42" />
                  <path class="ferry" d="M120 126 h44 l-6 8 h-32 z" /><rect class="ferry" x="130" y="118" width="22" height="8" rx="2" />
                  <path class="wave" d="M20 140 q10 -4 20 0 t20 0 M220 136 q10 -4 20 0 t20 0 M300 144 q10 -4 20 0 t20 0" />
                </svg>
              </view>
              <view class="ex-info">
                <text class="ex-title">深圳出发 · 香港一日游</text>
                <text class="ex-meta">轻松漫步 · 当天往返 · 高铁到西九龙约 15 分钟</text>
              </view>
              <view class="ex-steps">
                <view v-for="(st, i) in DEMO_STEPS" :key="st.place" class="ex-step">
                  <view class="ex-rail"><text class="ex-dot">{{ i + 1 }}</text><view v-if="i < DEMO_STEPS.length - 1" class="ex-line" /></view>
                  <view class="ex-step-body">
                    <text class="ex-when">{{ st.when }}</text>
                    <text class="ex-place">{{ st.place }}</text>
                    <text class="ex-what">{{ st.what }}</text>
                  </view>
                </view>
              </view>
              <view class="ex-feats">
                <view v-for="f in DEMO_FEATS" :key="f.label" class="ex-feat"><Icon :name="f.icon" :size="14" color="var(--muted)" /><text>{{ f.label }}</text></view>
              </view>
              <view class="ex-btns">
                <button class="btn" @click="planLikeDemo">按我的需求规划</button>
                <button class="btn ghost" @click="demoOpen = true">查看完整行程</button>
              </view>
            </view>
            <view class="ex-map">
              <!-- 形状用 SVG；文字用绝对定位叠在上面（uni-app 会把 <text> 换成自己的组件，SVG 里写不了字） -->
              <view class="map-box" aria-label="示例路线图：西九文化区、尖沙咀、天星小轮到中环">
                <svg viewBox="0 0 320 300" aria-hidden="true">
                  <rect class="land" width="320" height="300" />
                  <path class="water" d="M0 150 C70 132 140 160 200 150 C250 142 290 150 320 140 V214 C280 222 240 212 200 220 C140 232 70 206 0 222 Z" />
                  <path class="route" d="M64 118 C100 104 130 104 168 118 C190 128 200 140 206 176 C210 200 196 230 180 250" />
                  <circle class="pt" cx="64" cy="118" r="11" />
                  <circle class="pt" cx="168" cy="118" r="11" />
                  <circle class="pt" cx="206" cy="176" r="11" />
                  <circle class="end" cx="180" cy="250" r="6" />
                </svg>
                <view v-for="l in MAP_LABELS" :key="l.text" class="map-label" :class="l.cls" :style="{ left: `${(l.x / 320) * 100}%`, top: `${(l.y / 300) * 100}%` }">{{ l.text }}</view>
              </view>
              <text class="ex-note">示例内容，实际方案将按你的需求生成</text>
            </view>
          </view>
        </view>
        <!-- 星光紫背景下，页面底部加一条淡淡的城市天际线 -->
        <view v-if="theme.backdrop === 'aurora'" class="skyline" />
      </view>
    </view>

    <!-- 示例的完整行程 -->
    <view v-if="demoOpen" class="sheet-mask" @click="demoOpen = false">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">深圳出发 · 香港一日游</text>
          <text class="demo-tag">行程示例</text>
          <view class="sheet-x" @click="demoOpen = false"><Icon name="close" :size="16" color="var(--muted)" /></view>
        </view>
        <view class="full">
          <view v-for="row in DEMO_FULL" :key="row.time" class="full-row">
            <text class="full-time">{{ row.time }}</text>
            <view class="full-body">
              <text class="full-place">{{ row.place }}</text>
              <text class="full-what">{{ row.what }}</text>
            </view>
          </view>
        </view>
        <text class="ex-note">示例内容，没有写进你的行程。点"按我的需求规划"，星络会按你的出发地、日期和预算重新安排。</text>
        <button class="btn" @click="planLikeDemo">按我的需求规划</button>
      </view>
    </view>
    <CityPicker v-if="picking" :current="city" @close="picking = false" @pick="changeCity" />
  </Shell>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { getToken } from "../../api/http";
import type { AgendaEntry, ScheduleEvent, TodayAgenda, Trip, UploadView } from "../../api/types";
import BrandMark from "../../components/BrandMark.vue";
import CityPicker from "../../components/CityPicker.vue";
import Icon from "../../components/Icon.vue";
import FlightStatusCard from "../../components/FlightStatusCard.vue";
import ConvSidebar from "../../components/ConvSidebar.vue";
import VoiceButton from "../../components/VoiceButton.vue";
import Shell from "../../components/Shell.vue";
import { fileIcon, UPLOAD_EXTS } from "../../utils/attach";
import { dayLabel, hm, shortDate, ymd } from "../../utils/format";
import { loadDraft, saveDraft, setHandoff } from "../../utils/handoff";
import { flightNoIn } from "../../utils/priceCheck";
import { currentCity, setCurrentCity } from "../../utils/place";
import { useWide } from "../../utils/screen";
import { theme } from "../../utils/theme";
import { canGo, navigate } from "../../utils/today";

/**
 * 首页：以和星络对话为中心。电脑上左边是最近对话；中间从上到下是标题、最近一条日程提醒（没有不显示）、
 * 输入框（附件、机票比价、酒店比价、语音）、今天的航班动态（今天有航班才显示）、三个起步入口、
 * 还没完成的旅行计划（没有不显示）、一个固定的香港一日游示例（只展示，不写进用户数据）。
 * 输入框的字存成草稿，刷新或登录回来不丢。
 */
interface PendingFile {
  key: number;
  name: string;
  state: "uploading" | "ok" | "error";
  view?: UploadView;
  error?: string;
}

const MAX_FILES = 5;
const DRAFT_KEY = "home";
const wide = useWide();
const city = currentCity();
const question = ref(loadDraft(DRAFT_KEY));
const focused = ref(false);
const picking = ref(false);
const nickname = ref("");
const pending = ref<PendingFile[]>([]);
const events = ref<ScheduleEvent[]>([]);
const trips = ref<Trip[]>([]);
const agenda = ref<TodayAgenda | null>(null);
const demoOpen = ref(false);

const today = ymd(new Date());
let seq = 0;

watch(question, (v) => saveDraft(DRAFT_KEY, v));


const canSend = computed(() => !pending.value.some((a) => a.state === "uploading")
  && (!!question.value.trim() || pending.value.some((a) => a.state === "ok")));

/** 最近一条还没到时间的日程（全天的当天也算） */
const next = computed(() => {
  const now = Date.now();
  const e = events.value
    .filter((x) => (x.allDay ? ymd(new Date(x.startAt)) >= today : Date.parse(x.startAt) > now))
    .sort((a, b) => a.startAt.localeCompare(b.startAt))[0];
  if (!e) return null;
  const d = new Date(e.startAt);
  return { title: e.title, location: e.location, when: `${dayLabel(d)} ${e.allDay ? "全天" : hm(d)}` };
});

/** 还没结束的行程，最多三个；正在旅行的那个带上下一站 */
const plans = computed(() =>
  trips.value
    .filter((t) => t.status !== "FINISHED" && (!t.endDate || t.endDate >= today))
    .slice(0, 3)
    .map((t) => {
      const live = agenda.value?.trip?.id === t.id ? agenda.value.trip : null;
      const nextStop: AgendaEntry | undefined = live ? agenda.value!.entries.find((e) => e.kind === "TRIP" && !e.past) : undefined;
      const meta = live
        ? `进行中 · 第 ${live.dayNo}/${live.totalDays} 天${nextStop ? ` · 下一站 ${nextStop.time} ${nextStop.title}` : ""}`
        : `${dateRange(t)} · ${statusText(t)}`;
      return { id: t.id, title: t.title, meta, next: nextStop };
    }),
);

/** 今天安排里第一个还没过的航班（标题里带航班号），出发当天在首页直接看动态 */
const todayFlight = computed(() => {
  const e = agenda.value?.entries.find((x) => x.kind === "TRIP" && !x.past && flightNoIn(x.title));
  return e ? { no: flightNoIn(e.title)!, time: e.time, title: e.title } : null;
});

const STARTS = [
  { title: "周末不知道去哪", desc: "按出发地和预算推荐去处", icon: "compass", go: () => prefill(`从${city}出发，周末玩两天，预算1500元，推荐几个地方`) },
  { title: "已有目的地，帮我规划", desc: "路线、交通、住宿一次排好", icon: "route", go: () => prefill(`从${city}出发去`) },
  { title: "已有行程，帮我调整", desc: "改日期、换景点、调整节奏", icon: "refresh", go: adjustTrip },
];

const DEMO_STEPS = [
  { when: "上午", place: "西九文化区", what: "M+ 博物馆、海滨长廊" },
  { when: "中午", place: "尖沙咀", what: "钟楼、星光大道，顺路吃午饭" },
  { when: "下午", place: "天星小轮", what: "坐船过海到中环，看维港" },
];
/** 示例路线图上的字，坐标和 SVG 的 viewBox（320×300）一致，是字的中心点 */
const MAP_LABELS = [
  { text: "九龙", x: 240, y: 52, cls: "area" },
  { text: "香港岛", x: 270, y: 280, cls: "area" },
  { text: "维多利亚港", x: 64, y: 186, cls: "sea" },
  { text: "1", x: 64, y: 118, cls: "num" },
  { text: "2", x: 168, y: 118, cls: "num" },
  { text: "3", x: 206, y: 176, cls: "num" },
  { text: "西九文化区", x: 64, y: 94, cls: "" },
  { text: "尖沙咀", x: 168, y: 94, cls: "" },
  { text: "天星小轮", x: 254, y: 176, cls: "" },
  { text: "中环码头", x: 228, y: 250, cls: "" },
];
const DEMO_FEATS = [
  { label: "每日路线", icon: "route" },
  { label: "机票酒店比价", icon: "wallet" },
  { label: "保存与提醒", icon: "bell" },
];
const DEMO_FULL = [
  { time: "08:30", place: "深圳福田站", what: "高铁去香港西九龙，约 15 分钟，带好港澳通行证" },
  { time: "09:30", place: "西九文化区", what: "M+ 博物馆，沿海滨长廊走到尖沙咀，约 2 小时" },
  { time: "12:00", place: "尖沙咀", what: "午饭；钟楼、星光大道、1881 Heritage 顺路看" },
  { time: "15:00", place: "天星小轮", what: "尖沙咀码头到中环，约 10 分钟" },
  { time: "15:30", place: "中环", what: "半山扶梯、PMQ 元创方，体力好可以上太平山顶" },
  { time: "19:30", place: "香港西九龙站", what: "高铁回深圳，晚饭可以在西九龙站附近解决" },
];


onMounted(() => {
  if (!getToken()) return;
  api.me().then((u) => (nickname.value = u.nickname || u.username)).catch(() => {});
});

// 每次回到首页都刷新（可能刚在对话里加了日程、改了行程）；取不到就不显示对应区块
onShow(() => {
  if (!getToken()) return;
  api.events(today, ymd(new Date(Date.now() + 30 * 86400000))).then((list) => (events.value = list)).catch(() => {});
  api.trips().then((list) => (trips.value = list)).catch(() => {});
  api.today(city).then((a) => (agenda.value = a)).catch(() => {});
});

function dateRange(t: Trip): string {
  if (!t.startDate) return "日期待定";
  return `${shortDate(t.startDate)} – ${shortDate(t.endDate || t.startDate)}`;
}

function statusText(t: Trip): string {
  if (t.status === "DRAFT") return "未保存的方案";
  if (!t.startDate) return "日期待定";
  if (t.startDate <= today) return "进行中";
  const n = Math.round((Date.parse(t.startDate) - Date.parse(today)) / 86400000);
  return n === 1 ? "明天出发" : `${n} 天后出发`;
}

/** 选文件并逐个上传识别，最多 5 个；失败的留着显示原因。 */
function pickFiles() {
  const room = MAX_FILES - pending.value.length;
  if (room <= 0) {
    uni.showToast({ title: `一次最多带 ${MAX_FILES} 个文件`, icon: "none" });
    return;
  }
  uni.chooseFile({
    count: room,
    extension: UPLOAD_EXTS,
    success: (res) => {
      const files = (Array.isArray(res.tempFiles) ? res.tempFiles : [res.tempFiles]) as any[];
      for (const f of files.slice(0, room)) {
        const item = reactive<PendingFile>({ key: ++seq, name: f.name || "文件", state: "uploading" });
        pending.value.push(item);
        api.uploadFile({ path: f.path, name: f.name, file: f instanceof File ? f : undefined })
          .then((view) => {
            item.view = view;
            item.state = "ok";
          })
          .catch((e) => {
            item.state = "error";
            item.error = e instanceof Error ? e.message : String(e);
          });
      }
    },
  });
}

/** 把问题和附件交给对话页发出去；草稿等对话页真正发出后再清。 */
function ask() {
  if (!canSend.value) return;
  const ok = pending.value.filter((a) => a.state === "ok" && a.view);
  const text = question.value.trim() || (ok.length > 1 ? "帮我看看这几个文件" : "帮我看看这个文件");
  setHandoff({ text, files: ok.map((a) => ({ id: a.view!.id, name: a.view!.name })) });
  pending.value = [];
  question.value = "";
  uni.navigateTo({ url: "/pages/chat/chat?handoff=1" });
}

/** 语音转好的文字接在输入框后面，用户看过再发 */
function addVoice(text: string) {
  question.value = question.value.trim() ? `${question.value.trim()} ${text}` : text;
}

/** 把一句话放进输入框，让用户改完再发 */
function prefill(text: string) {
  question.value = text;
  focused.value = false;
  setTimeout(() => (focused.value = true), 50);
}

function adjust(title: string) {
  prefill(`调整一下「${title}」：`);
}

/** 有没结束的行程就带上第一个的名字；没有就去行程列表 */
function adjustTrip() {
  if (plans.value.length) adjust(plans.value[0].title);
  else uni.reLaunch({ url: "/pages/schedule/schedule?tab=trips" });
}

function planLikeDemo() {
  demoOpen.value = false;
  prefill(`从${city}出发去香港玩一天，节奏轻松，当天往返，帮我按我的情况规划`);
}

function go(url: string) {
  uni.navigateTo({ url });
}

function openTrip(id: number) {
  uni.navigateTo({ url: `/pages/trips/detail?id=${id}` });
}

function newChat() {
  uni.removeStorageSync("chat:last");
  uni.navigateTo({ url: "/pages/chat/chat?new=1" });
}

function openConv(id: string) {
  uni.navigateTo({ url: `/pages/chat/chat?id=${id}` });
}


function openSchedule() {
  uni.reLaunch({ url: "/pages/schedule/schedule" });
}

function changeCity(c: string) {
  picking.value = false;
  setCurrentCity(c);
  uni.reLaunch({ url: "/pages/home/home" });
}
</script>

<style scoped>
.home-wrap { min-height: 100%; }
.home-wrap.wide { display: flex; flex: 1; min-height: 0; height: 100%; }

.home { flex: 1; min-width: 0; max-width: 880px; margin: 0 auto; padding: 0 24px 56px; box-sizing: border-box; display: flex; flex-direction: column; gap: 20px; }
/* 电脑：整块右侧区域滚动（滚动条贴窗口右边），内容列随窗口变宽，最宽 1120px */
.home-wrap.wide .home { max-width: none; margin: 0; overflow-y: auto; padding: 64px 48px 56px; }
.home-wrap.wide .home > view:not(.next):not(.skyline) { width: 100%; max-width: 1120px; margin-left: auto; margin-right: auto; box-sizing: border-box; }
.home-wrap.wide .home > .skyline { max-width: none; margin: 8px -48px -56px; width: auto; }

/* 标题 */
.head { display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center; }
.eyebrow { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--muted); }
.title { font-size: 34px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.2; text-wrap: balance; }
.subtitle { font-size: 16px; color: var(--muted); line-height: 1.5; max-width: 34em; text-wrap: balance; }

.next { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); font-size: 14px; cursor: pointer; align-self: center; max-width: 100%; box-sizing: border-box; }
.next-when { color: var(--accent); font-weight: 600; white-space: nowrap; }
.next-title { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.next-loc { color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }

/* 输入框 */
.composer { background: var(--surface); border: 1px solid var(--line); border-radius: 24px; padding: 16px 14px 12px 20px; display: flex; flex-direction: column; gap: 10px; box-shadow: var(--shadow); box-sizing: border-box; transition: border-color 0.15s; }
.composer.focus { border-color: var(--accent); }
.c-input { width: 100%; min-height: 72px; height: 72px; font-size: 16px; line-height: 24px; color: var(--ink); }
.c-tools { display: flex; align-items: center; gap: 2px; flex-wrap: wrap; margin-left: -8px; }
.c-entry { display: flex; align-items: center; gap: 5px; height: 32px; padding: 0 8px; border-radius: 8px; font-size: 14px; cursor: pointer; white-space: nowrap; }
.c-entry:hover { background: var(--surface-2); }
.c-sep { width: 1px; height: 14px; background: var(--line); }
.c-right { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.c-send { width: 36px; height: 36px; margin: 0; padding: 0; border-radius: 50%; background: var(--accent); border: none; display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.c-send::after { border: none; }
.c-send[disabled] { background: var(--muted) !important; opacity: 0.4; cursor: default; }
.att-list { display: flex; flex-wrap: wrap; gap: 6px; }
.att { display: flex; align-items: center; gap: 6px; max-width: 100%; padding: 4px 8px; border-radius: 8px; background: var(--surface-2); font-size: 12px; }
.att.error { background: var(--danger-soft); color: var(--danger); }
.att-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 200px; }
.att-state { color: var(--muted); white-space: nowrap; }
.att-x { cursor: pointer; display: flex; }

.sec-title { font-size: 13px; color: var(--muted); font-weight: 600; }
.today-flight { display: flex; flex-direction: column; gap: 0; padding: 14px 16px 6px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); }

/* 三个起步入口 */
.starts { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.start { display: flex; align-items: center; gap: 12px; padding: 14px 14px 14px 16px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); cursor: pointer; transition: border-color 0.15s; }
.start:hover { border-color: var(--accent); }
.start-ic { width: 36px; height: 36px; border-radius: 10px; background: var(--accent-soft); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.start-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.start-title { font-size: 15px; font-weight: 600; }
.start-desc { font-size: 13px; color: var(--muted); }

.plans { display: flex; flex-direction: column; gap: 8px; }
.plan-list { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.plan { display: flex; align-items: center; gap: 16px; padding: 12px 18px; border-bottom: 1px solid var(--line); cursor: pointer; }
.plan:last-child { border-bottom: none; }
.plan:hover { background: var(--surface-2); }
.plan-body { flex: 1; min-width: 0; }
.plan-title { font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plan-meta { font-size: 13px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plan-acts { display: flex; gap: 16px; flex-shrink: 0; }
.plan-act { font-size: 14px; color: var(--accent); cursor: pointer; white-space: nowrap; }
.plan-act.strong { font-weight: 600; }

/* 示例 */
.demo { display: flex; flex-direction: column; gap: 12px; margin-top: 12px; }
.demo-head { display: flex; align-items: center; gap: 10px; }
.demo-title { font-size: 18px; font-weight: 700; }
.demo-tag { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); border: 1px solid var(--line); white-space: nowrap; }
.ex { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr); background: var(--surface); border: 1px solid var(--line); border-radius: 20px; overflow: hidden; }
.ex-main { display: flex; flex-direction: column; gap: 16px; padding: 0 0 20px; }
.ex-photo { height: 150px; overflow: hidden; }
.ex-photo svg { width: 100%; height: 100%; display: block; }
.sky { fill: #cfe3f7; }
.sun { fill: #ffd27a; }
.hill { fill: #8fb59a; }
.towers rect, .towers polygon { fill: #5c6f86; }
.sea { fill: #3d79b8; }
.ferry { fill: #2f6a3a; }
.wave { fill: none; stroke: #9cc3ea; stroke-width: 2; stroke-linecap: round; }
.ex-info, .ex-steps, .ex-feats, .ex-btns { padding: 0 20px; }
.ex-info { display: flex; flex-direction: column; gap: 4px; }
.ex-title { font-size: 20px; font-weight: 700; }
.ex-meta { font-size: 13px; color: var(--muted); }
.ex-steps { display: flex; flex-direction: column; }
.ex-step { display: flex; gap: 12px; }
.ex-rail { display: flex; flex-direction: column; align-items: center; width: 22px; }
.ex-dot { width: 22px; height: 22px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 12px; font-weight: 600; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.ex-line { flex: 1; width: 2px; background: var(--line); margin: 2px 0; min-height: 14px; }
.ex-step-body { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 8px; padding-bottom: 12px; }
.ex-when { font-size: 12px; color: var(--muted); width: 28px; }
.ex-place { font-size: 15px; font-weight: 600; }
.ex-what { font-size: 13px; color: var(--muted); flex-basis: 100%; padding-left: 36px; }
.ex-feats { display: flex; flex-wrap: wrap; gap: 6px 18px; padding-top: 12px; padding-bottom: 0; border-top: 1px solid var(--line); margin: 0 20px; padding-left: 0; padding-right: 0; }
.ex-feat { display: flex; align-items: center; gap: 5px; font-size: 13px; color: var(--muted); }
.ex-btns { display: flex; gap: 10px; flex-wrap: wrap; }
.ex-btns .btn { margin: 0; }
.ex-map { display: flex; flex-direction: column; justify-content: center; gap: 8px; padding: 16px; background: var(--surface-2); border-left: 1px solid var(--line); }
.map-box { position: relative; width: 100%; max-width: 360px; align-self: center; aspect-ratio: 320 / 300; border-radius: 12px; overflow: hidden; }
.map-box svg { width: 100%; height: 100%; display: block; }
.land { fill: var(--surface); }
.water { fill: var(--accent-soft); }
.route { fill: none; stroke: var(--accent); stroke-width: 3; stroke-dasharray: 6 6; stroke-linecap: round; }
.pt { fill: var(--accent); stroke: var(--surface); stroke-width: 3; }
.end { fill: var(--surface); stroke: var(--accent); stroke-width: 3; }
.map-label { position: absolute; transform: translate(-50%, -50%); font-size: 12px; font-weight: 600; color: var(--ink); white-space: nowrap; pointer-events: none; }
.map-label.area { color: var(--muted); font-size: 13px; }
.map-label.sea { color: var(--accent); font-weight: 400; }
.map-label.num { color: #fff; font-weight: 700; }
.ex-note { font-size: 12px; color: var(--muted); }
.skyline { height: 110px; margin: 8px -24px -56px; flex-shrink: 0; opacity: 0.16; background: var(--accent); -webkit-mask: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 800 120' preserveAspectRatio='xMidYMax slice'><path d='M0 120V96h40V80h18v16h30V70h22v26h26V58h16v38h34V84h28V64h20v32h40V40l8-10 8 10v56h24V76h26v20h30V60h20v36h40V30l6-28 6 28v66h22V72h28v24h36V86h24V66h18v30h44V78h22v18h40V90h30v30z' fill='black'/></svg>") center bottom / auto 100% repeat-x; mask: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 800 120' preserveAspectRatio='xMidYMax slice'><path d='M0 120V96h40V80h18v16h30V70h22v26h26V58h16v38h34V84h28V64h20v32h40V40l8-10 8 10v56h24V76h26v20h30V60h20v36h40V30l6-28 6 28v66h22V72h28v24h36V86h24V66h18v30h44V78h22v18h40V90h30v30z' fill='black'/></svg>") center bottom / auto 100% repeat-x; }

/* 示例完整行程 */
.sheet-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.35); z-index: 1001; display: flex; align-items: center; justify-content: center; padding: 16px; box-sizing: border-box; }
.sheet { width: 520px; max-width: 100%; max-height: 85vh; overflow-y: auto; background: var(--surface); border-radius: 20px; padding: 20px; box-sizing: border-box; display: flex; flex-direction: column; gap: 14px; }
.sheet .btn { margin: 0; }
.sheet-head { display: flex; align-items: center; gap: 10px; }
.sheet-title { font-size: 18px; font-weight: 700; }
.sheet-x { margin-left: auto; cursor: pointer; display: flex; padding: 4px; }
.full { display: flex; flex-direction: column; }
.full-row { display: flex; gap: 14px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.full-row:last-child { border-bottom: none; }
.full-time { width: 44px; font-size: 14px; font-weight: 600; color: var(--accent); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.full-body { display: flex; flex-direction: column; gap: 2px; }
.full-place { font-size: 15px; font-weight: 600; }
.full-what { font-size: 13px; color: var(--muted); }

/* 手机 */
.m-top { display: flex; align-items: center; justify-content: flex-end; }
.m-city { display: flex; align-items: center; gap: 4px; font-size: 14px; color: var(--muted); padding: 4px 0; }
@media (max-width: 1023px) {
  .home { padding: 12px 16px 32px; gap: 16px; }
  .head { align-items: flex-start; text-align: left; gap: 8px; }
  .title { font-size: 24px; }
  .subtitle { font-size: 14px; }
  .next { align-self: stretch; border-radius: 10px; }
  .composer { border-radius: 18px; padding: 12px 12px 10px 14px; }
  .c-entry { padding: 0 6px; font-size: 13px; }
  .starts { grid-template-columns: 1fr; gap: 8px; }
  .start { padding: 12px 14px; }
  .plan { flex-direction: column; align-items: stretch; gap: 8px; padding: 12px 14px; }
  .plan-acts { justify-content: flex-end; }
  .ex { grid-template-columns: 1fr; }
  .ex-map { border-left: none; border-top: 1px solid var(--line); }
  .sheet-mask { align-items: flex-end; padding: 0; }
  .sheet { border-radius: 20px 20px 0 0; width: 100%; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
}
</style>
