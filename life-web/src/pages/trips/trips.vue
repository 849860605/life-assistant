<template>
  <view class="container">
    <view v-if="loaded && !trips.length" class="empty">
      还没有保存的行程。<br />在对话里规划好后说"保存"，就会出现在这里。
    </view>
    <view class="list">
      <view v-for="t in trips" :key="t.id" class="card trip" @click="openTrip(t)">
        <view class="trip-head">
          <text class="trip-title">{{ t.title }}</text>
          <text class="chip" :class="t.status === 'CONFIRMED' ? 'travel' : ''">{{ STATUS[t.status] }}</text>
        </view>
        <view class="trip-meta">
          <text>{{ TYPE[t.tripType] }}</text>
          <text v-if="t.destination">· {{ t.destination }}</text>
          <text v-if="t.startDate">· {{ dateRange(t) }}</text>
        </view>
        <view class="trip-foot muted">第 {{ t.currentVer }} 版 · {{ relativeTime(t.updatedAt) }}更新</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { Trip } from "../../api/types";
import { relativeTime } from "../../utils/format";

const STATUS: Record<Trip["status"], string> = { DRAFT: "草稿", CONFIRMED: "已确认", FINISHED: "已结束" };
const TYPE: Record<Trip["tripType"], string> = { CITY: "城市游", HIKING: "爬山徒步", MIXED: "综合" };

const trips = ref<Trip[]>([]);
const loaded = ref(false);

/** 每次切到这个页面都刷新，对话里刚保存的行程能马上看到。 */
onShow(async () => {
  try {
    trips.value = await api.trips();
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
});

/** 日期范围：同一天只显示一个日期。 */
function dateRange(t: Trip) {
  const short = (d: string) => d.slice(5).replace("-", "/");
  if (!t.endDate || t.endDate === t.startDate) return short(t.startDate!);
  return `${short(t.startDate!)} – ${short(t.endDate)}`;
}

function openTrip(t: Trip) {
  uni.navigateTo({ url: `/pages/trips/detail?id=${t.id}` });
}
</script>

<style scoped>
.list { display: flex; flex-direction: column; gap: 12px; }
.trip { cursor: pointer; }
.trip-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.trip-title { font-size: 16px; font-weight: 600; }
.trip-meta { margin-top: 6px; font-size: 14px; display: flex; flex-wrap: wrap; gap: 4px; }
.trip-foot { margin-top: 8px; font-size: 12px; font-variant-numeric: tabular-nums; }
</style>
