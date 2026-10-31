<template>
  <view>
    <view class="search">
      <view class="row">
        <view class="field grow">
          <Icon name="pin" :size="14" color="var(--muted)" />
          <input id="hotel-city" v-model="city" class="input city" placeholder="城市" />
          <text class="sep">·</text>
          <input id="hotel-place" v-model="place" class="input" placeholder="附近位置（可选），如 钟楼" />
        </view>
      </view>
      <view class="row">
        <picker mode="date" :value="checkIn" :start="today" @change="setCheckIn($event.detail.value)">
          <view class="field"><Icon name="calendar" :size="14" color="var(--muted)" /><text>{{ shortDate(checkIn) }} 入住</text></view>
        </picker>
        <picker mode="date" :value="checkOut" :start="checkIn" @change="checkOut = $event.detail.value">
          <view class="field"><Icon name="calendar" :size="14" color="var(--muted)" /><text>{{ shortDate(checkOut) }} 离店 · {{ nights }}晚</text></view>
        </picker>
        <view class="field">
          <text class="muted">¥</text>
          <input id="hotel-max" v-model="maxPrice" class="input" type="number" placeholder="每晚预算（可选）" />
        </view>
      </view>
      <button class="btn search-btn" :disabled="loading" @click="search"><Icon name="search" :size="16" color="#fff" /><text>{{ loading ? "比价中…" : "找酒店并比价" }}</text></button>
    </view>

    <view v-if="loading" class="loading">正在用 RollingGo 找酒店，并比较飞猪、携程、美团的价格，约 20–60 秒…</view>
    <view v-else-if="error" class="error">{{ error }}</view>
    <view v-else-if="result" class="result">
      <HotelCard v-if="result.results?.length" :data="result" />
      <view v-else class="error">没找到符合条件的酒店，放宽预算或换个位置再试</view>
      <view v-if="result.notConfigured?.length" class="foot">未配置的平台：{{ result.notConfigured.join("、") }}</view>
    </view>
    <view v-else class="foot">"推荐排序"按评分和距离，不看佣金；价格以预订页为准，部分链接含推广参数。</view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Icon from "./Icon.vue";
import HotelCard from "./cards/HotelCard.vue";
import { api } from "../api";
import { currentCity } from "../utils/place";
import { shortDate, ymd } from "../utils/format";

/**
 * 酒店比价（原型⑨）：按城市和位置找候选酒店，同时查 RollingGo、飞猪、携程、美团的每晚价格。
 * initial 是页面参数（city、place、checkIn、checkOut），齐全时打开就查。
 */
const props = defineProps<{ initial?: Record<string, string | undefined> }>();

const DAY = 86400000;
const today = ymd(new Date());
const city = ref(currentCity());
const place = ref("");
const checkIn = ref(ymd(new Date(Date.now() + 7 * DAY)));
const checkOut = ref(ymd(new Date(Date.now() + 8 * DAY)));
const maxPrice = ref("");
const loading = ref(false);
const error = ref("");
const result = ref<Record<string, any> | null>(null);
const nights = computed(() => Math.max(1, Math.round((Date.parse(checkOut.value) - Date.parse(checkIn.value)) / DAY)));

onMounted(() => {
  const q = props.initial || {};
  if (q.city) city.value = q.city;
  if (q.place) place.value = q.place;
  if (q.checkIn) checkIn.value = q.checkIn;
  if (q.checkOut) checkOut.value = q.checkOut;
  if (q.city && q.checkIn && q.checkOut) search();
});

/** 改入住日期时，离店日期不能早于入住，自动顺延一晚。 */
function setCheckIn(value: string) {
  checkIn.value = value;
  if (checkOut.value <= value) checkOut.value = ymd(new Date(Date.parse(value) + DAY));
}

async function search() {
  if (!city.value.trim()) {
    uni.showToast({ title: "填一下城市", icon: "none" });
    return;
  }
  loading.value = true;
  error.value = "";
  result.value = null;
  try {
    const data = await api.searchHotels({
      city: city.value.trim(),
      place: place.value.trim() || undefined,
      checkIn: checkIn.value,
      checkOut: checkOut.value,
      maxPrice: Number(maxPrice.value) || undefined,
    });
    if (data.error) error.value = data.error;
    else result.value = { ...data, queriedAt: new Date().toISOString() };
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.search { background: var(--surface); border-radius: 14px; padding: 14px; border: 1px solid var(--line); display: flex; flex-direction: column; gap: 8px; }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.row picker { flex: 1; min-width: 140px; }
.field { flex: 1; min-width: 140px; display: flex; align-items: center; gap: 4px; font-size: 13px; background: var(--surface-2); border-radius: 8px; padding: 8px 10px; cursor: pointer; }
.field.grow { flex: 1 1 100%; }
.input { flex: 1; min-width: 0; font-size: 13px; }
.input.city { flex: 0 0 72px; font-weight: 600; }
.sep { color: var(--muted); }
.search-btn { margin: 4px 0 0; width: 100%; gap: 6px; }
.loading, .error { font-size: 13px; color: var(--muted); text-align: center; padding: 24px 0; }
.error { color: #c2560c; }
.result { margin-top: 12px; }
.foot { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
</style>
