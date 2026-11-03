<template>
  <view class="today">
    <view class="t-head" @click="openSchedule()">
      <view class="t-title"><Icon name="calendar" :size="16" color="var(--accent)" /><text>今日安排</text><text v-if="upcoming.length" class="t-count">{{ upcoming.length }} 项</text></view>
      <Icon name="right" :size="14" color="var(--muted)" />
    </view>
    <view v-if="agenda?.trip" class="t-trip" @click="openTrip(agenda.trip.id)">
      <Icon name="trip" :size="13" color="var(--accent)" />
      <text>旅行中 · {{ agenda.trip.title }} · 第 {{ agenda.trip.dayNo }}/{{ agenda.trip.totalDays }} 天</text>
    </view>

    <view v-if="!agenda" class="t-empty muted">{{ error || "加载中…" }}</view>
    <template v-else-if="shown.length">
      <view v-for="e in shown" :key="e.kind + e.id" class="t-item" :class="{ past: e.past }" @click="open(e)">
        <view class="t-dot" :class="e.kind === 'TRIP' ? 'trip' : 'event'" />
        <view class="t-time">{{ e.time }}</view>
        <view class="t-body">
          <view class="t-name">{{ e.title }}</view>
          <view v-if="e.place && e.place !== e.title" class="t-place">{{ e.place }}</view>
          <view v-if="leaveText(e)" class="t-leave"><Icon name="car" :size="12" color="var(--muted)" /><text>{{ leaveText(e) }}</text></view>
          <view v-if="wide && canGo(e)" class="t-acts row" @click.stop>
            <text class="t-btn" @click="navigate(e)">导航</text>
            <text class="t-btn" @click="takeTaxi(e)">{{ taxiText(e) }}</text>
          </view>
        </view>
        <view v-if="!wide && canGo(e)" class="t-acts" @click.stop>
          <text class="t-btn solid" @click="go(e)">出发</text>
        </view>
        <Icon v-else-if="!wide || !canGo(e)" name="right" :size="14" color="var(--muted)" />
      </view>
      <view v-if="hiddenCount" class="t-more" @click="expanded = true">还有 {{ hiddenCount }} 项，展开</view>
      <view v-if="pastCount" class="t-more muted" @click="showPast = !showPast">{{ showPast ? "收起已结束的" : `${pastCount} 项已结束，点开看` }}</view>
    </template>
    <view v-else class="t-empty">
      <view class="muted">{{ agenda.entries.length ? "今天的安排都结束了" : "今天没有安排" }}</view>
      <view class="t-tip" @click="emit('ask', '明天早上 9 点提醒我开会')">说一句"明天早上 9 点提醒我开会"就能加上 ›</view>
    </view>
    <view v-if="agenda && !agenda.home && !agenda.trip && agenda.entries.some((e) => !e.past && e.lng != null)" class="t-hint muted" @click="goMe">
      在"我的"里设一下家的地址，就能算几点出发 ›
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import type { AgendaEntry, TodayAgenda } from "../api/types";
import Icon from "./Icon.vue";
import { canGo, leaveText, navigate, openEventLater, takeTaxi, taxiText } from "../utils/today";

/**
 * 首页的今日安排：今天的日程和旅行当天的安排，按时间排。点一项看详情（日程进日程页、行程项进行程），
 * 有地点的可以直接导航、打车（电脑上两个按钮，手机上点"出发"再选）。已结束的默认收起。
 */
const props = defineProps<{ agenda: TodayAgenda | null; error?: string; wide?: boolean }>();
const emit = defineEmits<{ (e: "ask", q: string): void }>();

/** 默认先列这么多项还没开始的，其余点"展开" */
const LIMIT = 4;
const showPast = ref(false);
const expanded = ref(false);
const upcoming = computed(() => (props.agenda?.entries || []).filter((e) => !e.past));
/** 还没开始的先列前几项，已结束的点开才显示 */
const shown = computed(() => {
  const list = showPast.value ? props.agenda?.entries || [] : upcoming.value;
  return expanded.value ? list : list.slice(0, LIMIT + (showPast.value ? pastCount.value : 0));
});
const pastCount = computed(() => (props.agenda?.entries || []).filter((e) => e.past).length);
const hiddenCount = computed(() => (expanded.value ? 0 : Math.max(0, upcoming.value.length - LIMIT)));

function open(e: AgendaEntry) {
  if (e.kind === "TRIP" && e.tripId) openTrip(e.tripId);
  else openSchedule(Number(e.id), e);
}

function openTrip(id: number) {
  uni.navigateTo({ url: `/pages/trips/detail?id=${id}` });
}

/** 日程页是底部标签页，不能带参数，先记下要打开的那一项 */
function openSchedule(id?: number, e?: AgendaEntry) {
  if (id) openEventLater(id, e);
  if (props.wide) uni.reLaunch({ url: "/pages/schedule/schedule" });
  else uni.switchTab({ url: "/pages/schedule/schedule" });
}

function goMe() {
  if (props.wide) uni.reLaunch({ url: "/pages/me/me" });
  else uni.switchTab({ url: "/pages/me/me" });
}

/** 手机上点"出发"：选导航还是打车 */
function go(e: AgendaEntry) {
  uni.showActionSheet({
    itemList: [`导航（高德${e.mode === "WALK" ? "步行" : ""}）`, `${taxiText(e)}（高德，叫车付款在高德里确认）`],
    success: (r) => (r.tapIndex === 0 ? navigate(e) : takeTaxi(e)),
  });
}
</script>

<style scoped>
.today { display: flex; flex-direction: column; gap: 10px; }
.t-head { display: flex; align-items: center; justify-content: space-between; cursor: pointer; }
.t-title { display: flex; align-items: center; gap: 6px; font-size: 15px; font-weight: 700; }
.t-count { font-size: 12px; font-weight: 400; color: var(--muted); }
.t-trip { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--accent); background: var(--accent-soft); padding: 6px 10px; border-radius: 8px; cursor: pointer; }
.t-item { display: flex; align-items: flex-start; gap: 10px; padding: 8px 0; border-top: 1px solid var(--line); cursor: pointer; }
.t-item:first-of-type { border-top: none; }
.t-item.past { opacity: 0.5; }
.t-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 7px; flex-shrink: 0; }
.t-dot.event { background: var(--accent); }
.t-dot.trip { background: var(--ok); }
.t-time { font-size: 15px; font-weight: 700; min-width: 44px; font-variant-numeric: tabular-nums; }
.t-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.t-name { font-size: 14px; font-weight: 600; }
.t-place { font-size: 12px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.t-leave { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--muted); }
.t-acts { display: flex; gap: 6px; flex-shrink: 0; }
.t-acts.row { margin-top: 4px; }
.t-btn { font-size: 12px; padding: 4px 10px; border-radius: 999px; border: 1px solid var(--accent); color: var(--accent); white-space: nowrap; cursor: pointer; }
.t-btn.solid { background: var(--accent-soft); border-color: transparent; font-weight: 600; }
.t-more { font-size: 12px; text-align: center; cursor: pointer; color: var(--accent); }
.t-more.muted { color: var(--muted); }
.t-empty { display: flex; flex-direction: column; gap: 6px; font-size: 13px; padding: 4px 0; }
.t-tip { font-size: 12px; color: var(--accent); cursor: pointer; }
.t-hint { font-size: 12px; cursor: pointer; }
</style>
