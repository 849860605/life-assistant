<template>
  <view class="overlay" @click.self="emit('close')">
    <view class="modal">
      <view class="m-head">
        <view>
          <view class="m-title">替换{{ KIND[item.type] || "景点" }}</view>
          <view class="m-sub">从附近同类、评分高的地点里选一个替代，保存后自动重算当天路线</view>
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
              <view>
                <view class="cur-time"><text class="day-chip">D{{ dayNo }}</text> {{ item.start }} – {{ item.end }}</view>
                <view class="cur-name">{{ item.name }}</view>
                <view class="cur-note">{{ item.note }}</view>
              </view>
            </view>
            <view class="facts">
              <view><Icon name="calendar" :size="14" color="var(--muted)" /> {{ duration(item) }}</view>
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
            <view class="box-title">想换成什么</view>
            <view class="search">
              <Icon name="search" :size="14" color="var(--muted)" />
              <input id="replace-keyword" v-model="keyword" class="search-input" placeholder="输入关键字，如：博物馆" @confirm="load" />
            </view>
            <view class="chips">
              <text v-for="p in prefOptions" :key="p" class="chip-opt" :class="{ on: (keyword || '不限') === p }" @click="pickPref(p)">{{ p }}</text>
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
          </view>
        </scroll-view>

        <!-- 中：候选 -->
        <scroll-view scroll-y class="col mid">
          <view class="mid-head">
            <text class="box-title">推荐替换</text>
            <text class="muted small">（高德附近同类地点，按评分排）</text>
          </view>
          <view v-if="loading" class="state muted">正在找附近的地点…</view>
          <view v-else-if="!shown.length" class="state muted">{{ error || "这个范围里没找到合适的，换个关键字或扩大范围试试" }}</view>
          <view v-for="(r, i) in shown" :key="r.id" class="cand" :class="{ picked: picked?.id === r.id }" @click="picked = r">
            <view class="cand-img-wrap">
              <image v-if="r.photo && !broken[r.id]" class="cand-img" @error="broken[r.id] = true" :src="r.photo" mode="aspectFill" />
              <view v-else class="cand-img ph"><Icon name="pin" :size="24" color="#fff" /></view>
              <view class="cand-no">{{ String.fromCharCode(65 + i) }}</view>
            </view>
            <view class="cand-body">
              <view class="cand-name">{{ r.name }}</view>
              <view class="tags"><text class="tag">{{ r.category }}</text><text v-if="r.area" class="tag">{{ r.area }}</text></view>
              <view v-if="r.distance != null" class="cand-meta">距原地点 {{ distanceText(r.distance) }}</view>
              <view class="cand-meta"><text v-if="r.rating" class="star">★ {{ r.rating }}</text><text v-if="r.rating"> 高德评分</text><text v-if="r.cost"> · 人均 ¥{{ r.cost }}</text></view>
              <view v-if="r.address" class="cand-desc">{{ r.address }}</view>
            </view>
            <button class="btn small pick" :class="{ ghost: picked?.id !== r.id }">{{ picked?.id === r.id ? "✓ 已选择" : "选择替换" }}</button>
          </view>
        </scroll-view>

        <!-- 右：替换前后 -->
        <scroll-view scroll-y class="col right">
          <view class="box-title">替换后行程预览</view>
          <view class="pv">
            <view class="pv-label">原行程</view>
            <view class="pv-item">
              <image v-if="item.photo" class="pv-img" :src="item.photo" mode="aspectFill" />
              <view><view class="pv-time">{{ item.start }} – {{ item.end }}</view><view class="pv-name">{{ item.name }}</view></view>
            </view>
            <view class="pv-arrow"><Icon name="down" :size="20" color="var(--accent)" /></view>
            <view class="pv-label after">替换后</view>
            <view class="pv-item">
              <image v-if="picked?.photo && !broken[picked.id]" class="pv-img" :src="picked.photo" mode="aspectFill" />
              <view><view class="pv-time">{{ item.start }} – {{ item.end }}（不变）</view><view class="pv-name">{{ picked?.name || "请选择一个地点" }}</view></view>
            </view>
          </view>
          <view class="impact">
            <view class="box-title">行程影响说明</view>
            <view class="im"><Icon name="checkCircle" :size="16" color="var(--ok)" /><text class="im-k">时间安排</text><text class="muted">时间段不变</text></view>
            <view class="im"><Icon name="checkCircle" :size="16" color="var(--ok)" /><text class="im-k">交通路线</text><text class="muted">保存后按高德重新计算</text></view>
            <view v-if="picked?.distance != null" class="im"><Icon name="checkCircle" :size="16" color="var(--ok)" /><text class="im-k">位置</text><text class="muted">离原地点 {{ distanceText(picked.distance) }}</text></view>
            <view v-if="picked?.cost && item.cost" class="im"><Icon name="checkCircle" :size="16" color="var(--ok)" /><text class="im-k">花费</text><text class="muted">人均 ¥{{ item.cost }} → ¥{{ picked.cost }}</text></view>
            <view class="im"><Icon name="checkCircle" :size="16" color="var(--ok)" /><text class="im-k">版本</text><text class="muted">存成新版本，旧版本可回看</text></view>
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
import { computed, onMounted, reactive, ref } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { ChangeReason, ItineraryItem, Place, TripDetail } from "../api/types";
import Icon from "./Icon.vue";
import { distanceText, mapUrl } from "../utils/place";

/**
 * 替换弹窗（电脑行程编辑页）：左边当前项、原因和筛选，中间高德候选，右边替换前后对比。
 * 确认后直接存成新版本，把新的行程交给页面。
 */
const props = defineProps<{ tripId: number; item: ItineraryItem; dayNo: number; reason?: ChangeReason | null }>();
const emit = defineEmits<{ (e: "close"): void; (e: "saved", detail: TripDetail): void }>();

const KIND: Record<string, string> = { SPOT: "景点", FOOD: "餐厅", HOTEL: "酒店" };
const PREFS: Record<string, string[]> = {
  SPOT: ["不限", "博物馆", "公园", "寺庙", "古镇", "历史文化", "亲子", "夜景"],
  FOOD: ["不限", "本地菜", "小吃", "火锅", "面馆", "清真", "西餐"],
  HOTEL: ["不限", "经济型", "四星", "五星", "民宿"],
};
const REASONS: { label: string; value: ChangeReason | null }[] = [
  { label: "去过了", value: "VISITED" },
  { label: "不感兴趣", value: "DISLIKE" },
  { label: "只是想换", value: null },
];
const RANGES = [1, 3, 5, 10];

const why = ref<ChangeReason | null>(props.reason ?? null);
const keyword = ref("");
const range = ref(10);
const list = ref<Place[]>([]);
const picked = ref<Place | null>(null);
const loading = ref(false);
const saving = ref(false);
const error = ref("");
/** 加载失败的高德照片 */
const broken = reactive<Record<string, boolean>>({});

const prefOptions = computed(() => PREFS[props.item.type] || PREFS.SPOT);
/** 按范围筛；没有距离（按城市搜的）的都留着 */
const shown = computed(() => list.value.filter((p) => p.distance == null || p.distance <= range.value * 1000).slice(0, 10));
const nearMap = computed(() => {
  if (props.item.lng == null || props.item.lat == null) return "";
  const others = shown.value.filter((p) => p.lng != null && p.lat != null).map((p) => [p.lng!, p.lat!]);
  return mapUrl({ stops: [[props.item.lng, props.item.lat]], others, w: 300, h: 200 });
});

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    list.value = await api.alternatives(props.tripId, props.item.id, keyword.value.trim() || undefined);
    picked.value = shown.value[0] || null;
  } catch (e) {
    list.value = [];
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function pickPref(p: string) {
  keyword.value = p === "不限" ? "" : p;
  load();
}

/** "09:00"–"11:30" 算出"约 2.5 小时"。 */
function duration(it: ItineraryItem) {
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
    const detail = await api.replaceItem(props.tripId, props.item.id, {
      name: p.name, poiId: p.id, address: p.address, lng: p.lng, lat: p.lat, photo: p.photo, rating: p.rating, cost: p.cost,
    }, why.value);
    emit("saved", detail);
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
.modal { width: min(1200px, 100%); height: min(860px, 100%); background: var(--bg); border-radius: 18px; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 24px 60px rgba(0, 0, 0, 0.3); }
.m-head { display: flex; justify-content: space-between; align-items: flex-start; padding: 20px 24px 14px; background: var(--surface); }
.m-title { font-size: 22px; font-weight: 800; }
.m-sub { font-size: 13px; color: var(--muted); margin-top: 2px; }
.m-close { cursor: pointer; padding: 4px; }
.cols { flex: 1; min-height: 0; display: grid; grid-template-columns: 300px minmax(0, 1fr) 340px; gap: 14px; padding: 14px; }
.col { height: 100%; }
.box { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; margin-bottom: 12px; }
.box-title { font-weight: 700; font-size: 15px; margin-bottom: 10px; }
.current { display: flex; gap: 10px; }
.cur-img { width: 110px; height: 96px; border-radius: 10px; flex-shrink: 0; }
.ph { display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #93c5fd, #2563eb); }
.cur-time { font-size: 12px; color: var(--muted); }
.day-chip { font-size: 11px; padding: 1px 6px; border-radius: 4px; border: 1px solid var(--accent); color: var(--accent); }
.cur-name { font-weight: 800; margin-top: 4px; }
.cur-note { font-size: 12px; color: var(--muted); margin-top: 2px; }
.facts { display: flex; flex-direction: column; gap: 4px; font-size: 13px; margin-top: 10px; color: var(--ink); }
.facts view { display: flex; align-items: center; gap: 6px; }
.search { display: flex; align-items: center; gap: 6px; height: 34px; padding: 0 10px; border-radius: 8px; background: var(--surface-2); margin-bottom: 10px; }
.search-input { flex: 1; font-size: 13px; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip-opt { font-size: 13px; padding: 5px 12px; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
.chip-opt.on { background: var(--accent); color: #fff; }
.range { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
.range.three { grid-template-columns: repeat(3, 1fr); }
.range-opt { text-align: center; font-size: 13px; padding: 5px 0; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
.range-opt.on { background: var(--accent-soft); color: var(--accent); font-weight: 700; }
.why-note { margin-top: 8px; }
.near { margin-top: 10px; border-radius: 12px; overflow: hidden; height: 200px; }
.near-map { width: 100%; height: 100%; }
.near-map.empty { display: flex; align-items: center; justify-content: center; background: var(--surface-2); }
.mid { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; box-sizing: border-box; }
.mid-head { display: flex; align-items: baseline; gap: 4px; }
.state { padding: 40px 0; text-align: center; font-size: 14px; }
.small { font-size: 12px; }
.cand { display: flex; gap: 12px; align-items: center; padding: 10px; border: 1px solid var(--line); border-radius: 14px; margin-bottom: 10px; cursor: pointer; }
.cand.picked { border: 2px solid var(--accent); background: var(--accent-soft); }
.cand-img-wrap { position: relative; flex-shrink: 0; }
.cand-img { width: 130px; height: 96px; border-radius: 10px; }
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
.right { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; box-sizing: border-box; }
.pv { background: var(--surface-2); border-radius: 12px; padding: 12px; }
.pv-label { font-weight: 700; font-size: 13px; margin-bottom: 6px; }
.pv-label.after { color: var(--ok); }
.pv-item { display: flex; gap: 10px; align-items: center; background: var(--surface); border-radius: 10px; padding: 8px; }
.pv-img { width: 72px; height: 56px; border-radius: 8px; flex-shrink: 0; }
.pv-time { font-size: 12px; color: var(--muted); }
.pv-name { font-weight: 700; }
.pv-arrow { display: flex; justify-content: center; padding: 6px 0; }
.impact { margin-top: 14px; }
.im { display: flex; align-items: center; gap: 6px; font-size: 13px; margin-bottom: 6px; }
.im-k { font-weight: 600; }
.confirm { width: 100%; margin: 12px 0 8px; height: 44px; }
.cancel { width: 100%; margin: 0 0 8px; }
</style>
