<template>
  <view class="rmap" :style="{ aspectRatio: `${w} / ${h}` }">
    <!-- 能加载高德 JS 地图时用真地图（可拖动、缩放），否则用静态图片加叠加的标记 -->
    <view v-if="js" :id="domId" class="rmap-js" />
    <template v-else>
    <image v-if="url" class="rmap-img" :src="url" mode="scaleToFill" @load="loading = false" @error="loading = false" />
    <view v-if="!url || loading" class="rmap-state"><text>{{ stops.length || pois?.length ? "地图加载中…" : emptyText }}</text></view>
    <view
      v-for="m in markers"
      :key="m.id"
      class="pin"
      :class="{ on: m.id === activeId }"
      :style="{ left: m.pos.x + '%', top: m.pos.y + '%', '--pin': '#' + color }"
      @click.stop="emit('pick', m.id)"
    >
      <text class="pin-no">{{ m.n }}</text>
      <text v-if="m.id === activeId || markers.length <= 6" class="pin-name">{{ m.name }}</text>
    </view>
    <view
      v-for="m in poiMarkers"
      :key="'p-' + m.id"
      class="pin poi"
      :class="{ on: m.id === activePoi }"
      :style="{ left: m.pos.x + '%', top: m.pos.y + '%' }"
      @click.stop="emit('poi', m.id)"
    >
      <text class="poi-no">{{ m.label }}</text>
      <text v-if="m.id === activePoi" class="pin-name">{{ m.name }}</text>
    </view>
    </template>
    <view class="tools">
      <view class="tool" title="放大" @click="zoomBy(1)"><Icon name="plus" :size="16" color="var(--ink)" /></view>
      <view class="tool" title="缩小" @click="zoomBy(-1)"><text class="minus">−</text></view>
      <view class="tool" title="看全部" @click="fitAll"><Icon name="locate" :size="16" color="var(--ink)" /></view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api } from "../api";
import { loadAmap } from "../utils/amapjs";
import Icon from "./Icon.vue";
import { fitView, mapUrl, projectPct, type MapLine, type MapStop } from "../utils/place";

/**
 * 可点的路线地图：底图和路线是高德静态地图图片，编号标记由页面按同样的投影叠在图上，
 * 所以能点。点标记发 pick；父组件调 focus(id) 让地图以那一项为中心放大。放大、缩小、看全部都会换一张图。
 * pois 是另外叠加的候选地点（如餐厅），用字母标、点了发 poi，不连进路线。
 */
const props = withDefaults(defineProps<{ stops: MapStop[]; color?: string; lines?: MapLine[]; activeId?: string | null; pois?: (MapStop & { label: string })[]; activePoi?: string | null; route?: boolean; w?: number; h?: number; emptyText?: string }>(), {
  emptyText: "这一天的地点还没有坐标",
  route: true,
  color: "2563EB",
  lines: () => [],
  activeId: null,
  pois: () => [],
  activePoi: null,
  w: 640,
  h: 520,
});
const emit = defineEmits<{ (e: "pick", id: string): void; (e: "poi", id: string): void }>();

/** 用户手动调过的视野；为空时自动缩放到能看见所有点 */
const view = ref<{ center: number[]; zoom: number } | null>(null);
const loading = ref(true);

const fit = computed(() =>
  fitView([...props.stops.map((s) => [s.lng, s.lat]), ...props.pois.map((s) => [s.lng, s.lat]), ...props.lines.flatMap((l) => l.points)], props.w, props.h),
);
const current = computed(() => view.value || fit.value);
const url = computed(() => {
  const c = current.value;
  if (!c) return "";
  const stops = props.stops.map((s) => [s.lng, s.lat]);
  return mapUrl({ stops, route: props.route && stops.length > 1, color: props.color, lines: props.lines, center: c.center, zoom: c.zoom, marks: false, w: props.w, h: props.h });
});
const markers = computed(() => {
  const c = current.value;
  if (!c) return [];
  return props.stops
    .map((s, i) => ({ ...s, n: i + 1, pos: projectPct([s.lng, s.lat], c.center, c.zoom, props.w, props.h) }))
    .filter((m): m is MapStop & { n: number; pos: { x: number; y: number } } => m.pos != null);
});

const poiMarkers = computed(() => {
  const c = current.value;
  if (!c) return [];
  return props.pois
    .map((s) => ({ ...s, pos: projectPct([s.lng, s.lat], c.center, c.zoom, props.w, props.h) }))
    .filter((m): m is MapStop & { label: string; pos: { x: number; y: number } } => m.pos != null);
});

watch(url, () => (loading.value = true));
// 换了一天（站点变了）就回到看全部
watch(() => props.stops.map((s) => s.id).join(","), () => (view.value = null));

function zoomBy(d: number) {
  if (map) {
    d > 0 ? map.zoomIn() : map.zoomOut();
    return;
  }
  const c = current.value;
  if (!c) return;
  view.value = { center: c.center, zoom: Math.min(17, Math.max(4, c.zoom + d)) };
}

/** 回到能看见所有点 */
function fitAll() {
  if (map) {
    const pts = [...props.stops, ...props.pois].map((s) => [s.lng, s.lat]);
    if (pts.length) setBounds(pts);
    return;
  }
  view.value = null;
}

/** 以某一项（或某个候选地点）为中心放大到街道级别 */
function focus(id: string) {
  const s = props.stops.find((x) => x.id === id) || props.pois.find((x) => x.id === id);
  if (!s) return;
  if (map) {
    map.setZoomAndCenter(Math.max(15, map.getZoom()), [s.lng, s.lat], false, 300);
    return;
  }
  view.value = { center: [s.lng, s.lat], zoom: Math.max(15, current.value?.zoom || 15) };
}

/** 缩放到能看见这些点（经度,纬度）；"看全部"会回到全部 */
function fitTo(points: number[][]) {
  if (map) {
    if (points.length) setBounds(points);
    return;
  }
  const v = points.length ? fitView(points, props.w, props.h) : null;
  if (v) view.value = { center: v.center, zoom: v.zoom };
}

// ---------- 高德 JS 地图 ----------
const js = ref(false);
const domId = "rmap-" + Math.random().toString(36).slice(2, 9);
let AMap: any = null;
let map: any = null;
let markerLayer: any[] = [];
let routeLayer: any[] = [];
let routeSeq = 0;

onMounted(async () => {
  AMap = await loadAmap();
  if (!AMap) return;
  js.value = true;
  await new Promise((r) => setTimeout(r, 0));
  try {
    map = new AMap.Map(domId, { zoom: 12, resizeEnable: true, dragEnable: true, scrollWheel: true, doubleClickZoom: true });
  } catch {
    js.value = false;
    return;
  }
  drawMarkers();
  drawRoutes();
  fitAll();
});

onBeforeUnmount(() => {
  map?.destroy();
  map = null;
});

function setBounds(points: number[][]) {
  if (!map || !points.length) return;
  if (points.length === 1) {
    map.setZoomAndCenter(15, points[0]);
    return;
  }
  const lngs = points.map((p) => p[0]);
  const lats = points.map((p) => p[1]);
  map.setBounds(new AMap.Bounds([Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]), false, [48, 48, 48, 48]);
}

/** 标记的样式：和静态地图上叠的标记一样（编号圆点、字母方块、名字标签），内联样式因为是高德插进去的 DOM */
function pinHtml(text: string, name: string, active: boolean, poi: boolean, showName: boolean) {
  const bg = poi ? (active ? "#be123c" : "#e8590c") : active ? "#e8590c" : "#" + props.color;
  const size = poi ? 20 : 24;
  const scale = active ? "transform:scale(1.25);" : "";
  const label = showName
    ? `<span style="position:absolute;left:${size + 4}px;top:50%;transform:translateY(-50%);white-space:nowrap;font-size:12px;font-weight:700;color:${active ? "#e8590c" : "#1e293b"};background:rgba(255,255,255,.94);padding:2px 6px;border-radius:6px;box-shadow:0 1px 4px rgba(0,0,0,.15)">${escapeHtml(name)}</span>`
    : "";
  return `<div style="position:relative;cursor:pointer"><div style="width:${size}px;height:${size}px;border-radius:${poi ? 6 : 12}px;background:${bg};color:#fff;font:800 ${poi ? 11 : 12}px/1 sans-serif;display:flex;align-items:center;justify-content:center;border:2px solid #fff;box-sizing:border-box;box-shadow:0 2px 6px rgba(0,0,0,.3);${scale}">${escapeHtml(text)}</div>${label}</div>`;
}

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

function drawMarkers() {
  if (!map) return;
  map.remove(markerLayer);
  markerLayer = [];
  props.stops.forEach((s, i) => {
    const active = s.id === props.activeId;
    const m = new AMap.Marker({ position: [s.lng, s.lat], content: pinHtml(String(i + 1), s.name, active, false, active || props.stops.length <= 6), offset: new AMap.Pixel(-12, -12), zIndex: active ? 130 : 110, title: s.name });
    m.on("click", () => emit("pick", s.id));
    markerLayer.push(m);
  });
  props.pois.forEach((s) => {
    const active = s.id === props.activePoi;
    const m = new AMap.Marker({ position: [s.lng, s.lat], content: pinHtml(s.label, s.name, active, true, active), offset: new AMap.Pixel(-10, -10), zIndex: active ? 140 : 120, title: s.name });
    m.on("click", () => emit("poi", s.id));
    markerLayer.push(m);
  });
  map.add(markerLayer);
}

/** 路线：当天按高德实际路线画（后端查，近的直线），其他几天画虚线 */
async function drawRoutes() {
  if (!map) return;
  const mine = ++routeSeq;
  map.remove(routeLayer);
  routeLayer = [];
  for (const l of props.lines) {
    if (l.points.length > 1) routeLayer.push(new AMap.Polyline({ path: l.points, strokeColor: "#" + l.color, strokeWeight: 4, strokeOpacity: 0.6, strokeStyle: "dashed", zIndex: 50 }));
  }
  map.add(routeLayer);
  if (!props.route || props.stops.length < 2) return;
  let segments: number[][][];
  try {
    segments = await api.mapPath(props.stops.map((s) => [s.lng, s.lat]));
  } catch {
    segments = props.stops.slice(1).map((s, i) => [[props.stops[i].lng, props.stops[i].lat], [s.lng, s.lat]]);
  }
  if (mine !== routeSeq || !map) return;
  const line = new AMap.Polyline({ path: segments.flat(), strokeColor: "#" + props.color, strokeWeight: 6, strokeOpacity: 0.9, showDir: true, lineJoin: "round", zIndex: 60 });
  routeLayer.push(line);
  map.add(line);
}

watch(() => [props.activeId, props.activePoi, props.pois.map((p) => p.id + p.label).join(","), props.stops.map((s) => s.id + s.lng).join(",")].join("|"), drawMarkers);
watch(() => [props.stops.map((s) => `${s.lng},${s.lat}`).join(";"), props.lines.map((l) => l.color + l.points.length).join(","), props.route, props.color].join("|"), () => {
  drawRoutes();
});
// 换了一天（站点变了）就回到看全部
watch(() => props.stops.map((s) => s.id).join(","), () => map && fitAll());

defineExpose({ focus, fitTo });
</script>

<style scoped>
.rmap { position: relative; width: 100%; border-radius: 14px; overflow: hidden; background: var(--surface-2); }
.rmap-img { position: absolute; inset: 0; width: 100%; height: 100%; }
.rmap-js { position: absolute; inset: 0; width: 100%; height: 100%; }
.rmap-state { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 13px; color: var(--muted); pointer-events: none; }
.pin { position: absolute; transform: translate(-50%, -50%); display: flex; align-items: center; gap: 4px; cursor: pointer; z-index: 1; }
.pin.on { z-index: 2; }
.pin-no {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--pin);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #fff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
  transition: transform 0.15s;
}
.pin.on .pin-no { transform: scale(1.3); background: #e8590c; }
.pin-name {
  position: absolute;
  left: 28px;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 700;
  color: #1e293b;
  background: rgba(255, 255, 255, 0.92);
  padding: 2px 6px;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.15);
}
.pin.on .pin-name { color: #e8590c; }
.poi-no {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: #e8590c;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #fff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
  transition: transform 0.15s;
}
.pin.poi.on { z-index: 3; }
.pin.poi.on .poi-no { transform: scale(1.3); background: #be123c; }
.pin.poi.on .pin-name { color: #be123c; }
.tools { position: absolute; right: 10px; bottom: 10px; display: flex; flex-direction: column; gap: 6px; z-index: 3; }
.tool { width: 34px; height: 34px; border-radius: 8px; background: var(--surface); display: flex; align-items: center; justify-content: center; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15); cursor: pointer; }
.minus { font-size: 20px; line-height: 1; color: var(--ink); }
@media (prefers-reduced-motion: reduce) {
  .pin-no, .poi-no { transition: none; }
}
</style>
