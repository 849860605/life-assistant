<template>
  <view class="container">
    <view v-if="detail" class="head">
      <view class="title">{{ detail.trip.title }}</view>
      <view class="muted meta">
        {{ detail.trip.destination || "" }}
        <text v-if="detail.trip.startDate"> · {{ detail.trip.startDate }}</text>
        <text v-if="detail.trip.endDate && detail.trip.endDate !== detail.trip.startDate"> 至 {{ detail.trip.endDate }}</text>
      </view>
    </view>

    <view v-if="versions.length > 1" class="versions">
      <view
        v-for="v in versions"
        :key="v.version"
        class="version"
        :class="{ active: detail && v.version === detail.version.version }"
        @click="load(v.version)"
      >
        <text class="version-no">第 {{ v.version }} 版</text>
        <text class="version-note">{{ v.changeNote || (v.createdBy === "AI" ? "助手生成" : "手动修改") }}</text>
      </view>
    </view>

    <view v-if="events.length" class="card reminders">
      <view class="reminders-title">关联的提醒</view>
      <view v-for="e in events" :key="e.id" class="reminder">
        <text class="reminder-time">{{ eventTime(e.startAt) }}</text>
        <text>{{ e.title }}</text>
      </view>
    </view>

    <view v-if="detail" class="card body">
      <MarkdownView :text="detail.version.content || ''" />
    </view>

    <view v-if="detail" class="actions">
      <button class="btn danger small" @click="removeTrip">删除行程</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { ScheduleEvent, TripDetail, TripVersion } from "../../api/types";
import MarkdownView from "../../components/MarkdownView.vue";
import { hm } from "../../utils/format";

const tripId = ref(0);
const detail = ref<TripDetail | null>(null);
const versions = ref<TripVersion[]>([]);
const events = ref<ScheduleEvent[]>([]);

onLoad(async (query) => {
  tripId.value = Number(query?.id);
  await Promise.all([load(), loadVersions(), loadEvents()]);
});

async function loadEvents() {
  try {
    events.value = await api.tripEvents(tripId.value);
  } catch (e) {
    showError(e);
  }
}

/** 提醒时间显示成"10月31日 14:00"。 */
function eventTime(iso: string) {
  const d = new Date(iso);
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hm(d)}`;
}

/** 确认后删除行程，关联的提醒一起删，然后回到列表。 */
function removeTrip() {
  uni.showModal({
    title: "删除行程",
    content: events.value.length ? `行程和 ${events.value.length} 条关联提醒都会删掉。` : "删除这个行程？",
    confirmText: "删除",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await api.deleteTrip(tripId.value);
        uni.navigateBack();
      } catch (e) {
        showError(e);
      }
    },
  });
}

/** 读某个版本，不传就是当前版本。 */
async function load(version?: number) {
  try {
    detail.value = await api.trip(tripId.value, version);
    uni.setNavigationBarTitle({ title: detail.value.trip.title });
  } catch (e) {
    showError(e);
  }
}

async function loadVersions() {
  try {
    versions.value = await api.tripVersions(tripId.value);
  } catch (e) {
    showError(e);
  }
}
</script>

<style scoped>
.head { margin: 4px 4px 14px; }
.title { font-size: 20px; font-weight: 600; }
.meta { font-size: 14px; margin-top: 4px; }
.versions { display: flex; gap: 8px; overflow-x: auto; margin-bottom: 12px; padding-bottom: 2px; }
.version {
  flex-shrink: 0;
  max-width: 220px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  cursor: pointer;
}
.version.active { border-color: var(--accent); background: var(--accent-soft); }
.version-no { display: block; font-size: 13px; font-weight: 600; }
.version-note {
  display: block;
  font-size: 12px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.body { padding: 16px 18px; }
.reminders { margin-bottom: 12px; }
.reminders-title { font-size: 13px; color: var(--muted); margin-bottom: 6px; }
.reminder { display: flex; gap: 12px; padding: 4px 0; font-size: 14px; }
.reminder-time { color: var(--accent); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.actions { margin: 16px 0 8px; display: flex; justify-content: flex-end; }
.actions .btn { margin: 0; }
</style>
