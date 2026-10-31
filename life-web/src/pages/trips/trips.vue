<template>
  <Shell active="trips">
  <view class="page">
    <view class="tabs">
      <text v-for="t in TABS" :key="t" class="tab" :class="{ active: tab === t }" @click="tab = t">{{ t }}</text>
    </view>

    <view class="list">
      <view v-for="t in shown" :key="t.key" class="trip" @click="open(t)">
        <image v-if="t.cover" class="cover" :src="t.cover" mode="aspectFill" />
        <view v-else class="cover blank"><Icon name="trip" :size="28" color="#fff" /></view>
        <view class="body">
          <view class="top">
            <text class="name">{{ t.title }}</text>
            <text class="state" :class="t.stateTone">{{ t.state }}</text>
          </view>
          <view class="meta">{{ t.dates }}</view>
          <view v-if="t.summary" class="meta">{{ t.summary }}</view>
          <view class="actions">
            <button class="btn ghost small" @click.stop="edit(t)">继续编辑</button>
            <button class="btn small" @click.stop="open(t)">查看详情</button>
          </view>
        </view>
      </view>
      <view v-if="loaded && !shown.length" class="empty">
        <Icon :name="tab === '收藏' ? 'bookmark' : 'trip'" :size="34" color="var(--muted)" />
        <view>{{ tab === "收藏" ? "还没有收藏的行程" : "这里还没有行程" }}</view>
        <view class="muted small">在首页说出想去哪，规划好的行程会出现在这里</view>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { Trip } from "../../api/types";
import Icon from "../../components/Icon.vue";
import { coverFor } from "../../demo/xian";

/** 我的行程（原型⑫）：进行中、收藏、历史；第一条是示例行程。 */
const TABS = ["进行中", "收藏", "历史"];

interface Row {
  key: string;
  id: number | "demo";
  title: string;
  dates: string;
  summary: string;
  state: string;
  stateTone: string;
  cover: string;
  group: string;
}

const tab = ref(TABS[0]);
const trips = ref<Trip[]>([]);
const loaded = ref(false);

const DEMO: Row = {
  key: "demo",
  id: "demo",
  title: "西安4天3晚（示例）",
  dates: "3月10日 – 3月13日",
  summary: "12 个景点 · 1 家酒店",
  state: "进行中",
  stateTone: "ok",
  cover: coverFor("西安"),
  group: "进行中",
};

const rows = computed<Row[]>(() => [
  DEMO,
  ...trips.value.map((t) => {
    const finished = t.status === "FINISHED" || (!!t.endDate && t.endDate < today());
    return {
      key: String(t.id),
      id: t.id,
      title: t.title,
      dates: t.startDate ? `${cn(t.startDate)} – ${cn(t.endDate || t.startDate)}` : "日期待定",
      summary: `${t.destination || ""}${t.currentVer > 1 ? ` · 第 ${t.currentVer} 版` : ""}`,
      state: finished ? "已完成" : t.status === "DRAFT" ? "草稿" : "进行中",
      stateTone: finished ? "" : t.status === "DRAFT" ? "draft" : "ok",
      cover: coverFor(t.destination || t.title),
      group: finished ? "历史" : "进行中",
    };
  }),
]);
const shown = computed(() => rows.value.filter((r) => r.group === tab.value));

onShow(async () => {
  try {
    trips.value = await api.trips();
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
});

function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function cn(date: string) {
  return `${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日`;
}

function open(t: Row) {
  uni.navigateTo({ url: `/pages/trips/detail?id=${t.id}` });
}

function edit(t: Row) {
  if (t.id === "demo") {
    uni.navigateTo({ url: "/pages/plan/edit" });
  } else {
    uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent(`我想调整行程「${t.title}」（tripId=${t.id}）`) });
  }
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 12px 16px 24px; box-sizing: border-box; }
.tabs { display: flex; gap: 24px; border-bottom: 1px solid var(--line); margin-bottom: 12px; }
.tab { padding: 8px 0; font-size: 15px; color: var(--muted); border-bottom: 2px solid transparent; margin-bottom: -1px; cursor: pointer; }
.tab.active { color: var(--accent); font-weight: 600; border-color: var(--accent); }
.list { display: flex; flex-direction: column; gap: 12px; }
.trip { display: flex; gap: 12px; background: var(--surface); border-radius: 16px; padding: 10px; border: 1px solid var(--line); cursor: pointer; }
.cover { width: 112px; height: 112px; border-radius: 12px; flex-shrink: 0; }
.blank { background: linear-gradient(135deg, #60a5fa, #2563eb); display: flex; align-items: center; justify-content: center; }
.body { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.top { display: flex; align-items: flex-start; gap: 8px; }
.name { flex: 1; font-weight: 700; font-size: 15px; }
.state { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); white-space: nowrap; }
.state.ok { background: var(--ok-soft); color: var(--ok); }
.state.draft { background: #fff4ea; color: #c2560c; }
.meta { font-size: 12px; color: var(--muted); margin-top: 3px; }
.actions { display: flex; gap: 8px; margin-top: auto; padding-top: 8px; }
.actions .btn { margin: 0; flex: 1; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 60px 0; color: var(--ink); }
.small { font-size: 12px; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
