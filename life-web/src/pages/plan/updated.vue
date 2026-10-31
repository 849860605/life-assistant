<template>
  <Shell active="plan">
  <view class="page">
    <view v-if="!tripId" class="empty">
      <Icon name="trip" :size="34" color="var(--muted)" />
      <view>还没有调整过的行程</view>
      <button class="btn small" @click="toChat">先在对话里生成一个行程</button>
    </view>
    <template v-else-if="after">
      <view class="hero">
        <view class="party"><Icon name="party" :size="34" color="var(--price)" /></view>
        <view class="title">行程已更新！</view>
        <view class="sub">{{ after.version.changeNote || "已按你的调整" }}，已重新计算路线（第 {{ fromVer }} 版 → 第 {{ toVer }} 版）</view>
      </view>
      <view class="compare">
        <view class="side before">
          <view class="side-title">调整前</view>
          <view class="kv"><text>路上总里程</text><text>{{ stats(before).km }} km</text></view>
          <view class="kv"><text>路上总时长</text><text>{{ duration(stats(before).minutes) }}</text></view>
          <view class="kv"><text>景点餐饮</text><text>¥{{ stats(before).cost }}/人</text></view>
        </view>
        <Icon name="right" :size="20" color="var(--muted)" />
        <view class="side after">
          <view class="side-title">调整后</view>
          <view class="kv"><text>路上总里程</text><text :class="tone(stats(after).km, stats(before).km)">{{ stats(after).km }} km</text></view>
          <view class="kv"><text>路上总时长</text><text :class="tone(stats(after).minutes, stats(before).minutes)">{{ duration(stats(after).minutes) }}</text></view>
          <view class="kv"><text>景点餐饮</text><text :class="tone(stats(after).cost, stats(before).cost)">¥{{ stats(after).cost }}/人</text></view>
        </view>
      </view>
      <view v-if="changes.length" class="changes">
        <view v-for="c in changes" :key="c.key" class="change">
          <text class="ch-day">D{{ c.day }}</text>
          <text class="ch-kind" :class="c.kind">{{ KIND_LABEL[c.kind] }}</text>
          <text class="ch-text">{{ c.text }}</text>
        </view>
      </view>
      <image v-if="map" class="map" :src="map" mode="aspectFill" />
      <view class="note">路程来自高德，按打车估算（1 公里内按步行）；以上是变动那一天的路线。</view>
      <button class="btn primary" @click="view">查看新的行程</button>
      <button class="btn ghost" @click="again">继续调整</button>
    </template>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { TripDetail } from "../../api/types";
import Icon from "../../components/Icon.vue";
import { dayMapUrl, dayTravel, duration } from "../../utils/place";

/** 调整结果（原型⑪）：两个版本的路上里程、时长、花费对比和逐项变动。参数 ?id=行程&from=旧版本&to=新版本 */
const KIND_LABEL: Record<string, string> = { add: "新增", remove: "删除", change: "替换", move: "调整时间" };
const tripId = ref(0);
const fromVer = ref(0);
const toVer = ref(0);
const before = ref<TripDetail | null>(null);
const after = ref<TripDetail | null>(null);

/** 逐天比较：同一个 id 的项改了名字算替换，只有时间变了算调整时间 */
const changes = computed(() => {
  const out: { key: string; day: number; kind: string; text: string }[] = [];
  const a = before.value?.version.itinerary?.days || [];
  const b = after.value?.version.itinerary?.days || [];
  for (let d = 0; d < Math.max(a.length, b.length); d++) {
    const old = new Map((a[d]?.items || []).map((x) => [x.id, x]));
    const now = new Map((b[d]?.items || []).map((x) => [x.id, x]));
    for (const [id, x] of now) {
      const o = old.get(id);
      if (!o) out.push({ key: `${d}-a-${id}`, day: d + 1, kind: "add", text: `${x.start || ""} ${x.name}` });
      else if (o.name !== x.name) out.push({ key: `${d}-c-${id}`, day: d + 1, kind: "change", text: `${o.name} → ${x.name}` });
      else if (o.start !== x.start) out.push({ key: `${d}-m-${id}`, day: d + 1, kind: "move", text: `${x.name}：${o.start} → ${x.start}` });
    }
    for (const [id, o] of old) {
      if (!now.has(id)) out.push({ key: `${d}-r-${id}`, day: d + 1, kind: "remove", text: o.name });
    }
  }
  return out;
});
/** 有变动的第一天的新路线图 */
const map = computed(() => {
  const days = after.value?.version.itinerary?.days || [];
  const d = changes.value.length ? changes.value[0].day - 1 : 0;
  return dayMapUrl(days[d], 720, 300);
});

onLoad(async (query) => {
  tripId.value = Number(query?.id) || 0;
  if (!tripId.value) return;
  try {
    const latest = await api.trip(tripId.value);
    toVer.value = Number(query?.to) || latest.version.version;
    fromVer.value = Number(query?.from) || Math.max(toVer.value - 1, 1);
    [before.value, after.value] = await Promise.all([api.trip(tripId.value, fromVer.value), api.trip(tripId.value, toVer.value)]);
  } catch (e) {
    showError(e);
  }
});

/** 一个版本全程的路上里程、时长和景点餐饮花费 */
function stats(d: TripDetail | null) {
  const days = d?.version.itinerary?.days || [];
  const t = days.map(dayTravel);
  return {
    km: Math.round(t.reduce((s, x) => s + x.km, 0) * 10) / 10,
    minutes: t.reduce((s, x) => s + x.minutes, 0),
    cost: days.reduce((s, x) => s + x.items.reduce((c, i) => c + (i.cost || 0), 0), 0),
  };
}

/** 变少标蓝，变多标橙 */
function tone(now: number, old: number) {
  return now < old ? "better" : now > old ? "worse" : "";
}

function view() {
  uni.redirectTo({ url: `/pages/trips/detail?id=${tripId.value}` });
}

function again() {
  const d = changes.value.length ? changes.value[0].day : 1;
  uni.redirectTo({ url: `/pages/plan/edit?id=${tripId.value}&day=${d}` });
}

function toChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
}
</script>

<style scoped>
.page { max-width: 520px; margin: 0 auto; padding: 20px 16px 28px; box-sizing: border-box; display: flex; flex-direction: column; gap: 14px; }
.hero { text-align: center; }
.party { width: 64px; height: 64px; margin: 0 auto 8px; border-radius: 50%; background: #fff4ea; display: flex; align-items: center; justify-content: center; }
.title { font-size: 22px; font-weight: 800; }
.sub { font-size: 13px; color: var(--muted); margin-top: 4px; }
.compare { display: flex; align-items: center; gap: 8px; }
.side { flex: 1; border-radius: 14px; padding: 12px; background: var(--surface); border: 1px solid var(--line); }
.side.after { background: var(--accent-soft); border-color: var(--accent); }
.side-title { font-weight: 700; margin-bottom: 6px; }
.after .side-title { color: var(--accent); }
.kv { display: flex; justify-content: space-between; font-size: 13px; margin-top: 3px; font-variant-numeric: tabular-nums; }
.kv text:first-child { color: var(--muted); }
.better { color: var(--accent); font-weight: 600; }
.worse { color: var(--price); font-weight: 600; }
.changes { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 8px 12px; }
.change { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 5px 0; }
.ch-day { font-weight: 700; color: var(--accent); }
.ch-kind { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); flex-shrink: 0; }
.ch-kind.add { background: var(--ok-soft); color: var(--ok); }
.ch-kind.remove { background: #fde8ec; color: #e11d48; }
.ch-kind.change { background: var(--accent-soft); color: var(--accent); }
.ch-text { flex: 1; min-width: 0; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 80px 0; }
.empty .btn { width: auto; }
.map { width: 100%; height: 220px; border-radius: 14px; }
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: -6px; }
.btn { margin: 0; width: 100%; }
.btn.primary { height: 46px; }
@media (min-width: 1024px) {
  .page { max-width: 760px; padding: 24px 28px; }
  .map { height: 300px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
