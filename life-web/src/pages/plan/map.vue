<template>
  <Shell active="map">
  <view class="page">
    <view v-if="!tripId" class="empty">
      <Icon name="nav" :size="34" color="var(--muted)" />
      <view>还没有行程路线</view>
      <button class="btn small" @click="toChat">先在对话里生成一个行程</button>
    </view>
    <template v-else>
      <view class="head">
        <text class="title">第{{ dayNo }}天<text v-if="date"> · {{ date }}</text></text>
        <text v-if="day?.theme" class="tag">{{ day.theme }}</text>
      </view>
      <scroll-view v-if="dayCount > 1" scroll-x class="days">
        <view class="days-inner">
          <text v-for="n in dayCount" :key="n" class="day" :class="{ on: n === dayNo }" @click="dayNo = n">D{{ n }}</text>
        </view>
      </scroll-view>
      <view class="layout">
        <view class="stops">
          <view v-if="!stops.length" class="none muted">{{ loading ? "正在补坐标…" : "这一天的地点在高德上没找到坐标" }}</view>
          <view v-for="(s, i) in stops" :key="s.id" class="stop">
            <view class="no">{{ i + 1 }}</view>
            <view class="stop-body">
              <view class="name">{{ s.name }}</view>
              <view class="time">{{ s.start }}<text v-if="s.end"> - {{ s.end }}</text></view>
              <view v-if="legFrom(day, s.id)" class="next">
                <Icon :name="legFrom(day, s.id)!.mode === 'WALK' ? 'users' : 'car'" :size="12" color="var(--muted)" /> 到下一站 {{ legText(legFrom(day, s.id)) }}
                <text class="nav-link" @click="navigate(s, stops[i + 1], legFrom(day, s.id)!.mode === 'WALK' ? 'walk' : 'car')">导航</text>
              </view>
            </view>
            <image v-if="s.photo && !broken[s.id]" class="thumb" @error="broken[s.id] = true" :src="s.photo" mode="aspectFill" />
          </view>
        </view>
        <view class="map-wrap">
          <image v-if="map" class="map" :src="map" mode="widthFix" />
          <view v-else class="map blank" />
        </view>
      </view>
      <view class="summary">
        <text>全天路上约 <text class="strong">{{ travel.minutes ? duration(travel.minutes) : "—" }}</text></text>
        <text>共 <text class="strong">{{ travel.km || 0 }} 公里</text></text>
      </view>
      <view class="actions">
        <view class="action" @click="edit"><Icon name="route" :size="16" color="var(--accent)" /><text>调整顺序</text></view>
        <view class="action" @click="navigateAll('bus')"><Icon name="bus" :size="16" color="var(--accent)" /><text>公共交通</text></view>
        <view class="action" @click="navigateAll('car')"><Icon name="car" :size="16" color="var(--accent)" /><text>驾车导航</text></view>
      </view>
      <view class="note">地图、路程来自高德，路程按打车估算（1 公里内按步行）；导航会打开高德地图。</view>
    </template>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { ItineraryItem, TripDetail } from "../../api/types";
import Icon from "../../components/Icon.vue";
import { openUrl } from "../../utils/format";
import { dayMapUrl, dayTravel, duration, legFrom, legText, located } from "../../utils/place";

/** 地图路线（原型⑥）：当天有坐标的站点、各段路程和路线地图（高德）。参数 ?id=行程&day=第几天 */
const tripId = ref(0);
const dayNo = ref(1);
const detail = ref<TripDetail | null>(null);
const loading = ref(false);
/** 加载失败的高德照片 */
const broken = reactive<Record<string, boolean>>({});

const day = computed(() => detail.value?.version.itinerary?.days[dayNo.value - 1]);
const dayCount = computed(() => detail.value?.version.itinerary?.days.length || 0);
const date = computed(() => (day.value ? `${Number(day.value.date.slice(5, 7))}月${Number(day.value.date.slice(8, 10))}日` : ""));
const stops = computed(() => located(day.value));
const map = computed(() => dayMapUrl(day.value, 600, 700));
const travel = computed(() => dayTravel(day.value));

onLoad(async (query) => {
  tripId.value = Number(query?.id) || 0;
  dayNo.value = Number(query?.day) || 1;
  if (!tripId.value) return;
  loading.value = true;
  try {
    detail.value = await api.trip(tripId.value);
    if (detail.value.version.itinerary && !detail.value.version.itinerary.geo) detail.value = await api.tripGeo(tripId.value);
    dayNo.value = Math.min(dayNo.value, dayCount.value) || 1;
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
});

/** 用高德网页版导航（手机上会尝试唤起高德 App） */
function navigate(a: ItineraryItem, b: ItineraryItem | undefined, mode: "car" | "bus" | "walk") {
  if (!b) return;
  const p = (x: ItineraryItem) => `${x.lng},${x.lat},${encodeURIComponent(x.name)}`;
  openUrl(`https://uri.amap.com/navigation?from=${p(a)}&to=${p(b)}&mode=${mode}&coordinate=gaode&callnative=1`);
}

function navigateAll(mode: "car" | "bus") {
  if (stops.value.length < 2) {
    uni.showToast({ title: "这一天不到两个有坐标的地点", icon: "none" });
    return;
  }
  navigate(stops.value[0], stops.value[stops.value.length - 1], mode);
}

function edit() {
  uni.navigateTo({ url: `/pages/plan/edit?id=${tripId.value}&day=${dayNo.value}` });
}

function toChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.title { font-size: 18px; font-weight: 700; color: var(--accent); }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.days { white-space: nowrap; margin-bottom: 12px; }
.days-inner { display: inline-flex; gap: 8px; }
.day { padding: 4px 14px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); font-size: 13px; cursor: pointer; }
.day.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 80px 0; }
.empty .btn { margin: 0; }
.none { padding: 20px 0; font-size: 13px; text-align: center; }
.nav-link { color: var(--accent); margin-left: 6px; cursor: pointer; white-space: nowrap; }
.map.blank { height: 360px; background: var(--surface-2); }
.summary { display: flex; justify-content: center; gap: 18px; margin-top: 12px; font-size: 14px; }
.strong { font-weight: 700; color: var(--accent); }
.layout { display: flex; flex-direction: column; gap: 12px; }
.stops { background: var(--surface); border-radius: 14px; padding: 4px 12px; border: 1px solid var(--line); }
.stop { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--line); }
.stop:last-child { border-bottom: none; }
.no {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ok);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stop-body { flex: 1; min-width: 0; }
.name { font-weight: 600; }
.time, .next { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.next { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.thumb { width: 64px; height: 48px; border-radius: 8px; flex-shrink: 0; }
.map-wrap { position: relative; border-radius: 14px; overflow: hidden; border: 1px solid var(--line); }
.map { width: 100%; display: block; }
.map-tools { position: absolute; right: 10px; top: 10px; display: flex; flex-direction: column; gap: 8px; }
.tool { width: 34px; height: 34px; border-radius: 8px; background: #fff; display: flex; align-items: center; justify-content: center; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12); }
.actions { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 12px; }
.action {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 0;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  font-size: 13px;
  cursor: pointer;
}
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
@media (min-width: 700px) {
  .layout { flex-direction: row; }
  .stops { width: 300px; flex-shrink: 0; align-self: flex-start; }
  .map-wrap { flex: 1; }
}
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
