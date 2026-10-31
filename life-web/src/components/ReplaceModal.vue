<template>
  <view class="overlay" @click.self="emit('close')">
    <view class="modal">
      <view class="m-head">
        <view>
          <view class="m-title">替换景点</view>
          <view class="m-sub">从附近或相似景点中选择一个替代，系统将自动调整后续行程与路线</view>
        </view>
        <view class="m-close" @click="emit('close')"><Icon name="close" :size="20" color="var(--ink)" /></view>
      </view>

      <view class="cols">
        <!-- 左：当前景点、偏好、范围 -->
        <scroll-view scroll-y class="col left">
          <view class="box">
            <view class="box-title">当前景点</view>
            <view class="current">
              <image v-if="imageFor(item.name)" class="cur-img" :src="imageFor(item.name)" mode="aspectFill" />
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
            <view class="box-title">替换偏好（可多选）</view>
            <view class="chips">
              <text v-for="p in PREFS" :key="p" class="chip-opt" :class="{ on: prefs.includes(p) }" @click="togglePref(p)">{{ p }}</text>
            </view>
          </view>
          <view class="box">
            <view class="box-title">位置范围</view>
            <view class="range">
              <text v-for="r in RANGES" :key="r" class="range-opt" :class="{ on: range === r }" @click="range = r">{{ r }}公里</text>
            </view>
            <view class="near">
              <image class="near-map" :src="NEAR_MAP" mode="aspectFill" />
              <view class="ring" :style="{ width: ringSize + 'px', height: ringSize + 'px' }" />
            </view>
          </view>
        </scroll-view>

        <!-- 中：候选 -->
        <scroll-view scroll-y class="col mid">
          <view class="mid-head">
            <text class="box-title">推荐替换景点</text>
            <text class="muted small">（基于位置、兴趣和相似类型）</text>
          </view>
          <view v-for="(r, i) in candidates" :key="r.name" class="cand" :class="{ picked: picked === r.name }" @click="picked = r.name">
            <view class="cand-img-wrap">
              <image class="cand-img" :src="r.image" mode="aspectFill" />
              <view class="cand-no">{{ i + 1 }}</view>
            </view>
            <view class="cand-body">
              <view class="cand-name">{{ r.name }}</view>
              <view class="tags"><text v-for="t in r.tags" :key="t" class="tag">{{ t }}</text></view>
              <view class="cand-meta">距离 {{ r.distance }} · {{ r.drive }}</view>
              <view class="cand-meta"><text class="star">★ {{ r.rating }}</text> 高德评分</view>
              <view class="cand-desc">{{ r.desc }}</view>
            </view>
            <button class="btn small pick" :class="{ ghost: picked !== r.name }">{{ picked === r.name ? "✓ 已选择" : "选择替换" }}</button>
          </view>
        </scroll-view>

        <!-- 右：替换前后 -->
        <scroll-view scroll-y class="col right">
          <view class="box-title">替换后行程预览</view>
          <view class="pv">
            <view class="pv-label">原行程</view>
            <view class="pv-item">
              <image v-if="imageFor(item.name)" class="pv-img" :src="imageFor(item.name)" mode="aspectFill" />
              <view><view class="pv-time">{{ item.start }} – {{ item.end }}</view><view class="pv-name">{{ item.name }}</view></view>
            </view>
            <view class="pv-arrow"><Icon name="down" :size="20" color="var(--accent)" /></view>
            <view class="pv-label after">替换后</view>
            <view class="pv-item">
              <image v-if="pickedRow" class="pv-img" :src="pickedRow.image" mode="aspectFill" />
              <view><view class="pv-time">{{ item.start }} – {{ item.end }}（不变）</view><view class="pv-name">{{ picked || "请选择一个景点" }}</view></view>
            </view>
          </view>
          <view class="impact">
            <view class="box-title">行程影响说明</view>
            <view v-for="im in IMPACT" :key="im[0]" class="im"><Icon name="checkCircle" :size="16" color="var(--ok)" /><text class="im-k">{{ im[0] }}</text><text class="muted">{{ im[1] }}</text></view>
          </view>
          <button class="btn confirm" :disabled="!picked" @click="confirm">确认替换并重新规划</button>
          <button class="btn ghost cancel" @click="emit('close')">取消</button>
          <view class="muted small center">系统将自动调整后续景点的交通时间和推荐顺序</view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import type { ItineraryItem } from "../api/types";
import Icon from "./Icon.vue";
import { imageFor, NEAR_MAP, REPLACEMENTS } from "../demo/xian";

/** 替换景点弹窗（电脑行程编辑页）：左边当前景点和筛选，中间候选，右边替换前后对比；确认后把选中的名字交给页面。 */
const props = defineProps<{ item: ItineraryItem; dayNo: number; initial?: string }>();
const emit = defineEmits<{ (e: "close"): void; (e: "confirm", name: string): void }>();

const PREFS = ["不限", "历史文化", "自然风光", "博物馆", "寺庙古建", "网红打卡", "亲子友好", "特色美食", "购物街区", "小众景点"];
const RANGES = [1, 3, 5, 10];
const IMPACT = [
  ["时间安排", "无需调整，时间段不变"],
  ["交通路线", "自动重新规划，后续景点顺序保持合理"],
  ["总里程", "基本不变"],
  ["总费用", "基本不变（门票费用相当）"],
];

const prefs = ref<string[]>(["不限"]);
const range = ref(5);
const candidates = computed(() => REPLACEMENTS.filter((r) => !props.item.name.includes(r.name.slice(0, 3))).slice(0, 5));
const picked = ref(props.initial || candidates.value[0]?.name || "");
const pickedRow = computed(() => REPLACEMENTS.find((r) => r.name === picked.value));
const ringSize = computed(() => 50 + range.value * 14);

function togglePref(p: string) {
  if (p === "不限") {
    prefs.value = ["不限"];
    return;
  }
  const list = prefs.value.filter((x) => x !== "不限");
  prefs.value = list.includes(p) ? list.filter((x) => x !== p) : [...list, p];
  if (!prefs.value.length) prefs.value = ["不限"];
}

/** "09:00"–"11:30" 算出"约 2.5 小时"。 */
function duration(it: ItineraryItem) {
  if (!it.start || !it.end) return "时长待定";
  const [h1, m1] = it.start.split(":").map(Number);
  const [h2, m2] = it.end.split(":").map(Number);
  const hours = (h2 * 60 + m2 - h1 * 60 - m1) / 60;
  return `约 ${Number.isInteger(hours) ? hours : hours.toFixed(1)} 小时`;
}

function confirm() {
  if (picked.value) emit("confirm", picked.value);
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
.cur-time { font-size: 12px; color: var(--muted); }
.day-chip { font-size: 11px; padding: 1px 6px; border-radius: 4px; border: 1px solid var(--accent); color: var(--accent); }
.cur-name { font-weight: 800; margin-top: 4px; }
.cur-note { font-size: 12px; color: var(--muted); margin-top: 2px; }
.facts { display: flex; flex-direction: column; gap: 4px; font-size: 13px; margin-top: 10px; color: var(--ink); }
.facts view { display: flex; align-items: center; gap: 6px; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip-opt { font-size: 13px; padding: 5px 12px; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
.chip-opt.on { background: var(--accent); color: #fff; }
.range { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
.range-opt { text-align: center; font-size: 13px; padding: 5px 0; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
.range-opt.on { background: var(--accent-soft); color: var(--accent); font-weight: 700; }
.near { position: relative; margin-top: 10px; border-radius: 12px; overflow: hidden; height: 200px; }
.near-map { width: 100%; height: 100%; }
.ring { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); border-radius: 50%; border: 2px solid rgba(37, 99, 235, 0.6); background: rgba(37, 99, 235, 0.12); }
.mid { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; box-sizing: border-box; }
.mid-head { display: flex; align-items: baseline; gap: 4px; }
.small { font-size: 12px; }
.cand { display: flex; gap: 12px; align-items: center; padding: 10px; border: 1px solid var(--line); border-radius: 14px; margin-bottom: 10px; cursor: pointer; }
.cand.picked { border: 2px solid var(--accent); background: var(--accent-soft); }
.cand-img-wrap { position: relative; flex-shrink: 0; }
.cand-img { width: 130px; height: 96px; border-radius: 10px; }
.cand-no { position: absolute; left: 6px; top: 6px; width: 20px; height: 20px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
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
.center { text-align: center; }
</style>
