<template>
  <Shell active="plan">
  <view class="page">
    <template v-if="saved">
      <view class="head">
        <view class="title">{{ saved.data.destination }} {{ saved.data.days }} 天 · 推荐方案</view>
        <view class="sub">按你的需求生成的 {{ options.length }} 种不同风格的方案</view>
      </view>
      <view class="plans">
        <view v-for="(p, i) in options" :key="p.key" class="plan" :class="[TONES[i % TONES.length], { recommended: i === 1 }]">
          <view v-if="i === 1" class="ribbon">推荐</view>
          <view class="plan-body">
            <view class="plan-info">
              <view class="plan-name">
                <view class="plan-icon"><Icon :name="ICONS[i % ICONS.length]" :size="14" color="#fff" /></view>
                <text>{{ p.title }}</text>
              </view>
              <view class="plan-tagline">{{ p.style }}</view>
              <view v-if="p.budgetPerPerson" class="plan-price">¥{{ p.budgetPerPerson.toLocaleString() }}<text class="per"> /人</text></view>
              <view v-for="pt in p.highlights" :key="pt" class="point">
                <Icon name="check" :size="14" color="var(--tone)" />
                <text>{{ pt }}</text>
              </view>
              <view v-if="p.hotel" class="point muted">住：{{ p.hotel }}</view>
              <view class="outline">
                <view v-for="d in p.outline" :key="d.day" class="outline-row">
                  <text class="outline-day">D{{ d.day }}</text>
                  <text>{{ d.title }}</text>
                </view>
              </view>
            </view>
            <view class="plan-side">
              <image v-if="photos[i]" class="plan-img" :src="photos[i]" mode="aspectFill" />
              <view v-else class="plan-img blank"><Icon name="trip" :size="26" color="#fff" /></view>
              <button class="btn small detail" @click="pick(p)">选这个方案</button>
            </view>
          </view>
        </view>
      </view>
      <view class="note">预算为人均估算，含大交通、住宿、门票和餐饮；选定后助手会查真实机票、酒店价格再排完整行程。</view>
    </template>
    <view v-else class="empty">
      <Icon name="trip" :size="34" color="var(--muted)" />
      <view>还没有方案</view>
      <view class="muted small">在对话里说出想去哪、玩几天，助手会先给出三套不同风格的方案</view>
      <button class="btn small" @click="toChat">去规划</button>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import Shell from "../../components/Shell.vue";
import Icon from "../../components/Icon.vue";
import { api } from "../../api";
import { loadPlans } from "../../utils/plans";
import { cityOf } from "../../utils/place";

/** 三套方案对比（原型③）：读最近一次对话里的方案卡，选一套回到那个对话让助手展开。 */
const TONES = ["green", "blue", "violet"];
const ICONS = ["wallet", "star", "compass"];

const saved = loadPlans();
const options = computed<any[]>(() => saved?.data?.options || []);
const photos = ref<string[]>([]);

// 每套方案配一张图：第一天第一个地点在高德的照片
onMounted(() => {
  const city = cityOf(saved?.data?.destination);
  options.value.forEach((o, i) => {
    const place = o.outline?.[0]?.places?.[0];
    if (!place) return;
    api.places({ kind: "spot", city, keyword: place, size: 1 })
      .then((list) => { photos.value[i] = list[0]?.photo || ""; })
      .catch(() => {});
  });
});

function pick(p: any) {
  const q = encodeURIComponent(`就按「${p.title}」方案，展开成完整行程`);
  const id = saved?.conversationId;
  uni.reLaunch({ url: `/pages/chat/chat?${id ? `id=${id}&` : ""}q=${q}` });
}

function toChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
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
.plan-img.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); display: flex; align-items: center; justify-content: center; }
.outline { margin-top: 8px; font-size: 12px; line-height: 1.7; }
.outline-row { display: flex; gap: 6px; }
.outline-day { color: var(--tone); font-weight: 600; font-variant-numeric: tabular-nums; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 80px 0; }
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
