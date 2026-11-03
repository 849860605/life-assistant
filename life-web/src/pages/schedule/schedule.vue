<template>
  <Shell active="schedule">
  <view class="container">
    <view class="top">
      <text class="muted">未来 60 天</text>
      <button class="btn small" @click="showForm = !showForm">{{ showForm ? "收起" : "新建日程" }}</button>
    </view>

    <view v-if="showForm" class="card form">
      <input id="event-title" v-model="form.title" class="input" placeholder="做什么，比如：交电费" />
      <view class="row">
        <picker mode="date" :value="form.date" @change="form.date = $event.detail.value">
          <view class="input picker">{{ form.date }}</view>
        </picker>
        <picker mode="time" :value="form.time" @change="form.time = $event.detail.value">
          <view class="input picker">{{ form.time }}</view>
        </picker>
      </view>
      <input id="event-location" v-model="form.location" class="input" placeholder="地点（可不填）" />
      <picker :range="remindLabels" :value="form.remindIndex" @change="form.remindIndex = Number($event.detail.value)">
        <view class="input picker">提醒：{{ remindLabels[form.remindIndex] }}</view>
      </picker>
      <button class="btn" :disabled="!form.title.trim() || saving" @click="create">保存</button>
    </view>

    <view v-if="loaded && !groups.length" class="empty">
      最近没有日程。<br />可以在对话里说"明天早上 8 点提醒我交电费"。
    </view>

    <view v-for="g in groups" :key="g.day">
      <view class="section-title">{{ g.label }}</view>
      <view class="card day">
        <view v-for="e in g.events" :key="e.id" class="event" @click="openDetail(e)">
          <text class="time">{{ e.allDay ? "全天" : hm(new Date(e.startAt)) }}</text>
          <view class="event-body">
            <view class="event-title">{{ e.title }}</view>
            <view v-if="e.location" class="muted small">{{ e.location }}</view>
          </view>
          <text v-if="e.source !== 'USER'" class="chip" :class="e.source === 'TRAVEL' ? 'travel' : ''">
            {{ SOURCE[e.source] || e.source }}
          </text>
          <text class="del" @click.stop="remove(e)">删除</text>
        </view>
      </view>
    </view>
  </view>

  <view v-if="detail" class="sheet-mask" @click.self="closeDetail">
    <view class="sheet">
      <view class="sheet-bar" />
      <view class="d-title">{{ detail.title }}</view>
      <view class="d-row"><Icon name="clock" :size="15" color="var(--muted)" /><text>{{ whenText(detail) }}</text></view>
      <view v-if="detail.location" class="d-row"><Icon name="pin" :size="15" color="var(--muted)" /><text>{{ detail.location }}</text></view>
      <view v-if="detailEntry && leaveText(detailEntry)" class="d-row"><Icon name="car" :size="15" color="var(--muted)" /><text>{{ leaveText(detailEntry) }}</text></view>
      <view v-if="detail.description" class="d-desc">{{ detail.description }}</view>
      <view v-if="detail.source !== 'USER'" class="d-row muted small">来自{{ SOURCE[detail.source] || detail.source }}</view>
      <view v-if="detail.location" class="d-acts">
        <button class="btn" @click="goNav">导航</button>
        <button class="btn ghost" @click="goTaxi">{{ detailEntry && canGo(detailEntry) ? taxiText(detailEntry) : "打车" }}</button>
      </view>
      <view class="muted small">叫车和付款在高德里确认，星络不会替你下单。</view>
      <view class="d-foot">
        <text class="del" @click="remove(detail)">删除这条日程</text>
        <text class="d-close" @click="closeDetail">关闭</text>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, reactive, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { AgendaEntry, ScheduleEvent } from "../../api/types";
import Icon from "../../components/Icon.vue";
import { dayLabel, hm, toOffsetIso, ymd } from "../../utils/format";
import { currentCity } from "../../utils/place";
import { canGo, leaveText, navigate, searchPlace, takePendingEvent, takeTaxi, taxiText } from "../../utils/today";

const SOURCE: Record<string, string> = { TRAVEL: "出游", FITNESS: "健身", AI: "助手" };
const REMINDS: { label: string; minutes: number | null }[] = [
  { label: "不提醒", minutes: null },
  { label: "准时", minutes: 0 },
  { label: "提前 15 分钟", minutes: 15 },
  { label: "提前 30 分钟", minutes: 30 },
  { label: "提前 1 小时", minutes: 60 },
  { label: "提前 1 天", minutes: 1440 },
];
const remindLabels = REMINDS.map((r) => r.label);

const events = ref<ScheduleEvent[]>([]);
const loaded = ref(false);
const showForm = ref(false);
const saving = ref(false);
const form = reactive({ title: "", date: ymd(new Date(Date.now() + 86400000)), time: "09:00", location: "", remindIndex: 3 });

/** 按本地日期分组，组内按时间排。 */
const groups = computed(() => {
  const map = new Map<string, ScheduleEvent[]>();
  for (const e of [...events.value].sort((a, b) => a.startAt.localeCompare(b.startAt))) {
    const day = ymd(new Date(e.startAt));
    if (!map.has(day)) map.set(day, []);
    map.get(day)!.push(e);
  }
  return [...map.entries()].map(([day, list]) => ({ day, label: dayLabel(new Date(day + "T00:00:00")), events: list }));
});

/** 正在看详情的日程；从首页点进来时 detailEntry 带着首页查好的坐标和路程 */
const detail = ref<ScheduleEvent | null>(null);
const detailEntry = ref<AgendaEntry | null>(null);
const city = currentCity();

onShow(load);

/** 读今天起 60 天内的日程；首页点了某一项进来的，读完打开它的详情。 */
async function load() {
  const today = new Date();
  try {
    events.value = await api.events(ymd(today), ymd(new Date(today.getTime() + 60 * 86400000)));
    const p = takePendingEvent();
    if (p) {
      detail.value = events.value.find((e) => e.id === p.id) || null;
      detailEntry.value = p.entry || null;
    }
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
}

/** 按表单新建日程，成功后收起表单并刷新。 */
async function create() {
  saving.value = true;
  try {
    await api.createEvent({
      title: form.title.trim(),
      startAt: toOffsetIso(form.date, form.time),
      location: form.location.trim() || undefined,
      remindMinutesBefore: REMINDS[form.remindIndex].minutes,
    });
    form.title = "";
    form.location = "";
    showForm.value = false;
    uni.showToast({ title: "已保存", icon: "success" });
    await load();
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}

function openDetail(e: ScheduleEvent) {
  detail.value = e;
  detailEntry.value = null;
}

function closeDetail() {
  detail.value = null;
  detailEntry.value = null;
}

/** 详情里的时间：日期、几点到几点（全天的写全天） */
function whenText(e: ScheduleEvent): string {
  const start = new Date(e.startAt);
  const day = dayLabel(new Date(ymd(start) + "T00:00:00"));
  if (e.allDay) return `${day} 全天`;
  return `${day} ${hm(start)}${e.endAt ? ` – ${hm(new Date(e.endAt))}` : ""}`;
}

/** 导航：首页查到了坐标就直接导航，否则在高德里搜这个地点 */
function goNav() {
  const e = detailEntry.value;
  if (e && canGo(e)) navigate(e);
  else if (detail.value?.location) searchPlace(detail.value.location, city);
}

function goTaxi() {
  const e = detailEntry.value;
  if (e && canGo(e)) takeTaxi(e);
  else if (detail.value?.location) searchPlace(detail.value.location, city);
}

/** 删除前确认，提醒会一起删掉。 */
function remove(e: ScheduleEvent) {
  uni.showModal({
    title: "删除日程",
    content: `删除"${e.title}"？相关提醒也会取消。`,
    confirmText: "删除",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await api.deleteEvent(e.id);
        closeDetail();
        await load();
      } catch (err) {
        showError(err);
      }
    },
  });
}
</script>

<style scoped>
.top { display: flex; align-items: center; justify-content: space-between; margin: 0 4px; }
.top .btn { margin: 0; }
.form { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }
.form .btn { margin: 0; }
.row { display: flex; gap: 10px; }
.row picker { flex: 1; }
.picker { display: flex; align-items: center; width: 100%; }
.day { padding: 4px 16px; }
.event {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}
.event:last-child { border-bottom: none; }
.time { width: 44px; flex-shrink: 0; font-variant-numeric: tabular-nums; color: var(--accent); font-weight: 600; }
.event-body { flex: 1; min-width: 0; }
.event-title { word-break: break-word; }
.small { font-size: 13px; }
.del { font-size: 13px; color: var(--muted); cursor: pointer; flex-shrink: 0; }
.del:hover { color: var(--danger); }
.event { cursor: pointer; }
.sheet-mask { position: fixed; inset: 0; z-index: 200; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; justify-content: center; }
.sheet { width: 100%; max-width: 520px; background: var(--surface); border-radius: 18px 18px 0 0; padding: 10px 18px calc(18px + env(safe-area-inset-bottom, 0px)); display: flex; flex-direction: column; gap: 10px; box-sizing: border-box; }
.sheet-bar { width: 36px; height: 4px; border-radius: 2px; background: var(--line); align-self: center; }
.d-title { font-size: 18px; font-weight: 800; }
.d-row { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.d-desc { font-size: 14px; color: var(--muted); white-space: pre-wrap; }
.d-acts { display: flex; gap: 10px; }
.d-acts .btn { flex: 1; margin: 0; }
.d-foot { display: flex; justify-content: space-between; padding-top: 6px; border-top: 1px solid var(--line); }
.d-close { font-size: 13px; color: var(--accent); cursor: pointer; }
@media (min-width: 1024px) { .sheet-mask { align-items: center; } .sheet { border-radius: 18px; } }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
