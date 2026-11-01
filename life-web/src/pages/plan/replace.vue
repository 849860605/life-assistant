<template>
  <Shell active="plan">
  <view class="page">
    <view v-if="!tripId" class="empty">
      <Icon name="trip" :size="34" color="var(--muted)" />
      <view>还没有要替换的行程</view>
      <button class="btn small" @click="toChat">先在对话里生成一个行程</button>
    </view>
    <template v-else>
      <view class="head">
        <image v-if="item?.photo" class="head-img" :src="item.photo" mode="aspectFill" />
        <view>
          <view class="title">替换：{{ item?.name || "…" }}</view>
          <view class="sub">{{ item?.start }}<text v-if="item?.end"> – {{ item.end }}</text> · 从附近同类、评分高的地方里挑一个</view>
        </view>
      </view>
      <view class="reasons">
        <text v-for="r in REASONS" :key="r.label" class="reason" :class="{ on: why === r.value }" @click="why = r.value">{{ r.label }}</text>
      </view>
      <scroll-view scroll-x class="prefs">
        <view class="prefs-inner">
          <text class="pref" :class="{ on: !prefs.size }" @click="clearPrefs">不限</text>
          <text v-for="p in prefOptions" :key="p" class="pref" :class="{ on: prefs.has(p) }" @click="togglePref(p)">{{ p }}</text>
        </view>
      </scroll-view>
      <view class="search">
        <Icon name="search" :size="14" color="var(--muted)" />
        <input id="replace-search" v-model="keyword" class="search-input" placeholder="其他想找的，如：书店" @confirm="loadList" />
      </view>
      <view class="tabs">
        <text v-for="t in SORTS" :key="t" class="tab" :class="{ active: sort === t }" @click="sort = t">{{ t }}</text>
      </view>
      <view v-if="loading" class="state muted">正在找附近的地点…</view>
      <view v-else-if="!sorted.length" class="state muted">{{ error || "附近没找到合适的，换个偏好试试" }}</view>
      <view class="grid">
        <view v-for="r in sorted" :key="r.id" class="place" @click="choose(r)">
          <image v-if="r.photo && !broken[r.id]" class="img" :src="r.photo" mode="aspectFill" @error="broken[r.id] = true" />
          <view v-else class="img ph"><Icon name="pin" :size="24" color="#fff" /></view>
          <view class="info">
            <view class="name">{{ r.name }}</view>
            <view class="tags"><text v-for="t in (r.tags?.length ? r.tags : [r.category])" :key="t" class="tag">{{ t }}</text></view>
            <view class="meta"><text v-if="r.rating" class="rating">★ {{ r.rating }}</text><text v-if="r.cost" class="muted"> · 人均 ¥{{ r.cost }}</text></view>
            <view v-if="r.distance != null" class="meta muted">距原地点 {{ distanceText(r.distance) }} · {{ travelGuess(r.distance) }}</view>
          </view>
          <button class="btn ghost small pick">选这个</button>
        </view>
      </view>
      <button class="btn ghost remove" :disabled="saving || !item" @click="removeIt">不换了，直接删掉这一项</button>
      <view class="note">地点、评分、距离来自高德；车程按直线距离估，选中后按高德实际路线算。</view>
    </template>

    <!-- 选中后：底部弹出前后对比，确认才保存 -->
    <view v-if="picked" class="sheet-mask" @click.self="picked = null">
      <view class="sheet">
        <view class="sheet-bar" />
        <view class="sheet-title">换成「{{ picked.name }}」</view>
        <view class="swap muted small">{{ item?.name }} → {{ picked.name }} · {{ item?.start }}–{{ item?.end }} 时间不变</view>
        <view v-if="previewing" class="muted small">正在用高德计算对当天路线的影响…</view>
        <view v-else-if="previewError" class="muted small">{{ previewError }}</view>
        <view v-else class="cmp">
          <view v-for="row in impact" :key="row.label" class="cmp-row">
            <text class="cmp-k">{{ row.label }}</text>
            <text class="cmp-b">{{ row.before }}</text>
            <text class="cmp-arrow">→</text>
            <text class="cmp-a">{{ row.after }}</text>
            <text class="cmp-d" :class="row.tone">{{ row.delta }}</text>
          </view>
        </view>
        <button class="btn confirm" :disabled="saving" @click="confirmPick">{{ saving ? "保存中…" : "确认替换" }}</button>
        <button class="btn ghost confirm" :disabled="saving" @click="picked = null">再看看</button>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { ChangeReason, ItineraryItem, Place } from "../../api/types";
import Icon from "../../components/Icon.vue";
import { compareTotals, distanceText, travelGuess, type CompareRow } from "../../utils/place";
import { PREFS, pickOf, sortPlaces, type PlaceSort } from "../../utils/replace";

/**
 * 景点替换（手机）：高德附近同类候选，偏好可多选。点一个候选从底部弹出对当天路线的影响（按高德算），
 * 确认后保存并去看前后对比。参数 ?id=行程&item=项目id&reason=
 */
const SORTS: PlaceSort[] = ["综合推荐", "距离最近", "评分最高"];
const REASONS: { label: string; value: ChangeReason | null }[] = [
  { label: "去过了", value: "VISITED" },
  { label: "不感兴趣", value: "DISLIKE" },
  { label: "只是想换", value: null },
];
const tripId = ref(0);
const itemId = ref("");
const item = ref<ItineraryItem | null>(null);
const version = ref(0);
const why = ref<ChangeReason | null>(null);
const prefs = ref(new Set<string>());
const keyword = ref("");
const sort = ref<PlaceSort>("综合推荐");
const list = ref<Place[]>([]);
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const broken = reactive<Record<string, boolean>>({});
const picked = ref<Place | null>(null);
const previewing = ref(false);
const previewError = ref("");
const impact = ref<CompareRow[]>([]);
let previewSeq = 0;

const prefOptions = computed(() => PREFS[item.value?.type || "SPOT"] || PREFS.SPOT);
const sorted = computed(() => sortPlaces(list.value, sort.value));

onLoad(async (query) => {
  tripId.value = Number(query?.id) || 0;
  itemId.value = query?.item || "";
  why.value = (query?.reason as ChangeReason) || null;
  if (!tripId.value || !itemId.value) {
    tripId.value = 0;
    return;
  }
  try {
    const d = await api.trip(tripId.value);
    version.value = d.version.version;
    item.value = d.version.itinerary?.days.flatMap((x) => x.items).find((x) => x.id === itemId.value) || null;
  } catch (e) {
    showError(e);
  }
  await loadList();
});

async function loadList() {
  loading.value = true;
  error.value = "";
  const words = [...prefs.value, keyword.value.trim()].filter(Boolean).join(",");
  try {
    list.value = await api.alternatives(tripId.value, itemId.value, words || undefined);
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
  loadList();
}

function clearPrefs() {
  if (!prefs.value.size) return;
  prefs.value = new Set();
  loadList();
}

/** 选中一个候选：弹出底部面板，按高德算影响 */
async function choose(p: Place) {
  picked.value = p;
  impact.value = [];
  previewError.value = "";
  const seq = ++previewSeq;
  previewing.value = true;
  try {
    const r = await api.previewReplace(tripId.value, itemId.value, pickOf(p));
    if (seq === previewSeq) impact.value = compareTotals(r.beforeTotals, r.afterTotals);
  } catch (e) {
    if (seq === previewSeq) previewError.value = e instanceof Error ? e.message : String(e);
  } finally {
    if (seq === previewSeq) previewing.value = false;
  }
}

async function confirmPick() {
  const p = picked.value;
  if (!p) return;
  saving.value = true;
  try {
    const d = await api.replaceItem(tripId.value, itemId.value, pickOf(p), why.value);
    uni.redirectTo({ url: `/pages/plan/updated?id=${tripId.value}&from=${version.value}&to=${d.version.version}` });
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}

async function removeIt() {
  saving.value = true;
  try {
    const d = await api.removeItem(tripId.value, itemId.value, why.value);
    uni.redirectTo({ url: `/pages/plan/updated?id=${tripId.value}&from=${version.value}&to=${d.version.version}` });
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}

function toChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.head-img { width: 52px; height: 52px; border-radius: 10px; flex-shrink: 0; }
.title { font-weight: 700; font-size: 16px; }
.sub { font-size: 12px; color: var(--muted); }
.reasons { display: flex; gap: 8px; margin-bottom: 10px; }
.reason { font-size: 13px; padding: 5px 12px; border-radius: 999px; background: var(--surface-2); cursor: pointer; }
.reason.on { background: var(--accent); color: #fff; }
.prefs { white-space: nowrap; margin-bottom: 10px; }
.prefs-inner { display: inline-flex; gap: 8px; }
.pref { flex-shrink: 0; white-space: nowrap; font-size: 13px; padding: 5px 12px; border-radius: 8px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.pref.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); font-weight: 600; }
.search { display: flex; align-items: center; gap: 6px; height: 36px; padding: 0 12px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); margin-bottom: 10px; }
.search-input { flex: 1; font-size: 14px; }
.state { padding: 40px 0; text-align: center; font-size: 14px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.ph { display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #93c5fd, #2563eb); }
.remove { width: 100%; margin: 14px 0 0; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 80px 0; }
.empty .btn { margin: 0; }
.tabs { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; margin-bottom: 12px; }
.tab { flex: 1; text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; color: var(--muted); cursor: pointer; }
.tab.active { background: var(--surface); color: var(--accent); font-weight: 600; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08); }
.grid { display: flex; flex-direction: column; gap: 10px; }
.place { display: flex; gap: 10px; align-items: center; background: var(--surface); border-radius: 14px; padding: 10px; border: 1px solid var(--line); cursor: pointer; }
.img { width: 96px; height: 84px; border-radius: 10px; flex-shrink: 0; }
.info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.name { font-weight: 700; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--accent-soft); color: var(--accent); }
.meta { font-size: 12px; }
.rating { color: var(--price); font-weight: 700; }
.pick { margin: 0; flex-shrink: 0; color: var(--accent); border-color: var(--accent); }
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
.sheet-mask { position: fixed; inset: 0; z-index: 100; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; }
.sheet {
  width: 100%;
  background: var(--surface);
  border-radius: 18px 18px 0 0;
  padding: 10px 16px calc(16px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.sheet-bar { align-self: center; width: 40px; height: 4px; border-radius: 2px; background: var(--line); }
.sheet-title { font-weight: 800; font-size: 16px; }
.cmp { display: flex; flex-direction: column; gap: 6px; padding: 10px; border-radius: 10px; background: var(--surface-2); }
.cmp-row { display: grid; grid-template-columns: 52px auto 12px auto 1fr; gap: 6px; align-items: baseline; font-size: 13px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.cmp-row .cmp-d { text-align: right; }
.cmp-k { color: var(--muted); font-size: 12px; }
.cmp-b, .cmp-arrow { color: var(--muted); }
.cmp-a { font-weight: 700; }
.cmp-d { font-size: 12px; font-weight: 700; }
.cmp-d.good { color: var(--ok); }
.cmp-d.bad { color: var(--price); }
.confirm { width: 100%; margin: 0; }
@media (min-width: 700px) {
  .sheet { max-width: 560px; margin: 0 auto; border-radius: 18px 18px 0 0; }
}
</style>
