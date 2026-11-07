<template>
  <Shell active="home" :fill="wide">
    <view class="home-wrap" :class="{ wide }">
      <!-- 电脑：左边对话栏（参考千问，和对话页同一个），点开接着聊 -->
      <ConvSidebar v-if="wide" @open="(c) => openConv(c.id)" @new="newChat" />

      <view class="home">
        <view v-if="!wide" class="m-top">
          <view class="m-city" @click="picking = true"><Icon name="pin" :size="13" color="var(--muted)" /><text>{{ city }}</text></view>
        </view>

        <view class="hello">
          <BrandMark :size="wide ? 40 : 32" />
          <text class="hello-title">你好，我是星络</text>
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
            :placeholder="`向星络提问：规划旅行、安排日程、比价机票酒店、找${city}的美食…`"
            @focus="focused = true"
            @blur="focused = false"
            @confirm="ask"
          />
          <view class="c-tools">
            <view class="c-attach" title="添加附件" aria-label="添加附件" @click="pickFiles"><Icon name="plus" :size="18" color="var(--ink)" /></view>
            <view v-for="e in ENTRIES" :key="e.label" class="c-entry" @click="e.go()">
              <Icon :name="e.icon" :size="15" color="var(--ink)" /><text>{{ e.label }}</text>
            </view>
            <view class="c-right">
              <ModelPicker :wide="wide" :small="!wide" />
              <VoiceButton @text="addVoice" />
              <button class="c-send" :disabled="!canSend" aria-label="发送" @click="ask"><Icon name="send" :size="17" color="#fff" /></button>
            </view>
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
import ModelPicker from "../../components/ModelPicker.vue";
import ConvSidebar from "../../components/ConvSidebar.vue";
import VoiceButton from "../../components/VoiceButton.vue";
import Shell from "../../components/Shell.vue";
import { fileIcon, UPLOAD_EXTS } from "../../utils/attach";
import { dayLabel, hm, shortDate, ymd } from "../../utils/format";
import { loadDraft, saveDraft, setHandoff } from "../../utils/handoff";
import { currentCity, setCurrentCity } from "../../utils/place";
import { useWide } from "../../utils/screen";
import { canGo, navigate } from "../../utils/today";

/**
 * 首页：以和星络对话为中心（版式参考千问）。电脑上左边是最近对话；中间是"你好，我是星络"、
 * 最近一条日程提醒（没有就不显示）、输入框（可带附件，工具行里是四个轻入口）、还没完成的旅行计划（没有就不显示）。
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

const ENTRIES = [
  { label: "规划旅行", icon: "plane", go: () => prefill(`帮我规划去${city}的旅行，3 天 2 晚`) },
  { label: "机票比价", icon: "depart", go: () => uni.navigateTo({ url: "/pages/search/flight" }) },
  { label: "酒店比价", icon: "bed", go: () => uni.navigateTo({ url: "/pages/search/hotel" }) },
  { label: "添加提醒", icon: "bell", go: () => uni.reLaunch({ url: "/pages/schedule/schedule?new=1" }) },
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


/* 中间：问候、输入框、计划 */
.home { flex: 1; min-width: 0; max-width: 880px; margin: 0 auto; padding: 0 24px 48px; box-sizing: border-box; display: flex; flex-direction: column; gap: 20px; }
.home-wrap.wide .home { justify-content: center; overflow-y: auto; padding-top: 32px; }
.hello { display: flex; align-items: center; justify-content: center; gap: 14px; }
.hello-title { font-size: 30px; font-weight: 700; letter-spacing: -0.01em; line-height: 1.25; text-align: center; }

.next { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); font-size: 14px; cursor: pointer; align-self: center; max-width: 100%; box-sizing: border-box; }
.next-when { color: var(--accent); font-weight: 600; white-space: nowrap; }
.next-title { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.next-loc { color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }

.composer { background: var(--surface); border: 1px solid var(--line); border-radius: 24px; padding: 16px 16px 12px 20px; display: flex; flex-direction: column; gap: 10px; box-shadow: var(--shadow); box-sizing: border-box; transition: border-color 0.15s; }
.composer.focus { border-color: var(--accent); }
.c-input { width: 100%; min-height: 72px; height: 72px; font-size: 16px; line-height: 24px; color: var(--ink); }
.c-tools { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.c-attach { width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.c-entry { display: flex; align-items: center; gap: 5px; height: 32px; padding: 0 10px; border-radius: 8px; font-size: 14px; cursor: pointer; white-space: nowrap; }
.c-attach:hover, .c-entry:hover { background: var(--surface-2); }
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

.plans { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.sec-title { font-size: 13px; color: var(--muted); font-weight: 600; }
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

/* 手机 */
.m-top { display: flex; align-items: center; justify-content: flex-end; }
.m-brand { display: flex; align-items: center; gap: 8px; }
.m-brand-name { font-size: 17px; font-weight: 700; }
.m-city { display: flex; align-items: center; gap: 4px; font-size: 14px; color: var(--muted); padding: 4px 0; }
@media (max-width: 1023px) {
  .home { padding: 16px 16px 32px; gap: 16px; }
  .hello { justify-content: flex-start; padding-top: 16px; }
  .hello-title { font-size: 22px; text-align: left; }
  .next { align-self: stretch; border-radius: 10px; }
  .composer { border-radius: 18px; padding: 12px 12px 10px 14px; }
  .c-entry { padding: 0 8px; font-size: 13px; }
  .plan { flex-direction: column; align-items: stretch; gap: 8px; padding: 12px 14px; }
  .plan-acts { justify-content: flex-end; }
}
</style>
