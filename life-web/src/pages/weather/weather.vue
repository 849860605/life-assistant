<template>
  <Shell active="weather" :show-recent="false">
  <view class="page">
    <view class="head">
      <view>
        <view class="title">天气查询</view>
        <view class="muted">实时天气、未来预报和出行建议，带行程时看每天的天气和下雨天怎么调</view>
      </view>
    </view>

    <!-- 搜索 -->
    <view class="panel search">
      <view class="city-chip"><Icon name="pin" :size="14" color="var(--accent)" /><text class="strong">{{ board?.city || city }}</text></view>
      <input id="weather-city" v-model="keyword" class="s-input" placeholder="搜索城市，如：北京、上海、成都" @confirm="searchCity" />
      <button class="btn small" :disabled="loading" @click="searchCity">查询天气</button>
      <button class="btn ghost small" :disabled="locating" @click="useLocation">{{ locating ? "定位中…" : "定位当前城市" }}</button>
    </view>

    <view v-if="loading && !board" class="state">正在查{{ city }}的天气…</view>
    <view v-else-if="error" class="state err">{{ error }}</view>
    <view v-else-if="board" class="grid">
      <view class="main">
        <!-- 实况 -->
        <view class="hero">
          <view class="hero-top">
            <view>
              <view class="hero-city">{{ board.city }}<text v-if="board.adm && board.adm !== board.city" class="hero-adm">{{ board.adm }}</text></view>
              <view class="hero-time">{{ board.now?.obsTime ? "观测 " + clock(board.now.obsTime) : "" }} · {{ ago(board.queriedAt) }}查询 · {{ sourceName }}<text class="refresh" @click="load(true)">{{ loading ? "刷新中…" : "刷新" }}</text></view>
            </view>
          </view>
          <view class="hero-main">
            <text class="hero-icon">{{ icon(board.now?.text || today?.textDay) }}</text>
            <view>
              <view class="hero-temp">{{ board.now?.temp ?? "—" }}<text class="deg">°C</text></view>
              <view class="hero-text">{{ board.now?.text || today?.textDay }}</view>
              <view v-if="today" class="hero-range">{{ today.tempMin }}°C ～ {{ today.tempMax }}°C</view>
            </view>
          </view>
          <view class="facts">
            <view v-if="board.now?.feelsLike != null" class="fact"><text class="fk">体感温度</text><text class="fv">{{ board.now?.feelsLike != null ? board.now.feelsLike + "°C" : "—" }}</text></view>
            <view class="fact"><text class="fk">湿度</text><text class="fv">{{ board.now?.humidity != null ? board.now.humidity + "%" : "—" }}</text></view>
            <view class="fact"><text class="fk">{{ board.now?.windDir || "风力" }}</text><text class="fv">{{ board.now?.windScale ? board.now.windScale + "级" : "—" }}</text></view>
            <view v-if="board.air?.aqi != null" class="fact"><text class="fk">空气质量</text><text class="fv">{{ board.air?.aqi != null ? `${board.air.category || ""}（AQI ${board.air.aqi}）` : "—" }}</text></view>
            <view v-if="uvText(today?.uvIndex) !== '—'" class="fact"><text class="fk">紫外线</text><text class="fv">{{ uvText(today?.uvIndex) }}</text></view>
          </view>
        </view>

        <view v-for="w in board.warnings" :key="w.title" class="warning"><Icon name="bell" :size="14" color="#c2410c" /><text><text class="strong">{{ w.title }}</text> {{ w.text }}</text></view>

        <!-- 逐小时：数据源没有时整块不显示 -->
        <view v-if="board.hourly.length" class="panel">
          <view class="panel-title">逐小时预报</view>
          <scroll-view scroll-x class="hours">
            <view class="hours-inner">
              <view v-for="(h, i) in board.hourly" :key="h.time" class="hour" :class="{ on: i === 0 }">
                <text class="small muted">{{ i === 0 ? "现在" : clock(h.time) }}</text>
                <text class="h-icon">{{ icon(h.text, clock(h.time)) }}</text>
                <text class="strong">{{ h.temp }}°C</text>
                <text v-if="h.pop" class="small" :class="h.pop >= 50 ? 'rain-t' : 'muted'">{{ h.pop }}%</text>
              </view>
            </view>
          </scroll-view>
        </view>

        <!-- 多日 -->
        <view class="panel">
          <view class="panel-title">{{ board.daily.length }} 日天气预报<text v-if="board.daily.length < 7" class="muted small">（目前只能查到 {{ board.daily.length }} 天）</text></view>
          <view class="days" :style="{ gridTemplateColumns: `repeat(${Math.max(board.daily.length, 1)}, minmax(0, 1fr))` }">
            <view v-for="(d, i) in board.daily" :key="d.date" class="dcell" :class="{ on: i === 0, weekend: isWeekend(d.date) }">
              <text class="strong">{{ dayLabel(d.date, i) }}</text>
              <text class="small muted">{{ md(d.date) }}</text>
              <text class="d-icon">{{ icon(d.textDay) }}</text>
              <text class="small">{{ d.textDay }}</text>
              <text class="small strong">{{ d.tempMin }}～{{ d.tempMax }}°C</text>
            </view>
          </view>
        </view>
      </view>

      <view class="side">
        <!-- 行程天气 -->
        <view v-if="tripId" class="panel">
          <view class="ph"><text class="panel-title">行程天气</text><text v-if="tripTitle" class="muted small">{{ tripTitle }}</text></view>
          <view v-if="tripLoading" class="muted small">正在查行程每天的天气…</view>
          <view v-else-if="tripError" class="err small">{{ tripError }}</view>
          <template v-else-if="tripForecast">
            <view v-for="d in tripForecast.days" :key="d.day" class="trip-day">
              <text class="strong">D{{ d.day }}</text>
              <text class="small muted">{{ md(d.date) }}</text>
              <template v-if="d.forecast">
                <text>{{ icon(d.forecast.textDay) }} {{ d.forecast.textDay }}</text>
                <text class="small">{{ d.forecast.tempMin }}～{{ d.forecast.tempMax }}°C</text>
                <text v-if="d.rainy" class="tag rain">有雨</text>
              </template>
              <text v-else class="muted small">还不在预报范围内</text>
            </view>
            <view v-if="tripForecast.suggestion" class="suggest">
              <Icon name="sparkle" :size="14" color="var(--accent)" />
              <view class="suggest-body">
                <text>{{ tripForecast.suggestion.text }}</text>
                <button class="btn small" :disabled="!!swapSheet" @click="openSwap">查看调整方案</button>
              </view>
            </view>
            <view v-else-if="tripForecast.note" class="muted small">{{ tripForecast.note }}</view>
            <view class="muted small">"室内"按景点名称判断（博物馆、展馆、剧院、商场等），其余当户外。</view>
          </template>
        </view>

        <view class="panel">
          <view class="panel-title">出行建议（{{ board.city }}）</view>
          <view v-for="t in board.tips" :key="t.kind" class="tip">
            <text class="tip-k">{{ t.kind }}</text><text class="tip-v">{{ t.text }}</text>
          </view>
          <view class="muted small">按气温、温差、降水、紫外线和空气质量算出来，不是 AI 写的。</view>
        </view>

        <view v-if="board.air" class="panel">
          <view class="ph"><text class="panel-title">空气质量详情</text><text v-if="board.air?.aqi != null" class="aqi">{{ board.air.category }} AQI {{ board.air.aqi }}</text></view>
          <view class="air">
            <view v-for="p in airItems" :key="p.k" class="air-cell"><text class="small muted">{{ p.k }}</text><text class="strong">{{ p.v ?? "—" }}</text></view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-title">周边城市天气</view>
          <view v-for="c in board.nearby" :key="c.name" class="near" @click="pickCity(c.name)">
            <text class="strong near-name">{{ c.name }}</text><text>{{ icon(c.text) }} {{ c.text }}</text><text class="near-t">{{ c.tempMin }}～{{ c.tempMax }}°C</text>
          </view>
          <view v-if="!board.nearby.length" class="muted small">这次没查到周边城市。</view>
        </view>

      </view>
    </view>

    <!-- 对调预览 -->
    <view v-if="swapSheet" class="mask" @click="closeSwap">
      <view class="sheet" @click.stop>
        <view class="sh-head"><text class="sh-title">调整方案</text><text class="preview-tag">预览，还没保存</text><view class="x" @click="closeSwap"><Icon name="close" :size="16" color="var(--muted)" /></view></view>
        <view v-if="swapSheet.error" class="err">{{ swapSheet.error }}</view>
        <view v-else-if="!swapSheet.preview" class="muted small">正在按高德重算两天的路线…</view>
        <template v-else>
          <view class="small">{{ swapSheet.preview.note }}，各自用对方原来的时间段。</view>
          <view v-for="p in [swapSheet.preview.first, swapSheet.preview.second]" :key="p.day" class="sday">
            <view class="strong">第 {{ p.day }} 天</view>
            <view class="cmp">
              <view v-for="row in compareTotals(p.beforeTotals, p.afterTotals)" :key="row.label" class="cmp-row">
                <text class="cmp-k">{{ row.label }}</text><text class="muted">{{ row.before }}</text><text class="muted">→</text><text class="strong">{{ row.after }}</text><text class="cmp-d" :class="row.tone">{{ row.delta }}</text>
              </view>
            </view>
            <view v-for="it in p.after.items" :key="it.id" class="order" :class="{ moved: movedIds.has(it.id) }">
              <text class="order-time">{{ it.start }}</text><text>{{ it.name }}</text><text v-if="movedIds.has(it.id)" class="tag rain">换进来</text>
            </view>
          </view>
          <view v-for="im in swapSheet.preview.impacts" :key="im.id" class="small err">按高德路程，到「{{ im.name }}」会比原来晚约 {{ im.minutes }} 分钟<text v-if="im.fixed">（锁定或已预约）</text></view>
          <view class="acts">
            <button class="btn" :disabled="saving" @click="confirmSwap">{{ saving ? "保存中…" : "确认并更新行程" }}</button>
            <button class="btn ghost" :disabled="saving" @click="closeSwap">不用了</button>
          </view>
          <view class="muted small">保存后存成新版本，旧版本可以在行程页回看。</view>
        </template>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import Icon from "../../components/Icon.vue";
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { SwapPreview, TripForecast, WeatherBoard } from "../../api/types";
import { ago } from "../../utils/format";
import { cityOf, compareTotals, currentCity } from "../../utils/place";
import { locate } from "../../utils/location";

/**
 * 天气查询：城市搜索和定位、实况、逐小时、多日预报、出行建议（按规则算）、空气质量、周边城市。
 * 带 trip 参数时显示行程每天的天气，下雨天给出"户外和晴天的室内对调"的方案，预览后确认才保存。
 * 数据源不支持的部分（高德没有逐小时、空气质量）直接说明，不编数据。参数 ?city=&trip=
 */
const city = ref(currentCity());
const keyword = ref("");
const board = ref<WeatherBoard | null>(null);
/** 数据来源的短名字（"高德天气"、"和风天气"），说明里的括号部分不重复显示 */
const sourceName = computed(() => (board.value?.source || "").replace(/[（(].*$/, ""));
const loading = ref(false);
const error = ref("");
const locating = ref(false);
const tripId = ref(0);
const tripTitle = ref("");
const tripForecast = ref<TripForecast | null>(null);
const tripLoading = ref(false);
const tripError = ref("");
const swapSheet = ref<{ preview: SwapPreview | null; error: string } | null>(null);
const saving = ref(false);
let seq = 0;

const today = computed(() => board.value?.daily[0]);
const airItems = computed(() => {
  const a = board.value?.air;
  return a ? [{ k: "PM2.5", v: a.pm2p5 }, { k: "PM10", v: a.pm10 }, { k: "SO₂", v: a.so2 }, { k: "NO₂", v: a.no2 }, { k: "O₃", v: a.o3 }, { k: "CO", v: a.co }] : [];
});
const movedIds = computed(() => {
  const s = tripForecast.value?.suggestion;
  return new Set(s ? [s.outdoorId, s.indoorId] : []);
});

onLoad(async (q) => {
  if (q?.city) city.value = decodeURIComponent(q.city);
  if (q?.trip) {
    tripId.value = Number(q.trip) || 0;
    try {
      const d = await api.trip(tripId.value);
      tripTitle.value = d.trip.title;
      if (!q?.city) city.value = cityOf(d.trip.destination) || city.value;
    } catch (e) {
      showError(e);
    }
    loadTrip(false);
  }
  load(false);
});

async function load(refresh: boolean) {
  const mine = ++seq;
  loading.value = true;
  error.value = "";
  try {
    const b = await api.weatherBoard(city.value, refresh);
    if (mine === seq) board.value = b;
  } catch (e) {
    if (mine === seq) error.value = e instanceof Error ? e.message : String(e);
  } finally {
    if (mine === seq) loading.value = false;
  }
}

async function loadTrip(refresh: boolean) {
  if (!tripId.value) return;
  tripLoading.value = true;
  tripError.value = "";
  try {
    tripForecast.value = await api.tripWeather(tripId.value, refresh);
  } catch (e) {
    tripError.value = e instanceof Error ? e.message : String(e);
  } finally {
    tripLoading.value = false;
  }
}

function searchCity() {
  const q = keyword.value.trim();
  if (q) city.value = q;
  board.value = null;
  load(false);
}

function pickCity(name: string) {
  city.value = name;
  keyword.value = "";
  board.value = null;
  load(false);
}

/** 定位当前城市：第一次会弹浏览器授权，失败就提示原因 */
async function useLocation() {
  locating.value = true;
  try {
    const loc = await locate(true);
    if (loc.city) pickCity(loc.city);
  } catch (e) {
    showError(e);
  } finally {
    locating.value = false;
  }
}

async function openSwap() {
  const s = tripForecast.value?.suggestion;
  if (!s) return;
  swapSheet.value = { preview: null, error: "" };
  try {
    const p = await api.previewSwap(tripId.value, s.outdoorId, s.indoorId);
    if (swapSheet.value) swapSheet.value.preview = p;
  } catch (e) {
    if (swapSheet.value) swapSheet.value.error = e instanceof Error ? e.message : String(e);
  }
}

function closeSwap() {
  if (!saving.value) swapSheet.value = null;
}

async function confirmSwap() {
  const s = tripForecast.value?.suggestion;
  if (!s || !swapSheet.value?.preview) return;
  saving.value = true;
  try {
    const d = await api.saveSwap(tripId.value, s.outdoorId, s.indoorId, s.outdoorName, s.indoorName);
    swapSheet.value = null;
    const to = d.version.version;
    uni.showModal({
      title: `已更新行程，存成第 ${to} 版`,
      content: `「${s.outdoorName}」和「${s.indoorName}」已经对调。`,
      confirmText: "看前后对比",
      cancelText: "留在这里",
      success: (r) => {
        if (r.confirm) uni.navigateTo({ url: `/pages/plan/updated?id=${tripId.value}&from=${to - 1}&to=${to}` });
      },
    });
    loadTrip(false);
  } catch (e) {
    showError(e);
    swapSheet.value = null;
  } finally {
    saving.value = false;
  }
}

/** 天气图标：按天气文字挑一个，晚上的晴天用月亮 */
function icon(text?: string | null, clockText?: string) {
  const t = text || "";
  const night = clockText ? Number(clockText.slice(0, 2)) >= 19 || Number(clockText.slice(0, 2)) < 6 : false;
  if (/雷/.test(t)) return "⛈️";
  if (/雪/.test(t)) return "🌨️";
  if (/雨/.test(t)) return "🌧️";
  if (/雾|霾|沙|尘/.test(t)) return "🌫️";
  if (/阴/.test(t)) return "☁️";
  if (/云/.test(t)) return night ? "☁️" : "⛅";
  if (/晴/.test(t)) return night ? "🌙" : "☀️";
  return "🌤️";
}

function uvText(uv?: string | null) {
  const n = Number(uv);
  if (!uv || Number.isNaN(n)) return "—";
  return n >= 8 ? `很强（${n}）` : n >= 5 ? `较强（${n}）` : n >= 3 ? `中等（${n}）` : `弱（${n}）`;
}

/** "2026-11-20T14:00+08:00"、"2026-11-20 14:30:00" 取 14:00 */
function clock(t: string) {
  const m = t.match(/(\d{2}:\d{2})/);
  return m ? m[1] : t;
}

function md(d: string) {
  return `${Number(d.slice(5, 7))}/${Number(d.slice(8, 10))}`;
}

function isWeekend(d: string) {
  const w = new Date(d + "T00:00:00").getDay();
  return w === 0 || w === 6;
}

function dayLabel(d: string, i: number) {
  if (i === 0) return "今天";
  if (i === 1) return "明天";
  return "周" + "日一二三四五六"[new Date(d + "T00:00:00").getDay()];
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; display: flex; flex-direction: column; gap: 12px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; }
.title { font-size: 20px; font-weight: 800; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.strong { font-weight: 700; }
.err { color: var(--price); font-size: 13px; line-height: 1.5; }
.state { text-align: center; font-size: 13px; padding: 28px 0; }
.state.err { color: var(--price); }
.rain-t { color: #2563eb; font-weight: 700; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.panel-title { font-weight: 800; font-size: 15px; }
.ph { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }

.search { flex-direction: row; align-items: center; flex-wrap: wrap; gap: 8px; }
.city-chip { display: flex; align-items: center; gap: 4px; padding: 6px 12px; border-radius: 10px; background: var(--surface-2); }
.s-input { flex: 1; min-width: 160px; font-size: 14px; padding: 6px 12px; border-radius: 10px; background: var(--surface-2); height: 34px; box-sizing: border-box; }
.search .btn { margin: 0; }

.grid { display: flex; flex-direction: column; gap: 12px; }
.main, .side { display: flex; flex-direction: column; gap: 12px; min-width: 0; }

.hero { border-radius: 16px; padding: 18px 20px; background: linear-gradient(135deg, #4f8ff7, #8ec5ff); color: #fff; display: flex; flex-direction: column; gap: 14px; }
.hero-city { font-size: 24px; font-weight: 800; }
.hero-adm { font-size: 13px; font-weight: 500; margin-left: 8px; opacity: 0.85; }
.hero-time { font-size: 12px; opacity: 0.9; margin-top: 2px; }
.refresh { margin-left: 8px; text-decoration: underline; cursor: pointer; }
.hero-main { display: flex; align-items: center; gap: 16px; }
.hero-icon { font-size: 64px; line-height: 1; }
.hero-temp { font-size: 52px; font-weight: 800; line-height: 1; font-variant-numeric: tabular-nums; }
.deg { font-size: 22px; vertical-align: top; }
.hero-text { font-size: 16px; margin-top: 4px; }
.hero-range { font-size: 14px; opacity: 0.9; }
.facts { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; background: rgba(255, 255, 255, 0.92); color: var(--ink, #1e293b); border-radius: 12px; padding: 10px; }
.fact { display: flex; flex-direction: column; gap: 2px; align-items: center; text-align: center; min-width: 0; }
.fk { font-size: 11px; color: #64748b; }
.fv { font-size: 14px; font-weight: 800; }
.warning { display: flex; gap: 6px; align-items: flex-start; padding: 10px 12px; border-radius: 12px; background: #fff4ea; color: #9a3412; font-size: 13px; line-height: 1.5; }

.hours { white-space: nowrap; }
.hours-inner { display: inline-flex; gap: 6px; }
.hour { display: inline-flex; flex-direction: column; align-items: center; gap: 4px; min-width: 62px; padding: 8px 4px; border-radius: 10px; }
.hour.on { background: var(--accent-soft); }
.h-icon { font-size: 22px; }
.days { display: grid; gap: 6px; }
.dcell { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 10px 2px; border-radius: 10px; text-align: center; min-width: 0; }
.dcell.on { background: var(--accent-soft); }
.dcell.weekend .strong:first-child { color: #dc2626; }
.d-icon { font-size: 24px; }

.trip-day { display: grid; grid-template-columns: 30px 40px minmax(0, 1fr) auto auto; gap: 6px; align-items: center; font-size: 13px; padding: 4px 0; border-bottom: 1px solid var(--line); }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; }
.tag.rain { background: #dbeafe; color: #1d4ed8; }
.suggest { display: flex; gap: 6px; align-items: flex-start; padding: 10px; border-radius: 10px; background: var(--accent-soft); font-size: 13px; line-height: 1.5; }
.suggest-body { display: flex; flex-direction: column; gap: 8px; }
.suggest .btn { margin: 0; align-self: flex-start; }
.tip { display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 8px; font-size: 13px; line-height: 1.5; padding: 6px 8px; border-radius: 8px; background: var(--surface-2); }
.tip-k { font-weight: 800; color: var(--accent); }
.aqi { font-size: 12px; font-weight: 700; padding: 2px 8px; border-radius: 6px; background: var(--ok-soft); color: var(--ok); }
.air { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 6px; text-align: center; }
.air-cell { display: flex; flex-direction: column; gap: 2px; }
.near { display: grid; grid-template-columns: 60px minmax(0, 1fr) auto; gap: 8px; font-size: 13px; padding: 6px 0; border-bottom: 1px solid var(--line); cursor: pointer; }
.near-t { font-variant-numeric: tabular-nums; }

.mask { position: fixed; inset: 0; z-index: 50; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; justify-content: center; }
.sheet { width: 100%; max-width: 560px; max-height: 88vh; overflow-y: auto; background: var(--surface); border-radius: 18px 18px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom, 0px)); box-sizing: border-box; display: flex; flex-direction: column; gap: 12px; }
.sh-head { display: flex; align-items: center; gap: 8px; }
.sh-title { font-weight: 800; font-size: 16px; }
.preview-tag { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--price); }
.x { margin-left: auto; cursor: pointer; padding: 4px; }
.sday { display: flex; flex-direction: column; gap: 6px; padding: 10px; border-radius: 10px; border: 1px solid var(--line); }
.cmp { display: flex; flex-direction: column; gap: 4px; }
.cmp-row { display: grid; grid-template-columns: 60px auto 12px auto 1fr; gap: 6px; font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.cmp-d { text-align: right; font-weight: 700; }
.cmp-d.good { color: var(--ok); }
.cmp-d.bad { color: var(--price); }
.order { display: flex; gap: 8px; font-size: 13px; align-items: baseline; }
.order.moved { font-weight: 700; }
.order-time { width: 40px; color: var(--muted); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.acts { display: grid; grid-template-columns: 2fr 1fr; gap: 8px; }
.acts .btn { margin: 0; }

@media (max-width: 480px) {
  .facts { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .air { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (min-width: 1024px) {
  .page { max-width: 1400px; padding: 20px 24px; }
  .grid { display: grid; grid-template-columns: minmax(0, 1fr) 400px; align-items: start; gap: 16px; }
  .side { position: sticky; top: 20px; }
  .mask { align-items: center; }
  .sheet { border-radius: 18px; }
}
</style>
