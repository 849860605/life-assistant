<template>
  <scroll-view scroll-y class="panel-scroll">
    <view class="pp">
      <!-- 当前会话已经生成了行程：显示这个行程 -->
      <template v-if="trip">
        <view class="hero">
          <image v-if="cover" class="hero-img" :src="cover" mode="aspectFill" />
          <view v-else class="hero-img blank" />
          <view class="hero-shade" />
          <view class="hero-text">
            <view class="hero-title">{{ trip.title }}</view>
            <view class="hero-sub">{{ trip.destination }} · {{ shortDate(trip.startDate) }} – {{ shortDate(trip.endDate) }} · {{ days.length }} 天 · {{ trip.status === "DRAFT" ? "草稿" : "已确认" }}</view>
          </view>
        </view>
        <view class="block">
          <view class="block-title">逐日安排</view>
          <view v-for="(d, i) in days" :key="d.date" class="outline">
            <view class="outline-no">{{ i + 1 }}</view>
            <view>
              <view class="outline-day">第{{ i + 1 }}天 · {{ shortDate(d.date) }} <text v-if="d.theme" class="muted">{{ d.theme }}</text></view>
              <view class="outline-text">{{ d.items.filter((x: any) => x.type !== "TRANSPORT").map((x: any) => x.name).join(" → ") }}</view>
            </view>
          </view>
          <button class="btn open-btn" @click="openTrip(trip.tripId)">打开行程编辑</button>
        </view>
        <view v-if="highlights.length" class="block">
          <view class="block-title"><Icon name="sparkle" :size="16" color="var(--accent)" /> 行程亮点</view>
          <view class="highlights">
            <view v-for="h in highlights" :key="h.id" class="hl">
              <image class="hl-img" :src="h.photo!" mode="aspectFill" />
              <view class="hl-name"><Icon name="pin" :size="12" color="#e11d48" /> {{ h.name }}</view>
              <view v-if="h.rating" class="hl-desc">★ {{ h.rating }} 高德评分</view>
            </view>
          </view>
        </view>
        <view v-if="mapSrc" class="block map-block">
          <view class="outline-row">
            <view class="block-title"><Icon name="nav" :size="16" color="var(--accent)" /> 第 1 天路线</view>
            <view class="map-note">{{ travelNote }}</view>
          </view>
          <image class="mini-map" :src="mapSrc" mode="aspectFill" @click="openMap" />
        </view>
      </template>

      <!-- 当前会话给了三套方案：方案概要和逐日对比 -->
      <template v-else-if="plans && plans.options && plans.options.length">
        <view class="hero">
          <image v-if="cover" class="hero-img" :src="cover" mode="aspectFill" />
          <view v-else class="hero-img blank" />
          <view class="hero-shade" />
          <view class="hero-text">
            <view class="hero-title">{{ plans.destination }} {{ plans.days }} 天 · 推荐方案</view>
            <view class="hero-sub">
              <text v-if="plans.startDate">{{ shortDate(plans.startDate) }} – {{ shortDate(plans.endDate) }} · </text>根据你的需求生成了 {{ plans.options.length }} 套不同风格的方案，选一套展开成完整行程
            </view>
          </view>
        </view>

        <view class="tabs">
          <text v-for="t in TABS" :key="t" class="tab" :class="{ on: tab === t }" @click="tab = t">{{ t === TABS[0] ? `${t} (${plans.options.length})` : t }}</text>
        </view>

        <view v-if="tab === TABS[0]" class="plans">
          <view v-for="(p, i) in plans.options" :key="p.key || i" class="plan" :class="TONES[i % TONES.length]">
            <view class="plan-head">
              <view class="plan-name"><Icon :name="ICONS[i % ICONS.length]" :size="18" color="var(--tone)" /><text>{{ p.title }}</text></view>
            </view>
            <view v-if="p.style" class="plan-style">{{ p.style }}</view>
            <view v-if="p.budgetPerPerson" class="plan-price"><text class="per">人均约 </text>¥{{ Number(p.budgetPerPerson).toLocaleString() }}</view>
            <view v-for="pt in p.highlights || []" :key="pt" class="point"><Icon name="check" :size="14" color="var(--tone)" /><text>{{ pt }}</text></view>
            <view v-if="p.transport" class="point muted"><Icon name="plane" :size="14" color="var(--muted)" /><text>{{ p.transport }}</text></view>
            <view v-if="p.hotel" class="point muted"><Icon name="bed" :size="14" color="var(--muted)" /><text>{{ p.hotel }}</text></view>
            <button class="btn plan-btn" :class="{ ghost: i !== 0 }" @click="choose(p.title)">选这个方案</button>
          </view>
        </view>
        <view v-else class="compare">
          <view class="cmp-row head" :style="cmpCols"><text>逐日安排</text><text v-for="(p, i) in plans.options" :key="p.key || i">{{ p.title }}</text></view>
          <view v-for="n in plans.days" :key="n" class="cmp-row" :style="cmpCols">
            <text>第 {{ n }} 天</text>
            <text v-for="(p, i) in plans.options" :key="p.key || i">{{ dayOf(p, n) }}</text>
          </view>
          <view class="cmp-row" :style="cmpCols">
            <text>人均预算</text><text v-for="(p, i) in plans.options" :key="p.key || i">{{ p.budgetPerPerson ? `¥${Number(p.budgetPerPerson).toLocaleString()}` : "—" }}</text>
          </view>
        </view>
      </template>

      <!-- 还没有行程和方案：说明能做什么 -->
      <template v-else>
        <view class="hero">
          <image v-if="cover" class="hero-img" :src="cover" mode="aspectFill" />
          <view v-else class="hero-img blank" />
          <view class="hero-shade" />
          <view class="hero-text">
            <view class="hero-title">说出想去哪，AI 帮你规划</view>
            <view class="hero-sub">规划好的行程和方案会显示在这里，可以直接打开编辑</view>
          </view>
        </view>
        <view class="block">
          <view class="block-title"><Icon name="sparkle" :size="16" color="var(--accent)" /> 我能帮你</view>
          <view class="abilities">
            <view v-for="a in ABILITIES" :key="a.label" class="ability">
              <Icon :name="a.icon" :size="18" color="var(--accent)" />
              <view><view class="ab-name">{{ a.label }}</view><view class="ab-desc">{{ a.desc }}</view></view>
            </view>
          </view>
        </view>
        <view class="block">
          <view class="block-title">试试这样问</view>
          <view v-for="q in examples" :key="q" class="example" @click="emit('send', q)">
            <text>{{ q }}</text>
            <Icon name="right" :size="16" color="var(--muted)" />
          </view>
        </view>
      </template>
    </view>
  </scroll-view>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { api } from "../api";
import type { ItineraryDay, ItineraryItem } from "../api/types";
import Icon from "./Icon.vue";
import { cityCover } from "../utils/cover";
import { shortDate } from "../utils/format";
import { cityOf, currentCity, dayMapUrl, dayTravel, duration, located } from "../utils/place";

/**
 * 对话页右侧（电脑）：当前会话生成过行程时显示这个行程（逐日安排、亮点照片、第一天路线图）；
 * 给过三套方案时显示方案概要和逐日对比，选中后把选择发到对话里；都没有时说明能做什么、给几个示例问题。
 * trip 是会话里最后一张行程卡的数据，plans 是最后一张 plan_options 卡的数据。
 */
const props = defineProps<{ trip: any | null; plans?: any | null }>();
const emit = defineEmits<{ (e: "send", text: string): void }>();

const TABS = ["推荐方案", "逐日对比"];
const TONES = ["green", "blue", "violet"];
const ICONS = ["wallet", "star", "crown"];
const ABILITIES = [
  { label: "定制行程", desc: "先问清需求，再按天排好路线", icon: "route" },
  { label: "机票酒店比价", desc: "多个平台同时查，按价格排", icon: "plane" },
  { label: "美食推荐", desc: "大众点评和携程的评分合在一起看", icon: "food" },
  { label: "天气提醒", desc: "出发前看天气，提醒带什么", icon: "weather" },
];
const tab = ref(TABS[0]);
const city = currentCity();
const examples = [`帮我规划${city} 3 天 2 晚，预算每人 3000`, `${city}有哪些适合带老人去的景点？`, `${city}本地人常去的小吃店有哪些？`];

const cover = ref("");
/** 从后端取到的结构化行程（带坐标和照片），比卡片里的数据全 */
const loadedDays = ref<ItineraryDay[] | null>(null);
const days = computed<ItineraryDay[]>(() => loadedDays.value || props.trip?.days || []);
const highlights = computed<ItineraryItem[]>(() =>
  days.value.flatMap((d) => d.items).filter((i) => i.photo && i.type === "SPOT").slice(0, 5));
const mapSrc = computed(() => dayMapUrl(days.value[0], 220, 120));
const travelNote = computed(() => {
  const t = dayTravel(days.value[0]);
  const stops = located(days.value[0]).length;
  return t.minutes ? `${stops} 个地点 · 路上约 ${duration(t.minutes)} · ${t.km} 公里` : `${stops} 个地点`;
});
const cmpCols = computed(() => ({ gridTemplateColumns: `1fr repeat(${props.plans?.options?.length || 3}, 1.4fr)` }));

/** 封面跟着当前显示的内容：行程或方案的目的地，否则当前城市。 */
watch(
  () => [props.trip?.destination, props.plans?.destination],
  async () => {
    const dest = props.trip?.destination || props.plans?.destination;
    cover.value = await cityCover(dest ? cityOf(dest) : city);
  },
  { immediate: true },
);

/** 行程卡变了就取完整行程；还没补坐标时让后端补上。 */
watch(
  () => props.trip?.tripId,
  async (id) => {
    loadedDays.value = null;
    if (!id) return;
    try {
      let detail = await api.trip(id);
      if (detail.version.itinerary && !detail.version.itinerary.geo) detail = await api.tripGeo(id);
      if (props.trip?.tripId === id && detail.version.itinerary) loadedDays.value = detail.version.itinerary.days;
    } catch {
      // 取不到就用卡片里的数据
    }
  },
  { immediate: true },
);

function dayOf(p: any, n: number): string {
  const d = (p.outline || []).find((x: any) => x.day === n);
  if (!d) return "—";
  return d.title + (d.places?.length ? `：${d.places.join("、")}` : "");
}

function choose(title: string) {
  emit("send", `就按「${title}」方案，展开成完整行程`);
}

function openTrip(id: number | string) {
  uni.navigateTo({ url: `/pages/trips/detail?id=${id}` });
}

function openMap() {
  uni.navigateTo({ url: `/pages/plan/map?id=${props.trip?.tripId}&day=1` });
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
.hero-img.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); }
.tabs { display: flex; gap: 10px; }
.tab { padding: 7px 18px; border-radius: 999px; font-size: 14px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.tab.on { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }
.plans { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.plan { --tone: #16a34a; background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 16px; display: flex; flex-direction: column; gap: 6px; }
.plan.blue { --tone: #2563eb; }
.plan.violet { --tone: #7c3aed; }
.plan-head { display: flex; justify-content: space-between; align-items: center; }
.plan-name { display: flex; align-items: center; gap: 6px; font-weight: 800; font-size: 16px; color: var(--tone); }
.plan-style { font-size: 12px; color: var(--muted); }
.plan-price { font-size: 24px; font-weight: 800; color: var(--tone); font-variant-numeric: tabular-nums; }
.per { font-size: 13px; font-weight: 400; }
.point { display: flex; align-items: center; gap: 6px; font-size: 13px; }
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
.outline { display: flex; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--line); }
.outline:last-of-type { border-bottom: none; }
.outline-no { width: 22px; height: 22px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.outline-day { font-size: 13px; font-weight: 700; color: var(--accent); }
.outline-text { font-size: 12px; color: var(--ink); }
.map-note { font-size: 13px; color: var(--muted); }
.abilities { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.ability { display: flex; gap: 10px; align-items: flex-start; }
.ab-name { font-weight: 600; font-size: 14px; }
.ab-desc { font-size: 12px; color: var(--muted); }
.example { display: flex; align-items: center; justify-content: space-between; padding: 10px 2px; border-bottom: 1px solid var(--line); font-size: 14px; cursor: pointer; }
.example:last-child { border-bottom: none; }
.example:hover { color: var(--accent); }
.mini-map { width: 220px; height: 120px; border-radius: 12px; flex-shrink: 0; cursor: pointer; }
.open-btn { margin: 12px 0 0; }
.compare { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; overflow: hidden; }
.cmp-row { display: grid; gap: 10px; padding: 10px 16px; border-bottom: 1px solid var(--line); font-size: 14px; font-variant-numeric: tabular-nums; }
.cmp-row:last-child { border-bottom: none; }
.cmp-row.head { background: var(--surface-2); font-weight: 600; }
@media (max-width: 1380px) {
    .highlights { grid-template-columns: repeat(3, 1fr); }
}
</style>
