<template>
  <view class="tv">
    <view class="tv-bar">
      <view class="filters">
        <text v-for="f in FILTERS" :key="f" class="filter" :class="{ on: filter === f }" @click="filter = f">{{ f }}<text v-if="countOf(f)" class="cnt">{{ countOf(f) }}</text></text>
      </view>
      <view class="tv-right">
        <view class="search">
          <Icon name="search" :size="14" color="var(--muted)" />
          <input id="trip-search" v-model="keyword" class="search-input" placeholder="搜索行程、目的地" />
        </view>
        <button class="btn small" @click="toChat">新建行程</button>
      </view>
    </view>

    <view v-if="!loaded" class="state">加载中…</view>
    <view v-else-if="error" class="state">
      <text>{{ error }}</text>
      <text class="link" @click="load">重试</text>
    </view>
    <view v-else-if="!trips.length" class="empty">
      <view class="empty-title">还没有旅行计划</view>
      <view class="muted">告诉星络想去哪、什么时候出发、预算多少，生成方案后保存为行程。</view>
      <button class="btn" @click="toChat">规划第一个行程</button>
    </view>
    <view v-else-if="!shown.length" class="state">{{ keyword ? `没有找到"${keyword}"相关的行程` : `没有${filter}的行程` }}</view>

    <view v-else class="list">
      <view v-for="r in shown" :key="r.id" class="row" @click="open(r.id)">
        <view class="row-main">
          <view v-if="renamingId === r.id" class="rename" @click.stop>
            <input :id="`trip-rename-${r.id}`" v-model="renameText" class="input" maxlength="60" :focus="true" @confirm="saveRename(r)" />
            <text class="link" @click="saveRename(r)">保存</text>
            <text class="link muted-link" @click="renamingId = 0">取消</text>
          </view>
          <view v-else class="row-title">{{ r.title }}</view>
          <view class="row-meta">{{ [r.destination, r.dates, r.people ? `${r.people} 人` : ""].filter(Boolean).join(" · ") }}</view>
        </view>
        <text class="status" :class="r.groupKey">{{ r.group }}</text>
        <view class="row-acts" @click.stop>
          <text class="link" @click="open(r.id)">{{ r.groupKey === "done" ? "查看" : "继续" }}</text>
          <text class="link muted-link" @click="startRename(r)">改名</text>
          <text class="link danger" @click="remove(r)">删除</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Icon from "./Icon.vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { Trip } from "../api/types";
import { shortDate, ymd } from "../utils/format";

/**
 * 行程列表（「我的安排」的行程标签，行程页和日程页共用）：按全部、方案（未保存）、即将出发、进行中、已完成筛选，
 * 搜索标题和目的地，改名、删除（要确认），点进行程详情。没有行程时显示空状态，不放示例数据。
 */
const FILTERS = ["全部", "未保存的方案", "即将出发", "进行中", "已完成"] as const;
type Filter = (typeof FILTERS)[number];

const trips = ref<Trip[]>([]);
const loaded = ref(false);
const error = ref("");
const filter = ref<Filter>("全部");
const keyword = ref("");
const renamingId = ref(0);
const renameText = ref("");
const today = ymd(new Date());

interface Row {
  id: number;
  title: string;
  destination: string;
  dates: string;
  people: number | null;
  group: Filter;
  groupKey: string;
  trip: Trip;
}

const rows = computed<Row[]>(() =>
  trips.value.map((t) => {
    const group: Filter = t.status === "DRAFT" ? "未保存的方案"
      : t.status === "FINISHED" || (t.endDate && t.endDate < today) ? "已完成"
      : t.startDate && t.startDate <= today ? "进行中" : "即将出发";
    const groupKey = { 未保存的方案: "draft", 已完成: "done", 进行中: "live", 即将出发: "soon", 全部: "" }[group];
    const dates = t.startDate ? `${shortDate(t.startDate)} – ${shortDate(t.endDate || t.startDate)}` : "日期待定";
    return { id: t.id, title: t.title, destination: t.destination || "", dates, people: t.people ?? null, group, groupKey, trip: t };
  }),
);

const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  return rows.value.filter((r) => (filter.value === "全部" || r.group === filter.value)
    && (!k || r.title.toLowerCase().includes(k) || r.destination.toLowerCase().includes(k)));
});

onMounted(load);
defineExpose({ load });

async function load() {
  try {
    trips.value = await api.trips();
    error.value = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "行程没加载出来";
  } finally {
    loaded.value = true;
  }
}

function countOf(f: Filter) {
  return f === "全部" ? rows.value.length : rows.value.filter((r) => r.group === f).length;
}

function open(id: number) {
  uni.navigateTo({ url: `/pages/trips/detail?id=${id}` });
}

function toChat() {
  uni.reLaunch({ url: "/pages/home/home" });
}

function startRename(r: Row) {
  renamingId.value = r.id;
  renameText.value = r.title;
}

async function saveRename(r: Row) {
  const title = renameText.value.trim();
  if (!title) {
    uni.showToast({ title: "名称不能为空", icon: "none" });
    return;
  }
  try {
    const t = r.trip;
    const updated = await api.updateTripInfo(r.id, { title, people: t.people ?? null, budget: t.budget != null ? String(t.budget) : null, budgetCurrency: t.budgetCurrency || "CNY" });
    trips.value = trips.value.map((x) => (x.id === r.id ? { ...x, title: updated.title } : x));
    renamingId.value = 0;
  } catch (e) {
    showError(e);
  }
}

function remove(r: Row) {
  uni.showModal({
    title: "删除行程",
    content: `删除「${r.title}」？行程的所有版本、费用、预订记录和关联提醒都会一起删掉。`,
    confirmText: "删除",
    confirmColor: "#C4372B",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await api.deleteTrip(r.id);
        uni.showToast({ title: "已删除", icon: "none" });
        await load();
      } catch (e) {
        showError(e);
      }
    },
  });
}
</script>

<style scoped>
.tv { display: flex; flex-direction: column; gap: 16px; }
.tv-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.filters { display: flex; gap: 4px; flex-wrap: wrap; }
.filter { padding: 6px 12px; border-radius: 8px; font-size: 14px; color: var(--muted); cursor: pointer; }
.filter.on { background: var(--surface); color: var(--ink); font-weight: 600; box-shadow: inset 0 0 0 1px var(--line); }
.cnt { margin-left: 4px; font-size: 12px; color: var(--muted); font-weight: 400; }
.tv-right { display: flex; align-items: center; gap: 10px; }
.tv-right .btn { margin: 0; }
.search { display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 10px; border-radius: 8px; background: var(--surface); border: 1px solid var(--line); width: 220px; box-sizing: border-box; }
.search-input { flex: 1; font-size: 13px; }
.state { text-align: center; padding: 40px 0; font-size: 14px; color: var(--muted); display: flex; flex-direction: column; gap: 8px; align-items: center; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; text-align: center; padding: 56px 16px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); }
.empty .btn { margin: 6px 0 0; }
.empty-title { font-size: 17px; font-weight: 600; }
.list { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.row { display: flex; align-items: center; gap: 16px; padding: 14px 20px; border-bottom: 1px solid var(--line); cursor: pointer; }
.row:last-child { border-bottom: none; }
.row:hover { background: var(--surface-2); }
.row-main { flex: 1; min-width: 0; }
.row-title { font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-meta { font-size: 13px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rename { display: flex; align-items: center; gap: 10px; }
.rename .input { flex: 1; height: 32px; }
.status { font-size: 12px; padding: 2px 8px; border-radius: 4px; background: var(--surface-2); color: var(--muted); white-space: nowrap; }
.status.soon { background: var(--accent-soft); color: var(--accent); }
.status.live { background: var(--ok-soft); color: var(--ok); }
.status.draft { background: var(--warn-soft); color: var(--warn); }
.row-acts { display: flex; gap: 16px; }
.link { color: var(--accent); cursor: pointer; font-size: 14px; white-space: nowrap; }
.muted-link { color: var(--muted); }
.link.danger { color: var(--danger); }
.muted { color: var(--muted); }
@media (max-width: 767px) {
  .tv-right { width: 100%; }
  .search { flex: 1; width: auto; }
  .row { flex-wrap: wrap; gap: 6px 12px; padding: 12px 14px; }
  .row-main { flex-basis: calc(100% - 90px); }
  .row-acts { flex-basis: 100%; justify-content: flex-end; }
}
</style>
