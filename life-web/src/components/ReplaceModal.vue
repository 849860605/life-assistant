<template>
  <view class="overlay" @click.self="emit('close')">
    <view class="modal">
      <view class="m-head">
        <view>
          <view class="m-title">替换{{ KIND[item.type] || "景点" }}</view>
          <view class="m-sub">从附近同类、评分高的地点里选一个替代，确认后按高德重算当天路线</view>
        </view>
        <view class="m-close" @click="emit('close')"><Icon name="close" :size="20" color="var(--ink)" /></view>
      </view>

      <view class="cols">
        <!-- 左：当前项、原因、筛选 -->
        <scroll-view scroll-y class="col left">
          <view class="box">
            <view class="box-title">当前{{ KIND[item.type] || "景点" }}</view>
            <view class="current">
              <image v-if="item.photo" class="cur-img" :src="item.photo" mode="aspectFill" />
              <view v-else class="cur-img ph"><Icon name="pin" :size="24" color="#fff" /></view>
              <view class="cur-body">
                <view class="cur-time"><text class="day-chip">D{{ dayNo }}</text> {{ item.start }} – {{ item.end }}</view>
                <view class="cur-name">{{ item.name }}</view>
                <view v-if="item.rating" class="cur-note"><text class="star">★ {{ item.rating }}</text> 高德评分</view>
              </view>
            </view>
            <view class="facts">
              <view><Icon name="calendar" :size="14" color="var(--muted)" /> {{ stayText(item) }}</view>
              <view v-if="item.address"><Icon name="pin" :size="14" color="var(--muted)" /> {{ item.address }}</view>
            </view>
          </view>
          <view class="box">
            <view class="box-title">为什么换</view>
            <view class="range three">
              <text v-for="r in REASONS" :key="r.label" class="range-opt" :class="{ on: why === r.value }" @click="why = r.value">{{ r.label }}</text>
            </view>
            <view class="muted small why-note">选"去过了""不感兴趣"会记下来，以后规划时避开</view>
          </view>
          <view class="box">
            <view class="box-title">替换偏好 <text class="muted small">（可多选）</text></view>
            <view class="chips">
              <text class="chip-opt" :class="{ on: !prefs.size }" @click="clearPrefs">不限</text>
              <text v-for="p in prefOptions" :key="p" class="chip-opt" :class="{ on: prefs.has(p) }" @click="togglePref(p)">{{ p }}</text>
            </view>
            <view class="search">
              <Icon name="search" :size="14" color="var(--muted)" />
              <input id="replace-keyword" v-model="keyword" class="search-input" placeholder="其他想找的，如：书店" @confirm="load" />
            </view>
          </view>
          <view class="box">
            <view class="box-title">位置范围</view>
            <view class="range">
              <text v-for="r in RANGES" :key="r" class="range-opt" :class="{ on: range === r }" @click="range = r">{{ r }}公里</text>
            </view>
            <view class="near">
              <image v-if="nearMap" class="near-map" :src="nearMap" mode="aspectFill" />
              <view v-else class="near-map empty"><text class="muted small">这一项没有坐标，按城市搜索</text></view>
            </view>
            <view class="muted small">蓝点是原地点，圈是范围，橙色字母对应中间的候选</view>
          </view>
        </scroll-view>

        <!-- 中：候选 -->
        <scroll-view scroll-y class="col mid">
          <view class="mid-head">
            <view>
              <text class="box-title">推荐替换</text>
              <text class="muted small">（高德附近同类地点，{{ shown.length }} 个）</text>
            </view>
            <view class="sorts">
              <text v-for="o in SORTS" :key="o" class="sort" :class="{ on: sort === o }" @click="sort = o">{{ o }}</text>
            </view>
          </view>
          <view v-if="loading" class="state muted">正在找附近的地点…</view>
          <view v-else-if="!shown.length" class="state muted">{{ error || "这个范围里没找到合适的，换个偏好或扩大范围试试" }}</view>
          <view v-for="r in shown" :key="r.id" class="cand" :class="{ picked: picked?.id === r.id }" @click="picked = r">
            <view class="cand-img-wrap">
              <image v-if="r.photo && !broken[r.id]" class="cand-img" @error="broken[r.id] = true" :src="r.photo" mode="aspectFill" />
              <view v-else class="cand-img ph"><Icon name="pin" :size="24" color="#fff" /></view>
              <view class="cand-no">{{ letter(r) }}</view>
            </view>
            <view class="cand-body">
              <view class="cand-name">{{ r.name }}</view>
              <view class="tags">
                <text v-for="t in (r.tags?.length ? r.tags : [r.category])" :key="t" class="tag">{{ t }}</text>
              </view>
              <view v-if="r.distance != null" class="cand-meta">距原地点 {{ distanceText(r.distance) }} · {{ travelGuess(r.distance) }}</view>
              <view class="cand-meta"><text v-if="r.rating" class="star">★ {{ r.rating }}</text><text v-if="r.rating"> 高德评分</text><text v-if="r.cost"> · 人均 ¥{{ r.cost }}</text></view>
              <view v-if="r.address" class="cand-desc">{{ r.address }}</view>
              <text class="amap" @click.stop="openUrl(amapUrl(r))">在高德地图里看 ›</text>
            </view>
            <button class="btn small pick" :class="{ ghost: picked?.id !== r.id }">{{ picked?.id === r.id ? "✓ 已选择" : "选择替换" }}</button>
          </view>
          <view class="muted small foot">车程是按直线距离估的；选中后右边按高德实际路线算。</view>
        </scroll-view>

        <!-- 右：替换前后 -->
        <scroll-view scroll-y class="col right">
          <view class="box-title">替换后行程预览</view>
          <view class="swap">
            <view class="swap-row">
              <text class="swap-k">原</text>
              <text class="swap-name old">{{ item.name }}</text>
            </view>
            <view class="swap-row">
              <text class="swap-k new">新</text>
              <text class="swap-name">{{ picked?.name || "请在中间选一个地点" }}</text>
            </view>
            <view class="muted small">{{ item.start }} – {{ item.end }}，时间段不变</view>
          </view>
          <view class="impact">
            <view class="box-title">对当天路线的影响</view>
            <view v-if="!picked" class="muted small">选中候选后，这里会按高德实际路线算出前后变化。</view>
            <view v-else-if="previewing" class="muted small">正在用高德计算…</view>
            <view v-else-if="previewError" class="muted small">{{ previewError }}</view>
            <view v-else-if="impact.length" class="cmp">
              <view v-for="row in impact" :key="row.label" class="cmp-row">
                <text class="cmp-k">{{ row.label }}</text>
                <text class="cmp-b">{{ row.before }}</text>
                <text class="cmp-arrow">→</text>
                <text class="cmp-a">{{ row.after }}</text>
                <text class="cmp-d" :class="row.tone">{{ row.delta }}</text>
              </view>
            </view>
            <view class="muted small">保存后存成新版本，旧版本可以回看。</view>
          </view>
          <button class="btn confirm" :disabled="!picked || saving" @click="confirm">{{ saving ? "保存中…" : "确认替换并重新规划" }}</button>
          <button class="btn ghost cancel" :disabled="saving" @click="removeIt">不换了，直接删掉这一项</button>
          <button class="btn ghost cancel" @click="emit('close')">取消</button>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { ChangeReason, ItineraryItem, Place, TripDetail } from "../api/types";
import Icon from "./Icon.vue";
import { amapUrl, compareTotals, distanceText, mapUrl, travelGuess, type CompareRow } from "../utils/place";
import { openUrl } from "../utils/format";
import { PREFS, pickOf, sortPlaces, type PlaceSort } from "../utils/replace";

/**
 * 替换弹窗（电脑行程编辑页）：左边当前项、原因和筛选，中间高德候选，右边替换前后对当天路线的影响。
 * 影响按高德实际路线算（不保存）；确认后存成新版本，把新的行程交给页面。
 */
const props = defineProps<{ tripId: number; item: ItineraryItem; dayNo: number; reason?: ChangeReason | null }>();
const emit = defineEmits<{ (e: "close"): void; (e: "saved", detail: TripDetail): void }>();

const KIND: Record<string, string> = { SPOT: "景点", FOOD: "餐厅", HOTEL: "酒店" };
const REASONS: { label: string; value: ChangeReason | null }[] = [
  { label: "去过了", value: "VISITED" },
  { label: "不感兴趣", value: "DISLIKE" },
  { label: "只是想换", value: null },
];
const RANGES = [1, 3, 5, 10];
const SORTS: PlaceSort[] = ["综合推荐", "距离最近", "评分最高"];

const why = ref<ChangeReason | null>(props.reason ?? null);
const prefs = ref(new Set<string>());
const keyword = ref("");
const range = ref(10);
const sort = ref<PlaceSort>("综合推荐");
const list = ref<Place[]>([]);
const picked = ref<Place | null>(null);
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const broken = reactive<Record<string, boolean>>({});
const previewing = ref(false);
const previewError = ref("");
const impact = ref<CompareRow[]>([]);
let previewSeq = 0;

const prefOptions = computed(() => PREFS[props.item.type] || PREFS.SPOT);
/** 按范围筛（没有距离的、按城市搜的都留着），再排序 */
const shown = computed(() => sortPlaces(list.value.filter((p) => p.distance == null || p.distance <= range.value * 1000), sort.value).slice(0, 10));
/** 地图上的字母按距离排，和列表怎么排序无关 */
const lettered = computed(() => [...shown.value].sort((a, b) => (a.distance ?? 1e9) - (b.distance ?? 1e9)));
const nearMap = computed(() => {
  if (props.item.lng == null || props.item.lat == null) return "";
  const others = lettered.value.filter((p) => p.lng != null && p.lat != null).map((p) => [p.lng!, p.lat!]);
  return mapUrl({ stops: [[props.item.lng, props.item.lat]], others, circle: range.value * 1000, w: 300, h: 220 });
});

onMounted(load);

watch(picked, (p) => {
  impact.value = [];
  previewError.value = "";
  if (p) loadImpact(p);
});

async function load() {
  loading.value = true;
  error.value = "";
  const words = [...prefs.value, keyword.value.trim()].filter(Boolean).join(",");
  try {
    list.value = await api.alternatives(props.tripId, props.item.id, words || undefined);
    picked.value = shown.value[0] || null;
  } catch (e) {
    list.value = [];
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function togglePref(p: string) {
  const next = new Set(prefs.value);
  next.has(p) ? next.delete(p) : next.add(p);
  prefs.value = next;
  load();
}

function clearPrefs() {
  if (!prefs.value.size) return;
  prefs.value = new Set();
  load();
}

/** 候选在地图上的字母 */
function letter(p: Place) {
  const i = lettered.value.findIndex((x) => x.id === p.id);
  return i >= 0 && i < 10 ? String.fromCharCode(65 + i) : "";
}

/** 按高德实际路线算换上这个地点后当天的变化；连续点几个只认最后一次 */
async function loadImpact(p: Place) {
  const seq = ++previewSeq;
  previewing.value = true;
  try {
    const r = await api.previewReplace(props.tripId, props.item.id, pickOf(p));
    if (seq === previewSeq) impact.value = compareTotals(r.beforeTotals, r.afterTotals);
  } catch (e) {
    if (seq === previewSeq) previewError.value = e instanceof Error ? e.message : String(e);
  } finally {
    if (seq === previewSeq) previewing.value = false;
  }
}

/** "09:00"–"11:30" 算出"游玩约 2.5 小时"。 */
function stayText(it: ItineraryItem) {
  if (!it.start || !it.end) return "时长待定";
  const [h1, m1] = it.start.split(":").map(Number);
  const [h2, m2] = it.end.split(":").map(Number);
  const hours = (h2 * 60 + m2 - h1 * 60 - m1) / 60;
  return `约 ${Number.isInteger(hours) ? hours : hours.toFixed(1)} 小时`;
}

async function confirm() {
  const p = picked.value;
  if (!p || saving.value) return;
  saving.value = true;
  try {
    emit("saved", await api.replaceItem(props.tripId, props.item.id, pickOf(p), why.value));
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}

async function removeIt() {
  if (saving.value) return;
  saving.value = true;
  try {
    emit("saved", await api.removeItem(props.tripId, props.item.id, why.value));
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.overlay { position: fixed; inset: 0; z-index: 100; background: rgba(15, 23, 42, 0.45); display: flex; align-items: center; justify-content: center; padding: 24px; box-sizing: border-box; }
.modal { width: min(1240px, 100%); height: min(880px, 100%); background: var(--bg); border-radius: 18px; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 24px 60px rgba(0, 0, 0, 0.3); }
.m-head { display: flex; justify-content: space-between; align-items: flex-start; padding: 20px 24px 14px; background: var(--surface); }
.m-title { font-size: 22px; font-weight: 800; }
.m-sub { font-size: 13px; color: var(--muted); margin-top: 2px; }
.m-close { cursor: pointer; padding: 4px; }
/* 行高要定死（minmax(0, 1fr)），否则网格行会被内容撑高，三栏的 scroll-view 超出弹窗被裁掉、没法滚动 */
.cols { flex: 1; min-height: 0; display: grid; grid-template-columns: 300px minmax(0, 1fr) 320px; grid-template-rows: minmax(0, 1fr); gap: 14px; padding: 14px; }
.col { height: 100%; min-height: 0; }
.box { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; margin-bottom: 12px; display: flex; flex-direction: column; gap: 10px; }
.box-title { font-weight: 700; font-size: 15px; }
.current { display: flex; gap: 10px; }
.cur-img { width: 104px; height: 86px; border-radius: 10px; flex-shrink: 0; }
.cur-body { min-width: 0; }
.ph { display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #93c5fd, #2563eb); }
.cur-time { font-size: 12px; color: var(--muted); }
.day-chip { font-size: 11px; padding: 1px 6px; border-radius: 4px; border: 1px solid var(--accent); color: var(--accent); }
.cur-name { font-weight: 800; margin-top: 4px; }
.cur-note { font-size: 12px; color: var(--muted); margin-top: 2px; }
.facts { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--ink); }
.facts view { display: flex; align-items: center; gap: 6px; }
.search { display: flex; align-items: center; gap: 6px; height: 34px; padding: 0 10px; border-radius: 8px; background: var(--surface-2); }
.search-input { flex: 1; font-size: 13px; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip-opt { font-size: 13px; padding: 5px 12px; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
.chip-opt.on { background: var(--accent); color: #fff; }
.range { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
.range.three { grid-template-columns: repeat(3, 1fr); }
.range-opt { text-align: center; font-size: 13px; padding: 5px 0; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
.range-opt.on { background: var(--accent-soft); color: var(--accent); font-weight: 700; }
.near { border-radius: 12px; overflow: hidden; aspect-ratio: 300 / 220; }
.near-map { width: 100%; height: 100%; }
.near-map.empty { display: flex; align-items: center; justify-content: center; background: var(--surface-2); }
.mid { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; box-sizing: border-box; }
.mid-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.sorts { display: flex; background: var(--surface-2); border-radius: 8px; padding: 2px; }
.sort { font-size: 12px; padding: 4px 10px; border-radius: 6px; color: var(--muted); cursor: pointer; }
.sort.on { background: var(--surface); color: var(--accent); font-weight: 700; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08); }
.state { padding: 40px 0; text-align: center; font-size: 14px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.why-note { margin-top: -2px; }
.cand { display: flex; gap: 12px; align-items: center; padding: 10px; border: 1px solid var(--line); border-radius: 14px; margin-bottom: 10px; cursor: pointer; }
.cand.picked { border: 2px solid var(--accent); background: var(--accent-soft); }
.cand-img-wrap { position: relative; flex-shrink: 0; }
.cand-img { width: 130px; height: 96px; border-radius: 10px; }
.cand-no:empty { display: none; }
.cand-no { position: absolute; left: 6px; top: 6px; width: 20px; height: 20px; border-radius: 50%; background: #f97316; color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.cand-body { flex: 1; min-width: 0; }
.cand-name { font-weight: 800; font-size: 15px; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; margin: 4px 0; }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--accent-soft); color: var(--accent); }
.cand-meta { font-size: 12px; color: var(--muted); }
.star { color: var(--price); font-weight: 700; }
.cand-desc { font-size: 12px; margin-top: 2px; }
.pick { margin: 0; flex-shrink: 0; }
.pick.ghost { color: var(--accent); border-color: var(--accent); }
.foot { text-align: center; padding: 4px 0 8px; }
.right { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; box-sizing: border-box; }
.swap { display: flex; flex-direction: column; gap: 6px; background: var(--surface-2); border-radius: 12px; padding: 12px; margin-top: 10px; }
.swap-row { display: flex; align-items: center; gap: 8px; }
.swap-k { font-size: 11px; font-weight: 700; padding: 1px 6px; border-radius: 4px; background: var(--surface); color: var(--muted); }
.swap-k.new { background: var(--accent); color: #fff; }
.swap-name { font-weight: 700; }
.swap-name.old { color: var(--muted); text-decoration: line-through; font-weight: 600; }
.impact { margin-top: 14px; display: flex; flex-direction: column; gap: 8px; }
.cmp { display: flex; flex-direction: column; gap: 6px; padding: 10px; border-radius: 10px; background: var(--surface-2); }
.cmp-row { display: grid; grid-template-columns: 52px auto 12px auto 1fr; gap: 6px; align-items: baseline; font-size: 13px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.cmp-row .cmp-d { text-align: right; }
.cmp-k { color: var(--muted); font-size: 12px; }
.cmp-b, .cmp-arrow { color: var(--muted); }
.cmp-a { font-weight: 700; }
.cmp-d { font-size: 12px; font-weight: 700; }
.cmp-d.good { color: var(--ok); }
.cmp-d.bad { color: var(--price); }
.confirm { width: 100%; margin: 14px 0 8px; height: 44px; }
.cancel { width: 100%; margin: 0 0 8px; }
.amap { display: inline-block; margin-top: 4px; font-size: 12px; color: var(--accent); cursor: pointer; }
</style>
