<template>
  <view class="xcard">
    <view class="xcard-head">
      <text class="xcard-title">美食推荐</text>
      <text class="xcard-meta">{{ data.query }} · {{ queriedAt(data.queriedAt) }} 查询</text>
    </view>
    <view v-for="(r, i) in shown" :key="r.name" class="xcard-row shop">
      <view class="shop-top">
        <text class="rank">{{ i + 1 }}</text>
        <text class="shop-name" :class="{ link: r.dianpingUrl }" @click="openUrl(r.dianpingUrl)">{{ r.name }}</text>
        <text v-if="r.avgPrice" class="price">¥{{ r.avgPrice }}<text class="per">/人</text></text>
      </view>
      <view class="shop-meta">
        <text v-if="r.rating" class="rating">★ {{ r.rating }}</text>
        <text v-if="r.ratingFrom" class="muted">{{ r.ratingFrom }}</text>
        <text v-if="r.location" class="muted">· {{ r.location }}</text>
      </view>
      <view v-if="r.dishes" class="shop-line">招牌：{{ r.dishes }}</view>
      <view v-if="r.hours" class="shop-line muted">营业：{{ r.hours }}</view>
      <view v-if="r.platforms.length > 1" class="shop-tags">
        <text class="chip ok">点评、携程都推荐</text>
      </view>
    </view>
    <view v-if="data.restaurants.length > LIMIT" class="xcard-row more link" @click="all = !all">
      {{ all ? "收起" : `展开全部 ${data.restaurants.length} 家` }}
    </view>
    <view class="xcard-foot">评分、人均来自大众点评或携程，可能有变动；店名可点开点评页。{{ failed }}</view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { openUrl, queriedAt } from "../../utils/format";

/** 美食卡：findRestaurants 合并后的餐厅，默认显示前 5 家。 */
const props = defineProps<{ data: any }>();
const LIMIT = 5;
const all = ref(false);
const shown = computed(() => (all.value ? props.data.restaurants : props.data.restaurants.slice(0, LIMIT)));
/** 没查到的平台和原因 */
const failed = computed(() => {
  const bad = Object.entries(props.data.platforms || {}).filter(([, v]) => !String(v).startsWith("OK"));
  return bad.length ? "未查到：" + bad.map(([k, v]) => `${k}（${v}）`).join("、") : "";
});
</script>

<style scoped>
.shop-top { display: flex; align-items: baseline; gap: 8px; }
.rank {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  font-size: 12px;
  border-radius: 50%;
  background: var(--surface-2);
  color: var(--muted);
}
.shop-name { flex: 1; min-width: 0; font-weight: 600; }
.per { font-size: 12px; font-weight: 400; color: var(--muted); }
.shop-meta { display: flex; flex-wrap: wrap; gap: 4px 6px; font-size: 13px; margin-top: 2px; padding-left: 26px; }
.rating { color: var(--price); font-variant-numeric: tabular-nums; }
.shop-line { font-size: 13px; margin-top: 2px; padding-left: 26px; }
.shop-tags { padding-left: 26px; margin-top: 4px; }
.more { text-align: center; font-size: 14px; }
</style>
