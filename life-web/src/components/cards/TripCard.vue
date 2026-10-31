<template>
  <view class="xcard">
    <view class="xcard-head">
      <text class="xcard-title">{{ data.title }}</text>
      <text class="chip" :class="data.status === 'DRAFT' ? '' : 'ok'">{{ data.status === "DRAFT" ? "草稿" : "已确认" }}</text>
    </view>
    <view class="trip-meta muted">
      {{ data.destination }} · {{ shortDate(data.startDate) }}–{{ shortDate(data.endDate) }} · {{ data.days.length }} 天 · 第 {{ data.version }} 版
    </view>
    <view v-for="(d, i) in data.days" :key="d.date" class="xcard-row day">
      <view class="day-head">
        <text class="day-no">D{{ i + 1 }}</text>
        <text class="muted">{{ shortDate(d.date) }} {{ weekday(d.date) }}</text>
        <text v-if="d.theme" class="day-theme">{{ d.theme }}</text>
      </view>
      <view class="day-items">{{ d.items.filter((x: any) => x.type !== "TRANSPORT").map((x: any) => x.name).join(" → ") }}</view>
    </view>
    <view v-for="st in data.stays || []" :key="st.id" class="xcard-row stay">
      <text class="day-no">住</text>
      <text class="stay-name">{{ st.name }}</text>
      <text class="muted">{{ shortDate(st.checkIn) }}–{{ shortDate(st.checkOut) }}</text>
      <text v-if="st.price" class="stay-price">¥{{ st.price }}/晚 · {{ st.platform }}</text>
    </view>
    <view class="trip-actions">
      <button class="btn small" @click="openTrip">查看详情</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { shortDate, weekday } from "../../utils/format";

/** 行程卡：saveTripPlan 保存后的摘要，每天一行地点串，点开进行程详情。 */
const props = defineProps<{ data: any }>();

function openTrip() {
  uni.navigateTo({ url: `/pages/trips/detail?id=${props.data.tripId}` });
}
</script>

<style scoped>
.trip-meta { font-size: 13px; padding: 8px 14px 0; font-variant-numeric: tabular-nums; }
.day { border-bottom: none; padding-bottom: 2px; }
.day-head { display: flex; align-items: baseline; gap: 8px; font-size: 13px; }
.day-no {
  font-weight: 600;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.day-theme { font-weight: 600; }
.day-items { font-size: 14px; margin-top: 2px; }
.stay { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; font-size: 13px; }
.stay-name { font-weight: 600; }
.stay-price { color: var(--price); font-weight: 600; font-variant-numeric: tabular-nums; }
.trip-actions { padding: 10px 14px 12px; display: flex; justify-content: flex-end; }
.trip-actions .btn { margin: 0; }
</style>
