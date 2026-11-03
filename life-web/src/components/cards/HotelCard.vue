<template>
  <view class="xcard">
    <view class="xcard-head">
      <text class="xcard-title">{{ data.city ? data.city + " · " : "" }}酒店比价</text>
      <text class="xcard-meta">{{ shortDate(data.checkIn) }}–{{ shortDate(data.checkOut) }} · {{ queriedAt(data.queriedAt) }} 查询</text>
    </view>
    <view v-for="h in hotels" :key="h.hotel" class="xcard-row hotel">
      <view class="hotel-top">
        <text class="hotel-name">{{ h.hotel }}</text>
        <view class="hotel-price">
          <text v-if="h.best" class="price big">¥{{ h.best.price }}<text class="per">/晚起</text></text>
          <text v-else class="muted">暂无价格</text>
          <text v-if="h.best" class="muted small">最低 · {{ h.best.platform }}</text>
        </view>
      </view>
      <view v-if="h.review" class="hotel-review">
        <text class="review-score">{{ typeof h.review.score === "number" ? "口碑 " + h.review.score.toFixed(2) : h.review.score }}</text>
        <text v-if="h.review.sources" class="muted"> · {{ h.review.sources }}</text>
      </view>
      <view v-if="h.review?.highlight" class="hotel-note muted">{{ h.review.highlight }}</view>
      <view v-if="h.review?.match" class="hotel-note muted">符合要求 {{ h.review.match }}</view>
      <view v-if="h.note" class="hotel-note muted">{{ h.note }}</view>
      <view class="quotes">
        <text
          v-for="q in h.quotes"
          :key="q.platform"
          class="quote"
          :class="{ best: h.best && q.platform === h.best.platform, link: q.url }"
          @click="openUrl(q.url)"
        >{{ q.platform }} {{ q.price ? "¥" + q.price : "—" }}<text v-if="q.url"> ›</text></text>
      </view>
      <view v-if="h.mismatch" class="hotel-note warn">{{ h.mismatch }}</view>
    </view>
    <view class="xcard-foot">
      {{ ranked ? "按网上口碑排序（美团评分按评价条数加权、高德评分），价格不参与排序。" : "按最低价排序。" }}各平台口径不同（RollingGo 实时可订价、飞猪明码价、携程参考价、美团约价），以预订页为准；部分链接含推广参数，不影响排序。
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { openUrl, queriedAt, shortDate } from "../../utils/format";

/**
 * 酒店卡：每家酒店各平台的每晚价格；后端按口碑排过序（结果带 review）就保持那个顺序，否则按最低价从低到高排。
 * 平台匹配到的店名和查询名不一致时提示。
 */
const props = defineProps<{ data: any }>();

/** 去掉标点空格后，一个名字包含另一个就算同一家。 */
function sameName(a: string, b: string) {
  const norm = (s: string) => s.replace(/[^\p{L}\p{N}]/gu, "").toLowerCase();
  const x = norm(a);
  const y = norm(b);
  return x.includes(y) || y.includes(x);
}

const ranked = computed(() => (props.data.results || []).some((r: any) => r.review));

const hotels = computed(() =>
  (props.data.results || [])
    .map((r: any) => {
      const quotes = r.quotes || [];
      const priced = quotes.filter((q: any) => q.price);
      const best = priced.length ? priced.reduce((a: any, b: any) => (b.price < a.price ? b : a)) : null;
      const other = quotes.find((q: any) => q.matchedName && q.platform !== "RollingGo" && !sameName(q.matchedName, r.hotel));
      return {
        hotel: r.hotel,
        quotes,
        best,
        review: r.review,
        note: quotes.find((q: any) => q.platform === "RollingGo")?.note,
        mismatch: other ? `${other.platform} 匹配到的是"${other.matchedName}"，注意核对` : "",
      };
    })
    .sort((a: any, b: any) => (ranked.value ? 0 : (a.best?.price ?? Infinity) - (b.best?.price ?? Infinity))),
);
</script>

<style scoped>
.hotel-top { display: flex; align-items: flex-start; gap: 12px; }
.hotel-name { flex: 1; min-width: 0; font-weight: 600; }
.hotel-price { display: flex; flex-direction: column; align-items: flex-end; flex-shrink: 0; }
.big { font-size: 18px; }
.per { font-size: 12px; font-weight: 400; color: var(--muted); }
.small { font-size: 12px; }
.hotel-note { font-size: 12px; margin-top: 2px; }
.hotel-review { font-size: 12px; margin-top: 2px; }
.review-score { font-weight: 600; color: var(--accent); }
.warn { color: var(--price); }
.quotes { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
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
</style>
