<template>
  <Shell active="map" :show-recent="false">
  <view class="page">
    <view v-if="!tripId && !loading" class="empty">
      <Icon name="nav" :size="34" color="var(--muted)" />
      <view>还没有行程路线</view>
      <button class="btn small" @click="toChat">先在对话里生成一个行程</button>
    </view>
    <template v-else>
      <view class="top">
        <view v-if="wide" class="back" @click="goBack"><Icon name="back" :size="22" color="var(--ink)" /></view>
        <text class="title">{{ trip?.title || "行程" }} · 路线地图</text>
        <view class="views">
          <text class="view-tab" @click="toDetail">行程总览</text>
          <text class="view-tab on">路线地图</text>
          <text class="view-tab" @click="toFood">美食推荐</text>
        </view>
      </view>

      <scroll-view scroll-x class="days">
        <view class="days-inner">
          <view v-for="(d, i) in days" :key="d.date" class="day" :class="{ on: i === dayIndex }" :style="i === dayIndex ? { background: '#' + colorOf(i) } : {}" @click="pickDay(i)">
            <text class="day-no">D{{ i + 1 }}</text><text class="day-date">{{ shortDate(d.date) }}</text>
          </view>
        </view>
      </scroll-view>

      <view class="cols">
        <!-- 地图：手机上在最上面 -->
        <view id="map-col" class="col map-col">
          <view class="map-bar">
            <view class="layers">
              <text class="muted small">叠加</text>
              <text v-for="(d, i) in days" v-show="i !== dayIndex" :key="d.date" class="layer" :class="{ on: overlay.has(i) }" :style="{ '--c': '#' + colorOf(i) }" @click="toggleOverlay(i)">D{{ i + 1 }}</text>
            </view>
            <view v-if="preview?.changed" class="seg">
              <text class="seg-opt" :class="{ on: !showAfter }" @click="showAfter = false">优化前</text>
              <text class="seg-opt" :class="{ on: showAfter }" @click="showAfter = true">优化后</text>
            </view>
          </view>
          <RouteMap ref="mapRef" :stops="mapStops" :color="colorOf(dayIndex)" :lines="overlayLines" :active-id="activeId" :w="mapW" :h="mapH" @pick="onPick" />
          <view class="legend">
            <text class="lg"><text class="lg-line" :style="{ background: '#' + colorOf(dayIndex) }" />第 {{ dayIndex + 1 }} 天路线</text>
            <text v-for="i in overlayDays" :key="i" class="lg"><text class="lg-line thin" :style="{ background: '#' + colorOf(i) }" />第 {{ i + 1 }} 天</text>
            <text class="muted small">点地图上的编号，左边会定位到那一项；地图和路程来自高德</text>
          </view>
        </view>

        <!-- 当天安排 -->
        <view class="col list-col">
          <view class="panel">
            <view class="panel-title">D{{ dayIndex + 1 }} {{ day?.theme || "" }}</view>
            <view v-if="loading" class="muted small pad">加载中…</view>
            <view v-for="it in day?.items || []" :id="'it-' + it.id" :key="it.id" class="it" :class="{ on: it.id === activeId, nogeo: !hasGeo(it) }" @click="onSelect(it)">
              <view class="it-dot" :style="hasGeo(it) ? { background: '#' + colorOf(dayIndex) } : {}">{{ hasGeo(it) ? stopNo(it.id) : "" }}</view>
              <view class="it-body">
                <view class="it-time">{{ it.start }}<text v-if="it.end"> – {{ it.end }}</text></view>
                <view class="it-name">{{ it.name }}</view>
                <view class="it-tags">
                  <text v-if="it.locked" class="badge">已锁定</text>
                  <text v-if="booked.has(it.id)" class="badge ok">已预约</text>
                  <text v-if="!hasGeo(it) && placeTypes.has(it.type)" class="muted small">没有坐标</text>
                </view>
              </view>
              <image v-if="it.photo && !broken[it.id]" class="it-img" :src="it.photo" mode="aspectFill" @error="broken[it.id] = true" />
              <view v-if="legFrom(day, it.id)" class="it-leg">
                <LegRow class="it-leg-row" :leg="legFrom(day, it.id)!" :day="day" :stays="detail?.version.itinerary?.stays" :editable="true" :busy="modeBusy" @mode="setLegMode(legFrom(day, it.id)!, $event)" />
                <text class="nav-link" @click.stop="navigate(it, legFrom(day, it.id)!)">导航</text>
              </view>
            </view>
          </view>
        </view>

        <!-- 合计和优化 -->
        <view class="col side-col">
          <view class="panel">
            <view class="stats">
              <view class="stat"><text class="stat-k">路上</text><text class="stat-v">{{ travel.minutes ? duration(travel.minutes) : "—" }}</text></view>
              <view class="stat"><text class="stat-k">里程</text><text class="stat-v">{{ travel.km }} 公里</text></view>
              <view class="stat"><text class="stat-k">交通费</text><text class="stat-v">{{ fareText }}</text></view>
            </view>
            <view class="muted small">地铁公交是高德查的线路和每人票价，打车费是高德的估算；1 公里内按步行算。</view>
          </view>

          <view class="panel">
            <view class="panel-title">路线优化</view>
            <view class="goals">
              <text v-for="g in GOALS" :key="g.value" class="goal" :class="{ on: goal === g.value }" @click="setGoal(g.value)">{{ g.label }}</text>
            </view>
            <view class="muted small">{{ GOALS.find((g) => g.value === goal)?.hint }}。锁定、已预约、吃饭、酒店和交通安排不会动，傍晚以后的景点只在傍晚以后互换。</view>

            <button v-if="!preview" class="btn wide-btn" :disabled="previewing || !day" @click="runPreview">{{ previewing ? "正在用高德计算…" : "预览优化结果" }}</button>

            <view v-else-if="!preview.changed" class="result same">
              <Icon name="checkCircle" :size="18" color="var(--ok)" />
              <view>
                <view class="strong">这一天的顺序已经是最顺的了</view>
                <view class="muted small">在不动固定安排、也不让后面的安排更赶的前提下，没找到{{ goalWord }}的排法。</view>
                <view v-if="preview.lateBefore > 0" class="warn">按高德实际路程，这一天的吃饭、交通等固定安排一共会晚约 {{ preview.lateBefore }} 分钟，时间排得偏紧。</view>
              </view>
            </view>

            <view v-else class="result">
              <view class="preview-tag">预览，还没保存</view>
              <view class="cmp">
                <view v-for="row in compareRows" :key="row.label" class="cmp-row">
                  <text class="cmp-k">{{ row.label }}</text>
                  <text class="cmp-b">{{ row.before }}</text>
                  <text class="cmp-arrow">→</text>
                  <text class="cmp-a">{{ row.after }}</text>
                  <text class="cmp-d" :class="row.tone">{{ row.delta }}</text>
                </view>
              </view>
              <view v-if="preview.lateAfter > 0" class="warn">
                按高德实际路程排下来，吃饭、交通这些固定安排一共还会晚约 {{ preview.lateAfter }} 分钟（原来的顺序会晚约 {{ preview.lateBefore }} 分钟）。可以考虑删掉一项，或者缩短游玩时间。
              </view>
              <view class="panel-sub">新的顺序</view>
              <view v-for="(it, i) in preview.after.items" :key="it.id" class="order" :class="{ moved: movedIds.has(it.id) }">
                <text class="order-time">{{ it.start }}</text>
                <text class="order-name">{{ it.name }}</text>
                <text v-if="movedIds.has(it.id)" class="order-was">原 {{ preview.before.items.findIndex((x) => x.id === it.id) + 1 }} → {{ i + 1 }}</text>
              </view>
              <view class="acts">
                <button class="btn" :disabled="saving" @click="applyOptimize">{{ saving ? "保存中…" : "确认优化并保存" }}</button>
                <button class="btn ghost" :disabled="saving" @click="clearPreview">不用了</button>
              </view>
              <view class="muted small">保存后存成新版本，旧版本可以在行程页回看。</view>
            </view>
          </view>
        </view>
      </view>
    </template>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import RouteMap from "../../components/RouteMap.vue";
import { computed, reactive, ref, watch } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { DayPreview, ItineraryItem, ItineraryLeg, OptimizeGoal, TripDetail } from "../../api/types";
import Icon from "../../components/Icon.vue";
import { openUrl, shortDate } from "../../utils/format";
import { compareTotals, dayTravel, duration, legFrom, needsGeo, type MapLine, type MapStop } from "../../utils/place";
import LegRow from "../../components/LegRow.vue";
import { useWide } from "../../utils/screen";

/**
 * 行程地图与路线优化：左边当天安排、中间可点的路线地图（和列表双向联动）、右边合计和路线优化。
 * 优化先出预览（路程全部来自高德，不经过 AI），确认后才存成新版本。参数 ?id=行程&day=第几天
 */
const GOALS: { value: OptimizeGoal; label: string; hint: string }[] = [
  { value: "TIME", label: "时间最短", hint: "让路上花的时间最少" },
  { value: "DISTANCE", label: "距离最短", hint: "让走的总里程最少" },
  { value: "COST", label: "费用最低", hint: "多安排步行段，打车里程最少" },
];
/** 每天路线的颜色，和图例、日期标签一致 */
const COLORS = ["2563EB", "8B5CF6", "10B981", "F59E0B", "EC4899", "0EA5E9", "EF4444"];
const placeTypes = new Set(["SPOT", "FOOD", "HOTEL"]);

const wide = useWide();
const tripId = ref(0);
const dayIndex = ref(0);
const detail = ref<TripDetail | null>(null);
const loading = ref(false);
const broken = reactive<Record<string, boolean>>({});
const activeId = ref<string | null>(null);
const overlay = ref(new Set<number>());
const mapRef = ref<{ focus: (id: string) => void } | null>(null);

const goal = ref<OptimizeGoal>("TIME");
const preview = ref<DayPreview | null>(null);
const previewing = ref(false);
const saving = ref(false);
const showAfter = ref(true);

const trip = computed(() => detail.value?.trip);
const days = computed(() => detail.value?.version.itinerary?.days || []);
const day = computed(() => days.value[dayIndex.value]);
const booked = computed(() => new Set(detail.value?.version.itinerary?.booked || []));
const travel = computed(() => dayTravel(day.value));
/** 当天交通费：地铁公交每人票价加打车费 */
const fareText = computed(() => {
  const parts = [];
  if (travel.value.fare != null) parts.push(`¥${travel.value.fare}/人`);
  if (travel.value.taxi != null) parts.push(`打车 ¥${travel.value.taxi}`);
  return parts.length ? parts.join(" + ") : "—";
});
/** 正在切换某段路的出行方式，避免连点 */
const modeBusy = ref(false);
const mapW = computed(() => (wide.value ? 640 : 600));
const mapH = computed(() => (wide.value ? 560 : 420));

/** 地图上画的这一天：有预览时默认画优化后的顺序 */
const shownItems = computed(() => (preview.value?.changed && showAfter.value ? preview.value.after.items : day.value?.items) || []);
const mapStops = computed<MapStop[]>(() => shownItems.value.filter(hasGeo).map((i) => ({ id: i.id, name: i.name, lng: i.lng!, lat: i.lat! })));
const overlayDays = computed(() => [...overlay.value].filter((i) => i !== dayIndex.value && i < days.value.length).sort());
const overlayLines = computed<MapLine[]>(() =>
  overlayDays.value.map((i) => ({ color: colorOf(i), points: days.value[i].items.filter(hasGeo).map((x) => [x.lng!, x.lat!]) })),
);
const movedIds = computed(() => {
  const p = preview.value;
  if (!p?.changed) return new Set<string>();
  return new Set(p.after.items.filter((it, i) => p.before.items[i]?.id !== it.id).map((it) => it.id));
});
const goalWord = computed(() => ({ TIME: "更省时间", DISTANCE: "更少里程", COST: "更省打车费" })[goal.value]);

/** 前后对比的几行：数值都来自高德，变好绿色、变差橙色 */
const compareRows = computed(() => (preview.value ? compareTotals(preview.value.beforeTotals, preview.value.afterTotals) : []));

onLoad(async (query) => {
  tripId.value = Number(query?.id) || 0;
  dayIndex.value = Math.max(0, (Number(query?.day) || 1) - 1);
  loading.value = true;
  try {
    if (!tripId.value) {
      // 从侧边栏进来没带行程：用最近的一个
      tripId.value = (await api.trips())[0]?.id || 0;
    }
    if (!tripId.value) return;
    detail.value = await api.trip(tripId.value);
    const it = detail.value.version.itinerary;
    // 没补过坐标，或早先算的路程缺打车费、地铁公交：补一次
    if (needsGeo(it)) detail.value = await api.tripGeo(tripId.value);
    dayIndex.value = Math.min(dayIndex.value, Math.max(days.value.length - 1, 0));
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
});

watch(dayIndex, () => {
  activeId.value = null;
  clearPreview();
});

function colorOf(i: number) {
  return COLORS[i % COLORS.length];
}

function hasGeo(it: ItineraryItem) {
  return it.lng != null && it.lat != null;
}

/** 这一项在地图上的编号 */
function stopNo(id: string) {
  return (day.value?.items || []).filter(hasGeo).findIndex((x) => x.id === id) + 1;
}

function pickDay(i: number) {
  dayIndex.value = i;
}

function toggleOverlay(i: number) {
  const next = new Set(overlay.value);
  next.has(i) ? next.delete(i) : next.add(i);
  overlay.value = next;
}

/** 点地图上的编号：高亮并滚到列表里那一项 */
function onPick(id: string) {
  activeId.value = id;
  // #ifdef H5
  document.getElementById("it-" + id)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  // #endif
}

/** 点列表里的一项：地图以它为中心放大；手机上顺便滚回地图 */
function onSelect(it: ItineraryItem) {
  activeId.value = it.id;
  if (!hasGeo(it)) {
    if (placeTypes.has(it.type)) uni.showToast({ title: "这一项在高德上没找到坐标", icon: "none" });
    return;
  }
  if (preview.value?.changed && !showAfter.value) showAfter.value = true;
  mapRef.value?.focus(it.id);
  if (!wide.value) uni.pageScrollTo({ selector: "#map-col", duration: 200 });
}

function setGoal(g: OptimizeGoal) {
  if (goal.value === g) return;
  goal.value = g;
  clearPreview();
}

async function runPreview() {
  previewing.value = true;
  try {
    preview.value = await api.previewOptimize(tripId.value, dayIndex.value + 1, goal.value);
    showAfter.value = true;
  } catch (e) {
    showError(e);
  } finally {
    previewing.value = false;
  }
}

function clearPreview() {
  preview.value = null;
}

async function applyOptimize() {
  const p = preview.value;
  if (!p?.changed) return;
  saving.value = true;
  try {
    detail.value = await api.optimizeDay(tripId.value, dayIndex.value + 1, goal.value, p.after.items.map((i) => i.id));
    preview.value = null;
    uni.showToast({ title: `已优化，存成第 ${detail.value.version.version} 版`, icon: "none" });
  } catch (e) {
    showError(e);
    // 行程刚被改过：重新预览一次
    preview.value = null;
  } finally {
    saving.value = false;
  }
}

/** 单独改一段路坐地铁公交还是打车（只改当前版本，不算新版本） */
async function setLegMode(leg: ItineraryLeg, mode: "TRANSIT" | "DRIVE") {
  modeBusy.value = true;
  try {
    detail.value = await api.setLegMode(tripId.value, leg.from, leg.to, mode);
  } catch (e) {
    showError(e);
  } finally {
    modeBusy.value = false;
  }
}

/** 用高德导航到下一站 */
function navigate(from: ItineraryItem, leg: ItineraryLeg) {
  const to = day.value?.items.find((x) => x.id === leg.to);
  if (!to || !hasGeo(from) || !hasGeo(to)) return;
  const p = (x: ItineraryItem) => `${x.lng},${x.lat},${encodeURIComponent(x.name)}`;
  const mode = leg.mode === "WALK" ? "walk" : leg.mode === "TRANSIT" ? "bus" : "car";
  openUrl(`https://uri.amap.com/navigation?from=${p(from)}&to=${p(to)}&mode=${mode}&coordinate=gaode&callnative=1`);
}

function toDetail() {
  uni.redirectTo({ url: `/pages/trips/detail?id=${tripId.value}` });
}

function toFood() {
  uni.redirectTo({ url: `/pages/plan/food?id=${tripId.value}&day=${dayIndex.value + 1}` });
}

function goBack() {
  if (getCurrentPages().length > 1) uni.navigateBack();
  else toDetail();
}

function toChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 80px 0; }
.empty .btn { margin: 0; }
.top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.back { cursor: pointer; padding: 4px; }
.title { font-size: 18px; font-weight: 800; flex: 1; min-width: 0; }
.views { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; }
.view-tab { font-size: 13px; padding: 6px 12px; border-radius: 8px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.view-tab.on { background: var(--accent); color: #fff; font-weight: 600; }
.days { white-space: nowrap; margin-bottom: 12px; }
.days-inner { display: inline-flex; gap: 8px; }
.day { display: inline-flex; align-items: baseline; gap: 4px; padding: 7px 14px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.day.on { color: #fff; border-color: transparent; }
.day-no { font-weight: 800; }
.day-date { font-size: 12px; opacity: 0.8; }
.cols { display: flex; flex-direction: column; gap: 12px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.side-col { display: flex; flex-direction: column; gap: 12px; order: 1; }
.list-col { order: 2; }
.panel-title { font-weight: 800; font-size: 15px; }
.panel-sub { font-weight: 700; font-size: 13px; margin-top: 4px; }
.warn { font-size: 12px; line-height: 1.5; padding: 8px 10px; border-radius: 8px; background: var(--surface-2); color: var(--price); }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.pad { padding: 10px 0; }
.strong { font-weight: 700; }

.map-bar { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.layers { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.layer { font-size: 12px; padding: 3px 10px; border-radius: 999px; border: 1px solid var(--line); background: var(--surface); cursor: pointer; }
.layer.on { border-color: var(--c); color: var(--c); font-weight: 700; }
.seg { display: flex; background: var(--surface-2); border-radius: 8px; padding: 2px; }
.seg-opt { font-size: 12px; padding: 4px 10px; border-radius: 6px; color: var(--muted); cursor: pointer; }
.seg-opt.on { background: var(--surface); color: var(--accent); font-weight: 700; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08); }
.legend { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; margin-top: 8px; font-size: 12px; }
.lg { display: inline-flex; align-items: center; gap: 6px; }
.lg-line { display: inline-block; width: 20px; height: 4px; border-radius: 2px; }
.lg-line.thin { height: 3px; opacity: 0.7; }

.it { display: grid; grid-template-columns: 26px minmax(0, 1fr) auto; gap: 4px 10px; padding: 10px 8px; border-radius: 12px; cursor: pointer; scroll-margin: 12px; }
.it.on { background: var(--accent-soft); outline: 2px solid var(--accent); }
.it-dot { width: 24px; height: 24px; border-radius: 50%; background: var(--surface-2); color: #fff; font-size: 12px; font-weight: 800; display: flex; align-items: center; justify-content: center; margin-top: 2px; }
.it-body { min-width: 0; }
.it-time { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.it-name { font-weight: 700; }
.it.nogeo .it-name { font-weight: 600; color: var(--muted); }
.it-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 2px; }
.badge { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--accent-soft); color: var(--accent); }
.badge.ok { background: var(--ok-soft); color: var(--ok); }
.it-img { width: 72px; height: 54px; border-radius: 8px; }
.it-leg { grid-column: 2 / 4; display: flex; align-items: flex-start; gap: 6px; font-size: 12px; color: var(--muted); background: var(--surface-2); border-radius: 8px; padding: 5px 8px; }
.it-leg-row { flex: 1; min-width: 0; }
.nav-link { color: var(--accent); margin-left: auto; cursor: pointer; white-space: nowrap; }

.stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.stat { display: flex; flex-direction: column; gap: 2px; padding: 10px; border-radius: 10px; background: var(--surface-2); }
.stat-k { font-size: 12px; color: var(--muted); }
.stat-v { font-weight: 800; font-variant-numeric: tabular-nums; }
.goals { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.goal { text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
.goal.on { background: var(--accent); color: #fff; font-weight: 700; }
.wide-btn { width: 100%; margin: 0; }
.result { display: flex; flex-direction: column; gap: 8px; }
.result.same { flex-direction: row; gap: 8px; align-items: flex-start; padding: 10px; border-radius: 10px; background: var(--ok-soft); }
.preview-tag { align-self: flex-start; font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--price); }
.cmp { display: flex; flex-direction: column; gap: 6px; padding: 10px; border-radius: 10px; background: var(--surface-2); }
.cmp-row { display: grid; grid-template-columns: 52px auto 12px auto 1fr; gap: 6px; align-items: baseline; font-size: 13px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.cmp-row .cmp-d { text-align: right; }
.cmp-k { color: var(--muted); font-size: 12px; }
.cmp-b { color: var(--muted); }
.cmp-arrow { color: var(--muted); }
.cmp-a { font-weight: 700; }
.cmp-d { font-size: 12px; font-weight: 700; }
.cmp-d.good { color: var(--ok); }
.cmp-d.bad { color: var(--price); }
.order { display: flex; align-items: baseline; gap: 8px; font-size: 13px; padding: 3px 0; }
.order-time { color: var(--muted); width: 40px; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.order-name { flex: 1; min-width: 0; }
.order.moved .order-name { font-weight: 700; }
.order-was { font-size: 11px; color: var(--price); white-space: nowrap; }
.acts { display: grid; grid-template-columns: 2fr 1fr; gap: 8px; }
.acts .btn { margin: 0; }

@media (min-width: 1024px) {
  .page { max-width: 1400px; padding: 20px 24px; }
  .title { font-size: 22px; }
  .cols { display: grid; grid-template-columns: 300px minmax(0, 1fr) 320px; align-items: start; }
  .side-col { order: 1; }
  .list-col { order: -1; max-height: calc(100vh - 170px); overflow-y: auto; border-radius: 14px; }
  .side-col { position: sticky; top: 20px; }
}
</style>
