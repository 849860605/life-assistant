<template>
  <view class="xcard">
    <view class="xcard-head">
      <text class="xcard-title">{{ data.destination }} · {{ data.days }} 天 · 三套方案</text>
      <text class="more" @click="openPage">对比查看 ›</text>
    </view>
    <view class="opts">
      <view v-for="(o, i) in data.options" :key="o.key" class="opt" :class="TONES[i % TONES.length]">
        <view class="opt-title">{{ o.title }}</view>
        <view class="opt-style muted">{{ o.style }}</view>
        <view v-if="o.budgetPerPerson" class="opt-price">约 ¥{{ o.budgetPerPerson.toLocaleString() }}<text class="per"> /人</text></view>
        <view v-for="h in o.highlights" :key="h" class="opt-point">
          <Icon name="check" :size="13" color="var(--tone)" />
          <text>{{ h }}</text>
        </view>
        <view v-if="o.transport" class="opt-point"><Icon name="plane" :size="13" color="var(--muted)" /><text>{{ o.transport }}</text></view>
        <view class="opt-outline muted">
          <view v-for="d in o.outline" :key="d.day">D{{ d.day }} {{ d.title }}</view>
        </view>
        <button class="btn small pick" :disabled="!active" @click="pick(o)">选这个</button>
      </view>
    </view>
    <view class="note muted">预算是估算，选定后会查真实机票、酒店价格</view>
  </view>
</template>

<script setup lang="ts">
import Icon from "../Icon.vue";
import { savePlans } from "../../utils/plans";

/** 方案卡：proposeTripPlans 给出的三套方案概要，选一套后让助手展开成完整行程。 */
const props = defineProps<{ data: any; active: boolean; conversationId?: string | null }>();
const emit = defineEmits<{ (e: "submit", text: string): void }>();

const TONES = ["green", "blue", "violet"];

function pick(o: any) {
  emit("submit", `就按「${o.title}」方案，展开成完整行程`);
}

function openPage() {
  savePlans(props.data, props.conversationId || null);
  uni.navigateTo({ url: "/pages/plan/plans" });
}
</script>

<style scoped>
.more { font-size: 13px; color: var(--accent); cursor: pointer; }
.opts { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; padding: 10px 14px 4px; }
.opt {
  --tone: #16a34a;
  --tone-soft: #e7f6ec;
  border-radius: 12px;
  padding: 12px;
  background: linear-gradient(135deg, var(--tone-soft), var(--surface) 70%);
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
}
.opt.blue { --tone: #2563eb; --tone-soft: #e6eefd; }
.opt.violet { --tone: #7c3aed; --tone-soft: #f0e9fd; }
.opt-title { font-weight: 700; font-size: 15px; }
.opt-style { font-size: 12px; margin-top: 2px; }
.opt-price { font-size: 18px; font-weight: 800; color: var(--tone); margin: 6px 0 2px; font-variant-numeric: tabular-nums; }
.per { font-size: 12px; font-weight: 400; color: var(--muted); }
.opt-point { display: flex; align-items: center; gap: 5px; font-size: 13px; margin-top: 3px; }
.opt-outline { font-size: 12px; margin: 8px 0; line-height: 1.6; }
.pick { margin: auto 0 0 !important; background: var(--tone) !important; }
.note { font-size: 12px; padding: 6px 14px 12px; }
</style>
