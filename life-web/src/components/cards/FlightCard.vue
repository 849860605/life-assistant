<template>
  <view class="xcard">
    <view class="xcard-head">
      <text class="xcard-title">{{ data.origin }} → {{ data.destination }} · {{ shortDate(data.date) }} {{ weekday(data.date) }}</text>
      <text class="xcard-meta">{{ queriedAt(data.queriedAt) }} 查询</text>
    </view>
    <view v-for="f in shown" :key="f.flightNo" class="xcard-row flight">
      <view class="flight-main">
        <view class="times">
          <text class="time">{{ f.depTime }}</text>
          <text class="arrow">→</text>
          <text class="time">{{ f.arrTime }}</text>
        </view>
        <view class="airports muted">{{ f.depAirport }} → {{ f.arrAirport }}</view>
        <view class="airline muted">{{ f.airline }} {{ f.flightNo }}<text v-if="f.note"> · {{ f.note }}</text></view>
      </view>
      <view class="flight-price">
        <text v-if="f.lowest" class="price big">¥{{ f.lowest }}</text>
        <text v-else class="muted">无价格</text>
        <text v-if="f.lowestPlatform" class="muted small">最低 · {{ f.lowestPlatform }}</text>
      </view>
      <view class="quotes">
        <text
          v-for="(p, name) in f.prices"
          :key="name"
          class="quote"
          :class="{ best: name === f.lowestPlatform, link: p.url }"
          @click="openUrl(p.url)"
        >{{ name }} {{ p.price ? "¥" + p.price : "—" }}<text v-if="p.url"> ›</text></text>
      </view>
    </view>
    <view v-if="data.flights.length > LIMIT" class="xcard-row more link" @click="all = !all">
      {{ all ? "收起" : `展开全部 ${data.flights.length} 班` }}
    </view>
    <view class="xcard-foot">
      按最低价排序。经济舱单程，可能不含机建燃油，以平台页面为准；部分链接含推广参数，不影响排序。{{ failed }}
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { openUrl, queriedAt, shortDate, weekday } from "../../utils/format";

/** 机票卡：compareFlights 按航班号合并的结果，每班显示各平台价格，点平台价格跳预订页。 */
const props = defineProps<{ data: any }>();
const LIMIT = 6;
const all = ref(false);
const shown = computed(() => (all.value ? props.data.flights : props.data.flights.slice(0, LIMIT)));
const failed = computed(() => {
  const bad = Object.entries(props.data.platforms || {}).filter(([, v]) => !String(v).startsWith("OK"));
  return bad.length ? "未查到：" + bad.map(([k, v]) => `${k}（${v}）`).join("、") : "";
});
</script>

<style scoped>
.flight { display: grid; grid-template-columns: 1fr auto; gap: 2px 12px; }
.times { display: flex; align-items: baseline; gap: 6px; }
.time { font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; }
.arrow { color: var(--muted); }
.airports, .airline { font-size: 12px; }
.flight-price { display: flex; flex-direction: column; align-items: flex-end; }
.big { font-size: 18px; }
.small { font-size: 12px; }
.quotes { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.quote {
  font-size: 12px;
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid var(--line);
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
.quote.link { color: var(--accent); }
.quote.best { border-color: var(--price); color: var(--price); }
.more { text-align: center; font-size: 14px; }
</style>
