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
        <view class="avatar"><Icon name="sparkle" :size="18" color="#fff" /></view>
        <view>
          <view class="title">替换：{{ item?.name || "…" }}</view>
          <view class="sub">已去过？不想去？从附近同类、评分高的地方里挑一个</view>
        </view>
      </view>
      <view class="reasons">
        <text v-for="r in REASONS" :key="r.label" class="reason" :class="{ on: why === r.value }" @click="why = r.value">{{ r.label }}</text>
      </view>
      <view class="search">
        <Icon name="search" :size="14" color="var(--muted)" />
        <input id="replace-search" v-model="keyword" class="search-input" placeholder="想换成什么，如：博物馆" @confirm="loadList" />
      </view>
      <view class="tabs">
        <text v-for="t in TABS" :key="t" class="tab" :class="{ active: tab === t }" @click="tab = t">{{ t }}</text>
      </view>
      <view v-if="loading" class="state muted">正在找附近的地点…</view>
      <view v-else-if="!sorted.length" class="state muted">{{ error || "附近没找到合适的，换个关键字试试" }}</view>
      <view class="grid">
        <view v-for="r in sorted" :key="r.id" class="place">
          <image v-if="r.photo && !broken[r.id]" class="img" :src="r.photo" mode="aspectFill" @error="broken[r.id] = true" />
          <view v-else class="img ph"><Icon name="pin" :size="24" color="#fff" /></view>
          <view class="info">
            <view class="name">{{ r.name }}</view>
            <view class="meta"><text v-if="r.rating" class="rating">★ {{ r.rating }}</text><text class="muted"> · {{ r.category }}</text></view>
            <view v-if="r.distance != null" class="meta muted">距原地点 {{ distanceText(r.distance) }}</view>
            <button class="btn ghost small pick" :disabled="saving" @click="pick(r)">替换为这里</button>
          </view>
        </view>
      </view>
      <button class="btn ghost remove" :disabled="saving || !item" @click="removeIt">不换了，直接删掉这一项</button>
      <view class="note">地点、评分、距离来自高德；替换后存成新版本并重算当天路线。</view>
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
import type { ChangeReason, ItineraryItem, Place } from "../../api/types";
import Icon from "../../components/Icon.vue";
import { distanceText } from "../../utils/place";

/** 景点替换（原型⑤，手机）：高德附近同类候选，选一个替换后去看前后对比。参数 ?id=行程&item=项目id&reason= */
const TABS = ["评分最高", "离得最近"];
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
const keyword = ref("");
const tab = ref(TABS[0]);
const list = ref<Place[]>([]);
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const broken = reactive<Record<string, boolean>>({});

const sorted = computed(() =>
  tab.value === "离得最近" ? [...list.value].sort((a, b) => (a.distance ?? 1e9) - (b.distance ?? 1e9)) : list.value,
);

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
  try {
    list.value = await api.alternatives(tripId.value, itemId.value, keyword.value.trim() || undefined);
  } catch (e) {
    list.value = [];
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

async function pick(p: Place) {
  saving.value = true;
  try {
    const d = await api.replaceItem(tripId.value, itemId.value, {
      name: p.name, poiId: p.id, address: p.address, lng: p.lng, lat: p.lat, photo: p.photo, rating: p.rating, cost: p.cost,
    }, why.value);
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
.avatar { width: 38px; height: 38px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.title { font-weight: 700; font-size: 16px; }
.sub { font-size: 12px; color: var(--muted); }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 999px; background: var(--surface-2); }
.reasons { display: flex; gap: 8px; margin-bottom: 10px; }
.reason { font-size: 13px; padding: 5px 12px; border-radius: 999px; background: var(--surface-2); cursor: pointer; }
.reason.on { background: var(--accent); color: #fff; }
.search { display: flex; align-items: center; gap: 6px; height: 36px; padding: 0 12px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); margin-bottom: 10px; }
.search-input { flex: 1; font-size: 14px; }
.state { padding: 40px 0; text-align: center; font-size: 14px; }
.ph { display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #93c5fd, #2563eb); }
.remove { width: 100%; margin: 14px 0 0; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 80px 0; }
.empty .btn { margin: 0; }
.tabs { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; margin-bottom: 12px; }
.tab { flex: 1; text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; color: var(--muted); cursor: pointer; }
.tab.active { background: var(--surface); color: var(--accent); font-weight: 600; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08); }
.grid { display: flex; flex-direction: column; gap: 10px; }
.place { display: flex; gap: 10px; background: var(--surface); border-radius: 14px; padding: 10px; border: 1px solid var(--line); }
.img { width: 104px; height: 96px; border-radius: 10px; flex-shrink: 0; }
.info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.name { font-weight: 700; }
.meta { font-size: 12px; margin-top: 2px; }
.rating { color: var(--price); }
.pick { margin: auto 0 0; align-self: flex-start; color: var(--accent); border-color: var(--accent); }
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
@media (min-width: 700px) {
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); }
  .place { flex-direction: column; }
  .img { width: 100%; height: 120px; }
}
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
