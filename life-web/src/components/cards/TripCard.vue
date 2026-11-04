<template>
  <view class="xcard">
    <view class="xcard-head">
      <text class="xcard-title">{{ data.title }}</text>
      <text class="chip" :class="data.status === 'DRAFT' ? '' : 'ok'">{{ data.status === "DRAFT" ? "草稿" : "已确认" }}</text>
    </view>
    <view class="trip-meta muted">
      {{ data.destination }} · {{ shortDate(data.startDate) }}–{{ shortDate(data.endDate) }} · {{ data.days.length }} 天 · 第 {{ data.version }} 版
    </view>
    <view v-for="(d, i) in live.days" :key="d.date" class="xcard-row day">
      <view class="day-head">
        <text class="day-no">D{{ i + 1 }}</text>
        <text class="muted">{{ shortDate(d.date) }} {{ weekday(d.date) }}</text>
        <text v-if="d.theme" class="day-theme">{{ d.theme }}</text>
      </view>
      <view class="day-items">{{ d.items.filter((x: any) => x.type !== "TRANSPORT").map((x: any) => x.name).join(" → ") }}</view>
    </view>
    <view v-for="f in flights" :key="f.key" class="xcard-row stay">
      <text class="day-no">飞</text>
      <text class="stay-name">{{ f.name }}</text>
      <text class="muted">{{ shortDate(f.date) }} {{ f.time }}</text>
      <text v-if="f.price" class="stay-price">¥{{ f.price }}<text v-if="f.platform"> · {{ f.platform }}</text></text>
      <text v-if="f.checking" class="checking">比价中…</text>
    </view>
    <view v-for="st in live.stays || []" :key="st.id" class="xcard-row stay">
      <text class="day-no">住</text>
      <text class="stay-name">{{ st.name }}</text>
      <text class="muted">{{ shortDate(st.checkIn) }}–{{ shortDate(st.checkOut) }}</text>
      <text v-if="st.price" class="stay-price">¥{{ st.price }}/晚 · {{ st.platform }}</text>
      <text v-if="stayChecking(live, st)" class="checking">比价中…</text>
    </view>
    <view v-if="flights.length || (live.stays || []).length" class="muted hint">不满意直接说，或者点开行程换酒店、换航班</view>
    <view class="trip-actions">
      <button class="btn small" @click="openTrip">查看详情</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onUnmounted, reactive } from "vue";
import { api } from "../../api";
import { shortDate, weekday } from "../../utils/format";
import { anyChecking, flightChecking, flightNoOf, pollWhileChecking, stayChecking } from "../../utils/priceCheck";

/**
 * 行程卡：saveTripPlan 保存后的摘要，每天一行地点串，下面是航班和住宿的价格，点开进行程详情。
 * 酒店、航班其他平台的价格还在后台比时显示"比价中"，隔几秒刷新一次，补完换成最新价格。
 */
const props = defineProps<{ data: any }>();

/** 卡片上显示的内容：先用卡片自带的，后台补完价格后换成行程当前版本的 */
const live = reactive({ days: props.data.days || [], stays: props.data.stays || [], checking: props.data.checking || null });

/** 行程里的航班：名称、日期、起降时间、价格；价格说明开头是"平台 ¥价格" */
const flights = computed(() => {
  const out: { key: string; name: string; date: string; time: string; price?: number; platform?: string; checking: boolean }[] = [];
  for (const d of live.days) {
    for (const it of d.items) {
      const no = flightNoOf(it);
      if (!no) continue;
      out.push({
        key: `${no}|${d.date}`, name: it.name, date: d.date, time: `${it.start || ""}${it.end ? "–" + it.end : ""}`,
        price: it.cost, platform: (it.note || "").match(/^(\S+) ¥\d+，/)?.[1], checking: flightChecking(live, it, d.date),
      });
    }
  }
  return out;
});

/** 读这个版本的最新价格，返回是否不用再刷新了；行程已经有了新版本就不跟着变，免得卡片和这次保存的对不上 */
async function refresh(): Promise<boolean> {
  const d = await api.trip(props.data.tripId, props.data.version);
  if (!d.version.itinerary) return true;
  live.days = d.version.itinerary.days;
  live.stays = d.version.itinerary.stays || [];
  live.checking = d.version.itinerary.checking || null;
  return !anyChecking(live) || d.trip.currentVer !== props.data.version;
}

// 保存时有后台补比价的卡片（翻历史时也一样）：先读一次最新价格，还在比就隔几秒再读
let stop: (() => void) | null = null;
let gone = false;
if (props.data.checking) {
  refresh().then((done) => {
    if (!done && !gone) stop = pollWhileChecking(refresh);
  }).catch(() => {});
}
onUnmounted(() => {
  gone = true;
  stop?.();
});

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
.checking { color: var(--accent); font-size: 12px; }
.hint { font-size: 12px; padding: 6px 14px 0; }
.trip-actions { padding: 10px 14px 12px; display: flex; justify-content: flex-end; }
.trip-actions .btn { margin: 0; }
</style>
