<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">总览</h1>
        <div class="page-sub">用户、对话、模型用量和平台调用的整体情况；日期按北京时间算</div>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="o" class="tiles">
      <div class="card tile">
        <div class="t-label">用户</div>
        <div class="t-value num">{{ num(o.users) }}</div>
        <div class="t-sub">启用 {{ o.activeUsers }} · 近 7 天活跃 {{ o.activeUsers7d }}</div>
      </div>
      <div class="card tile">
        <div class="t-label">今天新对话</div>
        <div class="t-value num">{{ num(o.conversationsToday) }}</div>
        <div class="t-sub">近 7 天 {{ num(o.conversations7d) }} · 今天提问 {{ num(o.messagesToday) }} 次</div>
      </div>
      <div class="card tile">
        <div class="t-label">今天模型费用</div>
        <div class="t-value num">{{ money(o.costToday) }}</div>
        <div class="t-sub">今天调用 {{ num(o.llmCallsToday) }} 次 · 近 7 天 {{ money(o.cost7d) }}</div>
      </div>
      <div class="card tile">
        <div class="t-label">平台调用成功率（7 天）</div>
        <div class="t-value num" :class="rateTone">{{ o.toolSuccessRate7d == null ? "—" : o.toolSuccessRate7d + "%" }}</div>
        <div class="t-sub">共 {{ num(o.toolCalls7d) }} 次 · 近 7 天 Token {{ num(o.tokens7d) }}</div>
      </div>
    </div>

    <div v-if="o" class="two">
      <div class="card">
        <div class="card-head">
          <span class="card-title">近 7 天</span>
          <el-segmented v-model="metric" :options="METRICS" size="small" />
        </div>
        <div class="chart">
          <div v-for="d in o.days" :key="d.date" class="bar-col">
            <div class="bar-val num">{{ metricText(d) }}</div>
            <div class="bar-track"><div class="bar" :style="{ height: barHeight(d) }" /></div>
            <div class="bar-date num">{{ d.date.slice(5) }}</div>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head">
          <span class="card-title">平台和工具调用（7 天）</span>
          <span class="muted small">失败多的排前面</span>
        </div>
        <el-table :data="calls" size="small" max-height="300" empty-text="近 7 天没有调用">
          <el-table-column label="名称" min-width="140">
            <template #default="{ row }">
              <span>{{ field(row, "tool_name", "toolName") }}</span>
              <el-tag size="small" class="kind" :type="field(row, 'kind') === 'PLATFORM' ? 'warning' : 'info'">{{ field(row, "kind") === "PLATFORM" ? "平台" : "工具" }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="次数" width="70" align="right"><template #default="{ row }"><span class="num">{{ field(row, "calls") }}</span></template></el-table-column>
          <el-table-column label="失败" width="70" align="right">
            <template #default="{ row }"><span class="num" :class="{ bad: Number(field(row, 'failures')) > 0 }">{{ field(row, "failures") }}</span></template>
          </el-table-column>
          <el-table-column label="P95 耗时" width="90" align="right">
            <template #default="{ row }"><span class="num">{{ seconds(field(row, "p95_ms", "p95Ms")) }}</span></template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <el-skeleton v-if="!o && loading" :rows="6" animated />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import { api, type CallStat, type DayStat, type Overview } from "../api";
import { money, num } from "../utils";

/** 总览：四个关键数字、近 7 天趋势（对话 / 调用 / 费用切换）、平台和工具调用统计 */
const METRICS = [
  { label: "对话", value: "conversations" },
  { label: "模型调用", value: "llmCalls" },
  { label: "费用", value: "cost" },
];
const o = ref<Overview | null>(null);
const calls = ref<CallStat[]>([]);
const loading = ref(false);
const metric = ref<"conversations" | "llmCalls" | "cost">("conversations");

const rateTone = computed(() => {
  const r = o.value?.toolSuccessRate7d;
  return r == null ? "" : r >= 95 ? "good" : r >= 80 ? "warn" : "bad";
});

async function load() {
  loading.value = true;
  try {
    const [ov, cs] = await Promise.all([api.overview(), api.callStats(7)]);
    o.value = ov;
    calls.value = [...cs].sort((a, b) => Number(field(b, "failures")) - Number(field(a, "failures")) || Number(field(b, "calls")) - Number(field(a, "calls")));
  } finally {
    loading.value = false;
  }
}

/** 后端汇总是 Map，列名可能是下划线也可能是驼峰，两种都认 */
function field(row: CallStat, ...keys: string[]) {
  for (const k of keys) if (row[k] != null) return row[k];
  return "";
}

function seconds(ms: unknown) {
  const n = Number(ms);
  return ms === "" || Number.isNaN(n) ? "—" : n >= 1000 ? `${(n / 1000).toFixed(1)}s` : `${n}ms`;
}

function value(d: DayStat) {
  return Number(d[metric.value]) || 0;
}

function metricText(d: DayStat) {
  return metric.value === "cost" ? money(d.cost) : num(value(d));
}

const maxValue = computed(() => Math.max(1, ...(o.value?.days || []).map(value)));
function barHeight(d: DayStat) {
  return `${Math.max(2, (value(d) / maxValue.value) * 100)}%`;
}

onMounted(load);
</script>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}
.t-label {
  font-size: 13px;
  color: var(--x-muted);
}
.t-value {
  font-size: 28px;
  font-weight: 800;
  margin: 6px 0 4px;
}
.t-value.good {
  color: #1f9d55;
}
.t-value.warn {
  color: #d98a00;
}
.t-value.bad,
.bad {
  color: #d64545;
}
.t-sub {
  font-size: 12px;
  color: var(--x-muted);
}
.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 14px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.card-title {
  font-weight: 700;
}
.small {
  font-size: 12px;
}
.chart {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 8px;
  height: 240px;
}
.bar-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.bar-val {
  font-size: 11px;
  color: var(--x-muted);
  white-space: nowrap;
}
.bar-track {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: repeating-linear-gradient(to top, transparent 0 47px, var(--x-line) 47px 48px);
}
.bar {
  width: 60%;
  max-width: 34px;
  border-radius: 6px 6px 2px 2px;
  background: linear-gradient(180deg, #6ba2ff, #2457e6);
}
.bar-date {
  font-size: 12px;
  color: var(--x-muted);
}
.kind {
  margin-left: 6px;
}
@media (max-width: 500px) {
  .two {
    grid-template-columns: 1fr;
  }
}
</style>
