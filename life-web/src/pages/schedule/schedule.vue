<template>
  <Shell active="">
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
        <view v-for="e in g.events" :key="e.id" class="event">
          <text class="time">{{ e.allDay ? "全天" : hm(new Date(e.startAt)) }}</text>
          <view class="event-body">
            <view class="event-title">{{ e.title }}</view>
            <view v-if="e.location" class="muted small">{{ e.location }}</view>
          </view>
          <text v-if="e.source !== 'USER'" class="chip" :class="e.source === 'TRAVEL' ? 'travel' : ''">
            {{ SOURCE[e.source] || e.source }}
          </text>
          <text class="del" @click="remove(e)">删除</text>
        </view>
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
import type { ScheduleEvent } from "../../api/types";
import { dayLabel, hm, toOffsetIso, ymd } from "../../utils/format";

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

onShow(load);

/** 读今天起 60 天内的日程。 */
async function load() {
  const today = new Date();
  try {
    events.value = await api.events(ymd(today), ymd(new Date(today.getTime() + 60 * 86400000)));
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
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
