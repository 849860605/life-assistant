<template>
  <Shell active="flight">
  <view class="page">
    <view class="seg">
      <text v-for="t in TABS" :key="t.key" class="seg-item" :class="{ on: tab === t.key }" @click="tab = t.key">{{ t.label }}</text>
    </view>

    <template v-if="tab === 'flight'">
      <view class="search">
        <view class="route">
          <input id="flight-origin" v-model="origin" class="city" placeholder="出发城市" />
          <view class="swap" @click="swap"><Icon name="swap" :size="16" color="var(--accent)" /></view>
          <input id="flight-destination" v-model="destination" class="city right" placeholder="到达城市" />
        </view>
        <view class="fields">
          <picker mode="date" :value="date" :start="today" @change="date = $event.detail.value">
            <view class="field"><Icon name="calendar" :size="14" color="var(--muted)" /><text>{{ shortDate(date) }} {{ weekday(date) }} 出发</text></view>
          </picker>
          <view class="field"><Icon name="plane" :size="14" color="var(--muted)" /><text>单程 · 经济舱</text></view>
        </view>
        <button class="btn search-btn" :disabled="loading" @click="search"><Icon name="search" :size="16" color="#fff" /><text>{{ loading ? "查询中…" : "搜索" }}</text></button>
      </view>
      <view v-if="loading" class="loading">正在比较飞猪、携程、美团，约 15–60 秒…</view>
      <view v-else-if="error" class="error">{{ error }}</view>
      <view v-else-if="result" class="result">
        <FlightCard v-if="result.flights?.length" :data="result" />
        <view v-else class="error">这天没查到航班：{{ platformNote }}</view>
      </view>
      <view v-else class="foot">填好出发、到达和日期后搜索；往返请回程再查一次。</view>
    </template>

    <template v-else-if="tab === 'hotel'">
      <HotelList :initial="pageQuery" />
    </template>

    <view v-else class="empty">
      <Icon name="ticket" :size="36" color="var(--muted)" />
      <view>门票暂无可用数据源</view>
      <view class="muted small">可以在对话里问景点门票，助手会搜官方信息并提醒核实</view>
      <button class="btn small ask-btn" @click="askTicket">去问助手</button>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Icon from "../../components/Icon.vue";
import HotelList from "../../components/HotelList.vue";
import FlightCard from "../../components/cards/FlightCard.vue";
import { api } from "../../api";
import { currentCity } from "../../utils/place";
import { shortDate, weekday, ymd } from "../../utils/format";

/** 机票比价（原型⑧）：飞猪、携程、美团单程比价；和酒店、门票放在同一页用顶部切换。 */
const TABS = [
  { key: "flight", label: "机票" },
  { key: "hotel", label: "酒店" },
  { key: "ticket", label: "门票" },
];
const tab = ref("flight");
/** 页面参数，酒店标签用它预填表单 */
const pageQuery = ref<Record<string, string | undefined>>({});
const today = ymd(new Date());
const origin = ref("");
const destination = ref(currentCity());
const date = ref(ymd(new Date(Date.now() + 7 * 86400000)));
const loading = ref(false);
const error = ref("");
const result = ref<Record<string, any> | null>(null);
const platformNote = computed(() =>
  Object.entries(result.value?.platforms || {}).map(([k, v]) => `${k}（${v}）`).join("、"),
);

onLoad((query) => {
  pageQuery.value = query || {};
  if (query?.tab) tab.value = query.tab;
  uni.setNavigationBarTitle({ title: tab.value === "hotel" ? "酒店比价" : tab.value === "ticket" ? "门票" : "机票比价" });
  if (query?.origin) origin.value = decodeURIComponent(query.origin);
  if (query?.destination) destination.value = decodeURIComponent(query.destination);
  if (query?.date) date.value = query.date;
  if (tab.value === "flight" && query?.origin && query?.destination && query?.date) search();
});

function swap() {
  [origin.value, destination.value] = [destination.value, origin.value];
}

/** 三个平台并行查，结果按航班号合并、按最低价排。 */
async function search() {
  const from = origin.value.trim();
  const to = destination.value.trim();
  if (!from || !to) {
    uni.showToast({ title: "填一下出发和到达城市", icon: "none" });
    return;
  }
  loading.value = true;
  error.value = "";
  result.value = null;
  try {
    const data = await api.searchFlights(from, to, date.value);
    if (data.error) error.value = data.error;
    else result.value = { ...data, queriedAt: new Date().toISOString() };
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function askTicket() {
  uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent(`${currentCity()}有哪些景点要买门票？大概多少钱，怎么预约？`) });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.seg { display: flex; background: var(--surface); border-radius: 12px; padding: 4px; border: 1px solid var(--line); margin-bottom: 12px; }
.seg-item { flex: 1; text-align: center; padding: 8px 0; border-radius: 9px; font-size: 14px; color: var(--muted); cursor: pointer; }
.seg-item.on { background: var(--accent); color: #fff; font-weight: 600; }
.search { background: var(--surface); border-radius: 14px; padding: 14px; border: 1px solid var(--line); }
.route { display: flex; align-items: center; gap: 8px; }
.city { flex: 1; min-width: 0; font-size: 22px; font-weight: 800; height: 36px; }
.city.right { text-align: right; }
.swap { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.fields { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.fields picker { flex: 1; min-width: 140px; }
.field { flex: 1; min-width: 90px; display: flex; align-items: center; gap: 4px; font-size: 13px; background: var(--surface-2); border-radius: 8px; padding: 8px 10px; cursor: pointer; }
.search-btn { margin: 12px 0 0; width: 100%; gap: 6px; }
.loading, .error { font-size: 13px; color: var(--muted); text-align: center; padding: 24px 0; }
.error { color: #c2560c; }
.result { margin-top: 12px; }
.foot { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 60px 0; }
.ask-btn { margin-top: 8px; }
.small { font-size: 12px; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
}
</style>
