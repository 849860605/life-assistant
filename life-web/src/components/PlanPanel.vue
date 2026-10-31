<template>
  <scroll-view scroll-y class="panel-scroll">
    <view class="pp">
      <!-- 当前会话已经生成了行程：显示这个行程 -->
      <template v-if="trip">
        <view class="hero">
          <image class="hero-img" :src="coverFor(trip.destination) || HERO" mode="aspectFill" />
          <view class="hero-shade" />
          <view class="hero-text">
            <view class="hero-title">{{ trip.title }}</view>
            <view class="hero-sub">{{ trip.destination }} · {{ shortDate(trip.startDate) }} – {{ shortDate(trip.endDate) }} · {{ trip.days.length }} 天 · {{ trip.status === "DRAFT" ? "草稿" : "已确认" }}</view>
          </view>
        </view>
        <view class="block">
          <view class="block-title">逐日安排</view>
          <view v-for="(d, i) in trip.days" :key="d.date" class="outline">
            <view class="outline-no">{{ i + 1 }}</view>
            <view>
              <view class="outline-day">第{{ i + 1 }}天 · {{ shortDate(d.date) }} <text v-if="d.theme" class="muted">{{ d.theme }}</text></view>
              <view class="outline-text">{{ d.items.filter((x: any) => x.type !== "TRANSPORT").map((x: any) => x.name).join(" → ") }}</view>
            </view>
          </view>
          <button class="btn open-btn" @click="openTrip(trip.tripId)">打开行程编辑</button>
        </view>
      </template>

      <!-- 还没有行程：示例方案 -->
      <template v-else>
        <view class="hero">
          <image class="hero-img" :src="HERO" mode="aspectFill" />
          <view class="hero-shade" />
          <view class="hero-text">
            <view class="hero-title">{{ CITY }} 4 天 3 晚 · 推荐方案</view>
            <view class="hero-sub">根据你的需求生成了三套不同风格的行程方案 <text class="sample">示例</text></view>
          </view>
          <view class="req">
            <view class="req-item"><Icon name="calendar" :size="18" color="var(--accent)" /><view><view class="req-label">出行时间</view><view class="req-value">{{ REQUIREMENT.dates }}</view></view></view>
            <view class="req-item"><Icon name="users" :size="18" color="var(--accent)" /><view><view class="req-label">出行人数</view><view class="req-value">{{ REQUIREMENT.people }}</view></view></view>
            <view class="req-item"><Icon name="wallet" :size="18" color="var(--accent)" /><view><view class="req-label">预算范围</view><view class="req-value">{{ REQUIREMENT.budget }}</view><view class="req-note">{{ REQUIREMENT.budgetNote }}</view></view></view>
            <view class="req-item"><Icon name="heart" :size="18" color="var(--accent)" /><view><view class="req-label">旅行偏好标签</view><view class="req-value">{{ REQUIREMENT.tags.join(" · ") }}</view><view class="req-note">{{ REQUIREMENT.prefs }}</view></view></view>
          </view>
        </view>

        <view class="tabs">
          <text v-for="t in TABS" :key="t" class="tab" :class="{ on: tab === t }" @click="tab = t">{{ t }}</text>
        </view>

        <view v-if="tab === TABS[0]" class="plans">
          <view v-for="p in PLANS" :key="p.key" class="plan" :class="[p.tone, { rec: p.recommended }]">
            <view class="plan-head">
              <view class="plan-name"><Icon :name="p.icon" :size="18" color="var(--tone)" /><text>{{ p.name }}</text></view>
              <text class="badge">{{ p.badge }}</text>
            </view>
            <view class="plan-price"><text class="per">人均约 </text>¥{{ p.price.toLocaleString() }}</view>
            <view v-for="pt in p.points" :key="pt" class="point"><Icon name="check" :size="14" color="var(--tone)" /><text>{{ pt }}</text></view>
            <image class="plan-img" :src="p.image" mode="aspectFill" />
            <button class="btn plan-btn" :class="{ ghost: !p.recommended }" @click="openTrip('demo')">查看详细行程</button>
          </view>
        </view>
        <view v-else-if="tab === TABS[1]" class="compare">
          <view class="cmp-row head"><text>对比项</text><text v-for="p in PLANS" :key="p.key">{{ p.name }}</text></view>
          <view v-for="row in COMPARE" :key="row[0]" class="cmp-row"><text v-for="(c, i) in row" :key="i">{{ c }}</text></view>
        </view>
        <view v-else class="compare">
          <view class="cmp-row head"><text>费用（人均）</text><text v-for="p in PLANS" :key="p.key">{{ p.name }}</text></view>
          <view v-for="row in COSTS" :key="row[0]" class="cmp-row"><text v-for="(c, i) in row" :key="i">{{ c }}</text></view>
        </view>

        <view class="block">
          <view class="block-title"><Icon name="sparkle" :size="16" color="var(--accent)" /> 方案亮点预览</view>
          <view class="highlights">
            <view v-for="h in HIGHLIGHTS" :key="h.name" class="hl">
              <image class="hl-img" :src="h.image" mode="aspectFill" />
              <view class="hl-name"><Icon name="pin" :size="12" color="#e11d48" /> {{ h.name }}</view>
              <view class="hl-desc">{{ h.desc }}</view>
            </view>
          </view>
        </view>

        <view class="block map-block">
          <view class="outline-row">
            <view class="block-title"><Icon name="nav" :size="16" color="var(--accent)" /> 行程地图预览</view>
            <view class="steps">
              <view v-for="(d, i) in DAY_OUTLINE" :key="d.day" class="step">
                <view class="outline-no">{{ i + 1 }}</view>
                <view><view class="outline-day">{{ d.day }}</view><view class="outline-text">{{ d.text }}</view></view>
              </view>
            </view>
          </view>
          <image class="mini-map" :src="ROUTE_MAP_WIDE" mode="aspectFill" @click="openMap" />
        </view>
      </template>
    </view>
  </scroll-view>
</template>

<script setup lang="ts">
import { ref } from "vue";
import Icon from "./Icon.vue";
import { CITY, coverFor, DAY_OUTLINE, HERO, HIGHLIGHTS, PLANS, REQUIREMENT, ROUTE_MAP_WIDE } from "../demo/xian";
import { shortDate } from "../utils/format";

/**
 * 对话页右侧（电脑）：当前会话生成过行程时显示这个行程的逐日安排，否则显示三套示例方案、亮点和地图预览。
 * trip 是会话里最后一张行程卡的数据。
 */
defineProps<{ trip: any | null }>();

const TABS = ["推荐方案 (3)", "行程对比", "费用明细"];
const tab = ref(TABS[0]);
const COMPARE = [
  ["住宿", "经济型", "舒适型", "高端"],
  ["交通", "公共交通", "专车/包车", "专车 + 讲解"],
  ["景点", "经典必玩", "精选 + 夜游", "小众 + 深度"],
  ["节奏", "紧凑", "轻松", "轻松"],
];
const COSTS = [
  ["机票（往返）", "¥1,100", "¥1,300", "¥1,600"],
  ["住宿（3 晚）", "¥600", "¥1,000", "¥2,100"],
  ["门票", "¥350", "¥380", "¥520"],
  ["餐饮", "¥450", "¥650", "¥900"],
  ["市内交通", "¥300", "¥470", "¥480"],
];

function openTrip(id: number | string) {
  uni.navigateTo({ url: `/pages/trips/detail?id=${id}` });
}

function openMap() {
  uni.navigateTo({ url: "/pages/plan/map" });
}
</script>

<style scoped>
.panel-scroll { height: 100%; }
.pp { padding: 16px 20px 24px; display: flex; flex-direction: column; gap: 16px; }
.hero { position: relative; border-radius: 18px; overflow: hidden; min-height: 200px; }
.hero-img { position: absolute; inset: 0; width: 100%; height: 100%; }
.hero-shade { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0.55) 50%, rgba(255, 255, 255, 0)); }
.hero-text { position: relative; padding: 26px 26px 16px; color: #111827; }
.hero-title { font-size: 26px; font-weight: 800; }
.hero-sub { font-size: 14px; color: #374151; margin-top: 6px; }
.sample { font-size: 11px; padding: 1px 6px; border-radius: 999px; background: rgba(0, 0, 0, 0.08); }
.req {
  position: relative;
  margin: 10px 16px 16px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  background: #fff;
  border-radius: 14px;
  padding: 14px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.12);
  color: #1b2333;
}
.req-item { display: flex; gap: 8px; align-items: flex-start; }
.req-label { font-size: 12px; color: #687386; }
.req-value { font-size: 13px; font-weight: 600; margin-top: 2px; }
.req-note { font-size: 11px; color: #687386; }
.tabs { display: flex; gap: 10px; }
.tab { padding: 7px 18px; border-radius: 999px; font-size: 14px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.tab.on { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }
.plans { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.plan { --tone: #16a34a; background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 16px; display: flex; flex-direction: column; gap: 6px; }
.plan.blue { --tone: #2563eb; }
.plan.violet { --tone: #7c3aed; }
.plan.rec { border: 2px solid var(--tone); box-shadow: 0 8px 24px rgba(37, 99, 235, 0.15); }
.plan-head { display: flex; justify-content: space-between; align-items: center; }
.plan-name { display: flex; align-items: center; gap: 6px; font-weight: 800; font-size: 16px; color: var(--tone); }
.badge { font-size: 11px; padding: 2px 8px; border-radius: 6px; background: var(--tone); color: #fff; }
.plan-price { font-size: 24px; font-weight: 800; color: var(--tone); font-variant-numeric: tabular-nums; }
.per { font-size: 13px; font-weight: 400; }
.point { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.plan-img { width: 100%; height: 96px; border-radius: 10px; margin-top: 6px; }
.plan-btn { margin: 4px 0 0; }
.plan-btn.ghost { color: var(--tone); border-color: var(--tone); }
.block { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 14px 16px; }
.block-title { display: flex; align-items: center; gap: 6px; font-weight: 700; margin-bottom: 10px; }
.highlights { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; }
.hl-img { width: 100%; height: 80px; border-radius: 10px; }
.hl-name { font-size: 13px; font-weight: 700; margin-top: 4px; display: flex; align-items: center; gap: 2px; }
.hl-desc { font-size: 12px; color: var(--muted); }
.map-block { display: flex; gap: 14px; align-items: stretch; }
.outline-row { flex: 1; min-width: 0; }
.steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.step { display: flex; gap: 6px; }
.outline { display: flex; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--line); }
.outline:last-of-type { border-bottom: none; }
.outline-no { width: 22px; height: 22px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.outline-day { font-size: 13px; font-weight: 700; color: var(--accent); }
.outline-text { font-size: 12px; color: var(--ink); }
.mini-map { width: 220px; height: 120px; border-radius: 12px; flex-shrink: 0; cursor: pointer; }
.open-btn { margin: 12px 0 0; }
.compare { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; overflow: hidden; }
.cmp-row { display: grid; grid-template-columns: 1.2fr 1fr 1fr 1fr; padding: 10px 16px; border-bottom: 1px solid var(--line); font-size: 14px; font-variant-numeric: tabular-nums; }
.cmp-row:last-child { border-bottom: none; }
.cmp-row.head { background: var(--surface-2); font-weight: 600; }
@media (max-width: 1380px) {
  .req { grid-template-columns: repeat(2, 1fr); }
  .highlights { grid-template-columns: repeat(3, 1fr); }
  .steps { grid-template-columns: repeat(2, 1fr); }
}
</style>
