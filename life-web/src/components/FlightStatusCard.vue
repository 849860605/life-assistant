<template>
  <view v-if="show" class="fs" :class="{ compact }" @click.stop>
    <!-- 还没查：一行说明 + 查看按钮（写明多少积分）；积分不够给购买入口 -->
    <view v-if="!result" class="fs-idle">
      <view class="fs-lead">
        <Icon name="plane" :size="14" color="var(--accent)" />
        <text class="fs-name">航班实时动态</text>
        <text class="fs-sub">{{ tooEarly ? `起飞前 ${WINDOW_DAYS} 天内可以查看值机柜台、登机口、延误` : "值机柜台、登机口、延误、行李转盘" }}</text>
      </view>
      <view v-if="!tooEarly" class="fs-act">
        <text v-if="error" class="fs-err">{{ error }}</text>
        <text v-if="noCredit" class="fs-btn primary" @click="toCredits">买积分</text>
        <template v-else>
          <text v-if="info.loaded" class="fs-hint">{{ info.member ? "会员不扣积分" : `查一次 ${info.price} 积分` }}</text>
          <text class="fs-btn" :class="{ busy: loading }" @click="query">{{ loading ? "查询中…" : "查看动态" }}</text>
        </template>
      </view>
    </view>

    <!-- 查到了 -->
    <view v-else class="fs-body">
      <view class="fs-top">
        <text class="fs-state" :class="stateKind">{{ s.state || "未知" }}</text>
        <text class="fs-route">{{ s.flightNo }}<text v-if="s.depCity && s.arrCity"> · {{ s.depCity }} → {{ s.arrCity }}</text></text>
        <text class="fs-refresh" @click="query">{{ loading ? "刷新中…" : "刷新" }}</text>
      </view>
      <view class="fs-times">
        <view class="fs-time">
          <text class="fs-k">出发 {{ terminal(s.depAirport, s.depTerminal) }}</text>
          <text class="fs-v" :class="{ late: isLate(s.planDep, s.actualDep || s.estimateDep) }">{{ hhmm(s.actualDep || s.estimateDep || s.planDep) }}</text>
          <text class="fs-plan">{{ timeNote(s.planDep, s.estimateDep, s.actualDep) }}</text>
        </view>
        <view class="fs-time">
          <text class="fs-k">到达 {{ terminal(s.arrAirport, s.arrTerminal) }}</text>
          <text class="fs-v" :class="{ late: isLate(s.planArr, s.actualArr || s.estimateArr) }">{{ hhmm(s.actualArr || s.estimateArr || s.planArr) }}</text>
          <text class="fs-plan">{{ timeNote(s.planArr, s.estimateArr, s.actualArr) }}</text>
        </view>
      </view>
      <view class="fs-grid">
        <view v-for="f in facts" :key="f.k" class="fs-fact">
          <text class="fs-k">{{ f.k }}</text>
          <text class="fs-fv" :class="{ none: !f.v }">{{ f.v || f.empty }}</text>
        </view>
      </view>
      <view v-if="s.delayReason" class="fs-note">延误原因：{{ s.delayReason }}</view>
      <view class="fs-foot">
        <text>{{ s.queriedAt ? `${hhmm(s.queriedAt)} 更新` : "" }}{{ s.onTimeRate ? ` · 近期准点率 ${s.onTimeRate}` : "" }}</text>
        <text>{{ result.cached ? "5 分钟内查过，这次不扣积分" : result.spent ? `这次用了 ${result.spent} 积分` : "" }}</text>
      </view>
      <text v-if="error" class="fs-err">{{ error }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { ApiError, getToken } from "../api/http";
import type { FlightStatus, FlightStatusResult } from "../api/types";
import { flightInfo, loadFlightInfo, refreshCredits } from "../utils/credits";
import { ymd } from "../utils/format";
import Icon from "./Icon.vue";

/**
 * 航班实时动态卡片（行程里的航班、首页出发当天用）：先显示"查看动态"，点了才查（扣航班动态的积分，老会员到期前不扣；5 分钟内重复看不扣）；
 * 查到后显示状态、计划/预计/实际时间、值机柜台、登机口、行李转盘。起飞前 3 天以外只提示，过了起飞日一天不显示。
 * 服务端没配飞常准时整个卡片不显示。
 */
const WINDOW_DAYS = 3;

const props = withDefaults(defineProps<{ flightNo: string; date: string; compact?: boolean }>(), { compact: false });

/** 能不能查、多少积分，各卡片共用一份 */
const info = flightInfo;
const noCredit = ref(false);
const loading = ref(false);
const error = ref("");
const result = ref<FlightStatusResult | null>(null);
const s = computed<FlightStatus>(() => result.value!.status);

const today = ymd(new Date());
const daysAhead = computed(() => Math.round((Date.parse(props.date) - Date.parse(today)) / 86400000));
const tooEarly = computed(() => daysAhead.value > WINDOW_DAYS);
const show = computed(() => !!getToken() && info.available && daysAhead.value >= -1);

const stateKind = computed(() => {
  const st = s.value.state || "";
  if (/取消|备降|返航/.test(st)) return "bad";
  if (/延误/.test(st) || isLate(s.value.planDep, s.value.actualDep || s.value.estimateDep)) return "warn";
  if (/到达|起飞|登机/.test(st)) return "ok";
  return "";
});

const facts = computed(() => [
  { k: "值机柜台", v: s.value.checkinCounters, empty: "待定" },
  { k: "登机口", v: s.value.boardGate, empty: "起飞前 1～2 小时公布" },
  { k: "登机时间", v: s.value.boardStart ? `${hhmm(s.value.boardStart)}${s.value.boardEnd ? ` - ${hhmm(s.value.boardEnd)}` : ""}` : null, empty: "待定" },
  { k: "截止值机", v: s.value.lastCheckin ? hhmm(s.value.lastCheckin) : null, empty: "待定" },
  { k: "行李转盘", v: s.value.baggage, empty: "落地前公布" },
  { k: "出机场", v: s.value.exitMinutes ? `落地后约 ${s.value.exitMinutes} 分钟` : null, empty: "—" },
]);

onMounted(() => {
  if (getToken()) loadFlightInfo();
});

async function query() {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  try {
    const r = await api.flightStatus(props.flightNo, props.date);
    result.value = r;
    if (r.spent) refreshCredits();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "没查到";
    noCredit.value = e instanceof ApiError && e.code === 402;
  } finally {
    loading.value = false;
  }
}

function toCredits() {
  uni.navigateTo({ url: "/pages/member/member" });
}

function hhmm(v?: string | null) {
  return v ? v.slice(11, 16) || v : "--:--";
}

function terminal(airport?: string | null, t?: string | null) {
  return [airport, t].filter(Boolean).join(" ");
}

function isLate(plan?: string | null, real?: string | null) {
  return !!plan && !!real && Date.parse(real.replace(" ", "T")) - Date.parse(plan.replace(" ", "T")) >= 15 * 60000;
}

/** 时间下面的小字：实际/预计和计划不一样时写出计划时间 */
function timeNote(plan?: string | null, est?: string | null, actual?: string | null) {
  if (actual) return `实际 · 计划 ${hhmm(plan)}`;
  if (est && est !== plan) return `预计 · 计划 ${hhmm(plan)}`;
  return "计划";
}
</script>


<style scoped>
.fs { margin-top: 10px; padding: 10px 12px; border-radius: 10px; background: var(--surface-2); font-size: 13px; display: flex; flex-direction: column; gap: 8px; }
.fs-idle { display: flex; align-items: center; justify-content: space-between; gap: 8px 12px; flex-wrap: wrap; }
.fs-lead { display: flex; align-items: center; gap: 6px; min-width: 0; flex-wrap: wrap; }
.fs-name { font-weight: 600; }
.fs-sub { color: var(--muted); }
.fs-act { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.fs-hint { color: var(--muted); font-size: 12px; }
.fs-btn { padding: 4px 12px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); color: var(--accent); cursor: pointer; white-space: nowrap; }
.fs-btn.primary { background: var(--accent); border-color: var(--accent); color: #fff; }
.fs-btn.busy { color: var(--muted); }
.fs-err { color: var(--danger); font-size: 12px; }
.fs-body { display: flex; flex-direction: column; gap: 10px; }
.fs-top { display: flex; align-items: center; gap: 8px; }
.fs-state { padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 600; background: var(--accent-soft); color: var(--accent); }
.fs-state.ok { background: var(--ok-soft); color: var(--ok); }
.fs-state.warn { background: var(--warn-soft); color: var(--warn); }
.fs-state.bad { background: var(--danger-soft); color: var(--danger); }
.fs-route { flex: 1; min-width: 0; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fs-refresh, .fs-link { color: var(--accent); cursor: pointer; white-space: nowrap; }
.fs-times { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.fs-time { display: flex; flex-direction: column; gap: 2px; }
.fs-k { font-size: 12px; color: var(--muted); }
.fs-v { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }
.fs-v.late { color: var(--warn); }
.fs-plan { font-size: 12px; color: var(--muted); }
.fs-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 12px; padding-top: 8px; border-top: 1px solid var(--line); }
.fs-fact { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.fs-fv { font-weight: 600; word-break: break-all; }
.fs-fv.none { font-weight: 400; color: var(--muted); font-size: 12px; }
.fs-note { color: var(--warn); }
.fs-foot { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--muted); }
.compact .fs-v { font-size: 18px; }
@media (max-width: 767px) {
  .fs-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
