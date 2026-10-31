<template>
  <view class="stay">
    <view class="stay-main">
      <image v-if="stay.photo && !broken" class="stay-img" :src="stay.photo" mode="aspectFill" @error="broken = true" />
      <view v-else class="stay-img ph"><Icon name="bed" :size="22" color="#fff" /></view>
      <view class="stay-body">
        <view class="stay-name" :class="{ link: stay.url }" @click="openUrl(stay.url)">{{ stay.name }}<text v-if="booked" class="booked-tag">已预约</text></view>
        <view class="stay-meta">
          <text v-if="stay.rating" class="star">★ {{ stay.rating }}</text>
          <text class="muted">{{ shortDate(stay.checkIn) }} 入住 · {{ nights }} 晚</text>
        </view>
        <view v-if="stay.address" class="muted small">{{ stay.address }}</view>
        <view class="amap-links">
          <text class="amap" @click="openUrl(amapUrl(stay))">高德地图</text>
          <text class="amap" @click="openUrl(amapNavUrl(stay))">导航</text>
        </view>
      </view>
      <view class="stay-side">
        <template v-if="stay.price">
          <text class="price">¥{{ stay.price }}</text>
          <text class="muted small">/晚 · {{ stay.platform }}</text>
        </template>
        <text v-else class="muted small">暂无价格</text>
      </view>
    </view>

    <view v-if="stay.quotes?.length" class="quotes">
      <view v-for="q in stay.quotes" :key="q.platform" class="quote" :class="{ best: q.platform === stay.platform }" @click="openUrl(q.url)">
        <text>{{ q.platform }}</text>
        <text class="q-price">¥{{ q.price }}</text>
        <text v-if="q.url" class="q-go">预订 ›</text>
      </view>
    </view>
    <view class="muted small note">
      <text v-if="stay.queriedAt">{{ queriedAt(stay.queriedAt) }} 查询 · </text>各平台口径不同，以预订页为准<text v-if="nights && stay.price"> · 住宿约 ¥{{ stay.price * nights }}</text>
    </view>

    <view v-if="editable" class="acts">
      <button class="btn ghost small" :class="{ bookedBtn: booked }" @click="toggleBooked">{{ booked ? "✓ 已预约" : "标记已预约" }}</button>
      <button v-if="stay.options?.length" class="btn ghost small" @click="showOptions = !showOptions">{{ showOptions ? "收起" : "换一家" }}</button>
      <button class="btn ghost small" :disabled="quoting" @click="requote">{{ quoting ? "比价中，约 15–60 秒…" : "重新比价" }}</button>
    </view>

    <view v-if="showOptions" class="options">
      <view v-for="o in stay.options" :key="o.name" class="option">
        <view class="option-body">
          <view class="option-name">{{ o.name }}</view>
          <view class="muted small">
            <text v-if="o.price">¥{{ o.price }}/晚 · {{ o.platform }}</text>
            <text v-else>暂无价格</text>
            <text v-if="o.price && stay.price" :class="o.price < stay.price ? 'cheaper' : 'dearer'"> · {{ o.price < stay.price ? "便宜" : "贵" }} ¥{{ Math.abs(o.price - stay.price) }}</text>
          </view>
        </view>
        <button class="btn small" :disabled="switching" @click="pick(o.name)">换这家</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { Stay, TripDetail } from "../api/types";
import Icon from "./Icon.vue";
import { openUrl, queriedAt, shortDate } from "../utils/format";
import { amapNavUrl, amapUrl } from "../utils/place";

/** 行程里的一段住宿：酒店照片评分、各平台每晚价格和预订链接；可以从比价候选里换一家，或重新比价。 */
const props = defineProps<{ stay: Stay; tripId: number; editable: boolean; booked?: boolean }>();
const emit = defineEmits<{ (e: "saved", detail: TripDetail, note: string): void; (e: "updated", detail: TripDetail): void }>();

const broken = ref(false);
const showOptions = ref(false);
const quoting = ref(false);
const switching = ref(false);

const nights = computed(() => {
  const a = new Date(`${props.stay.checkIn}T00:00:00`).getTime();
  const b = new Date(`${props.stay.checkOut}T00:00:00`).getTime();
  return a && b && b > a ? Math.round((b - a) / 86400000) : 0;
});

/** 标记或取消已预约：只是记录，不会去平台下单或取消 */
async function toggleBooked() {
  try {
    emit("updated", await api.markBooked(props.tripId, props.stay.id, !props.booked));
    uni.showToast({ title: props.booked ? "已取消预约标记" : "已标记为已预约", icon: "none" });
  } catch (e) {
    showError(e);
  }
}

/** 换一家：已预约的先提醒去平台取消 */
function pick(name: string) {
  if (!props.booked) {
    doPick(name);
    return;
  }
  uni.showModal({
    title: "这家酒店已预约",
    content: "换酒店前记得去原平台取消预订，避免扣费。继续换吗？",
    confirmText: "继续",
    success: (res) => res.confirm && doPick(name),
  });
}

/** 换一家：存成新版本 */
async function doPick(name: string) {
  switching.value = true;
  try {
    const detail = await api.switchStay(props.tripId, props.stay.id, name);
    showOptions.value = false;
    emit("saved", detail, `住宿换成「${name}」`);
  } catch (e) {
    showError(e);
  } finally {
    switching.value = false;
  }
}

/** 重新比价：只更新价格，不算新版本 */
async function requote() {
  quoting.value = true;
  try {
    emit("updated", await api.requoteStay(props.tripId, props.stay.id));
    uni.showToast({ title: "价格已更新", icon: "none" });
  } catch (e) {
    showError(e);
  } finally {
    quoting.value = false;
  }
}
</script>

<style scoped>
.stay { display: flex; flex-direction: column; gap: 8px; }
.stay-main { display: flex; gap: 10px; align-items: flex-start; }
.stay-img { width: 88px; height: 70px; border-radius: 10px; flex-shrink: 0; background: var(--surface-2); }
.stay-img.ph { background: linear-gradient(135deg, #93c5fd, #2563eb); display: flex; align-items: center; justify-content: center; }
.stay-body { flex: 1; min-width: 0; }
.stay-name { font-weight: 700; font-size: 14px; }
.stay-name.link { color: var(--accent); cursor: pointer; }
.stay-meta { display: flex; gap: 6px; font-size: 12px; margin-top: 2px; }
.star { color: #f59e0b; }
.stay-side { display: flex; flex-direction: column; align-items: flex-end; flex-shrink: 0; }
.price { font-size: 18px; font-weight: 800; color: var(--price); font-variant-numeric: tabular-nums; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.amap-links { display: flex; gap: 12px; margin-top: 2px; }
.amap { font-size: 12px; color: var(--accent); cursor: pointer; }
.quotes { display: flex; flex-wrap: wrap; gap: 6px; }
.quote {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 8px;
  border: 1px solid var(--line);
  cursor: pointer;
}
.quote.best { border-color: var(--accent); background: var(--accent-soft); }
.q-price { font-weight: 700; font-variant-numeric: tabular-nums; }
.q-go { color: var(--accent); }
.note { line-height: 1.5; }
.acts { display: flex; gap: 8px; }
.acts .btn { margin: 0; }
.options { display: flex; flex-direction: column; gap: 6px; border-top: 1px dashed var(--line); padding-top: 8px; }
.option { display: flex; align-items: center; gap: 8px; }
.option-body { flex: 1; min-width: 0; }
.option-name { font-size: 13px; font-weight: 600; }
.option .btn { margin: 0; }
.cheaper { color: var(--ok); }
.booked-tag { margin-left: 6px; font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 999px; background: var(--ok-soft); color: var(--ok); }
.bookedBtn { color: var(--ok) !important; border-color: var(--ok) !important; background: var(--ok-soft) !important; }
.dearer { color: var(--price); }
</style>
