<template>
  <Shell active="plan">
  <view class="page">
    <view class="head">
      <view class="title">西安4天3晚 · 推荐方案</view>
      <view class="sub">按你的需求生成 3 种不同风格的方案 <text class="tag">示例</text></view>
    </view>
    <view class="plans">
      <view v-for="p in PLANS" :key="p.key" class="plan" :class="[p.tone, { recommended: p.recommended }]">
        <view v-if="p.recommended" class="ribbon">推荐</view>
        <view class="plan-body">
          <view class="plan-info">
            <view class="plan-name">
              <view class="plan-icon"><Icon :name="p.icon" :size="14" color="#fff" /></view>
              <text>{{ p.name }}</text>
            </view>
            <view class="plan-tagline">{{ p.tagline }}</view>
            <view class="plan-price">¥{{ p.price.toLocaleString() }}<text class="per"> /人</text></view>
            <view v-for="pt in p.points" :key="pt" class="point">
              <Icon name="check" :size="14" color="var(--tone)" />
              <text>{{ pt }}</text>
            </view>
          </view>
          <view class="plan-side">
            <image class="plan-img" :src="p.image" mode="aspectFill" />
            <button class="btn small detail" @click="openPlan">查看详情</button>
          </view>
        </view>
      </view>
    </view>
    <view class="note">价格为人均估算，含机票、住宿、门票和餐饮，以实际预订为准。</view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import Icon from "../../components/Icon.vue";
import { PLANS } from "../../demo/xian";

/** 三套方案对比（原型③）：省钱、舒适、深度体验，点开进行程详情。 */
function openPlan() {
  uni.navigateTo({ url: "/pages/trips/detail?id=demo" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 16px; box-sizing: border-box; }
.head { text-align: center; margin-bottom: 14px; }
.title { font-size: 18px; font-weight: 700; }
.sub { font-size: 13px; color: var(--muted); margin-top: 4px; }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 999px; background: var(--surface-2); }
.plans { display: flex; flex-direction: column; gap: 12px; }
.plan {
  --tone: #16a34a;
  --tone-soft: #e7f6ec;
  position: relative;
  border-radius: 16px;
  background: linear-gradient(135deg, var(--tone-soft), var(--surface) 60%);
  border: 1px solid var(--line);
  overflow: hidden;
}
.plan.blue { --tone: #2563eb; --tone-soft: #e6eefd; }
.plan.violet { --tone: #7c3aed; --tone-soft: #f0e9fd; }
.plan.recommended { border: 2px solid var(--tone); }
.ribbon {
  position: absolute;
  top: 0;
  left: 0;
  background: var(--tone);
  color: #fff;
  font-size: 11px;
  padding: 2px 10px;
  border-bottom-right-radius: 10px;
}
.plan-body { display: flex; gap: 12px; padding: 16px 14px 14px; }
.plan-info { flex: 1; min-width: 0; }
.plan-name { display: flex; align-items: center; gap: 6px; font-weight: 700; font-size: 16px; }
.plan-icon {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--tone);
  display: flex;
  align-items: center;
  justify-content: center;
}
.plan-tagline { font-size: 12px; color: var(--muted); margin-top: 2px; }
.plan-price { font-size: 22px; font-weight: 800; color: var(--tone); margin: 6px 0 4px; font-variant-numeric: tabular-nums; }
.per { font-size: 12px; font-weight: 400; color: var(--muted); }
.point { display: flex; align-items: center; gap: 6px; font-size: 13px; margin-top: 3px; }
.plan-side { width: 120px; display: flex; flex-direction: column; gap: 10px; flex-shrink: 0; }
.plan-img { width: 120px; height: 96px; border-radius: 10px; }
.detail { margin: 0; background: var(--tone) !important; }
.note { font-size: 12px; color: var(--muted); text-align: center; margin-top: 14px; }
@media (min-width: 700px) {
  .plans { flex-direction: row; }
  .plan { flex: 1; }
  .plan-body { flex-direction: column; }
  .plan-side { width: 100%; }
  .plan-img { width: 100%; height: 140px; }
}
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
