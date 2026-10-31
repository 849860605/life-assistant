<template>
  <Shell active="home">
    <view v-if="wide" class="desk">
      <view class="banner">
        <image class="banner-img" :src="HERO" mode="aspectFill" />
        <view class="banner-shade" />
        <view class="banner-body">
          <view class="banner-title">用 AI 规划更好的{{ CITY }}之旅</view>
          <view class="banner-sub">告诉我你的想法，AI 为你定制行程、推荐景点美食、查询机票酒店、规划路线</view>
          <view class="features">
            <view v-for="f in FEATURES" :key="f.label" class="feature"><Icon :name="f.icon" :size="14" color="#fff" /><text>{{ f.label }}</text></view>
          </view>
          <view class="big-ask">
            <input id="desk-ask" v-model="question" class="big-input" confirm-type="send" :placeholder="`例如：帮我规划${CITY}4天3晚，预算5000元，带父母，想看历史文化和美食`" @confirm="ask" />
            <view class="big-send" @click="ask"><Icon name="send" :size="20" color="#fff" /></view>
          </view>
          <view class="quick">
            <text v-for="q in QUICK" :key="q" class="quick-chip" @click="openChat(q)">{{ q }}</text>
          </view>
        </view>
      </view>

      <view class="grid">
        <view class="col-main">
          <view class="panel entries-row">
            <view v-for="e in DESK_ENTRIES" :key="e.label" class="d-entry" @click="e.go()">
              <view class="d-entry-icon" :style="{ background: e.bg }"><Icon :name="e.icon" :size="24" :color="e.color" /></view>
              <text class="d-entry-label">{{ e.label }}</text>
              <text class="d-entry-sub">{{ e.sub }}</text>
            </view>
          </view>

          <view class="two">
            <view class="panel">
              <view class="panel-head"><text class="panel-title">{{ CITY }}热门景点</text><text class="more" @click="openChat(`${CITY}有哪些值得去的景点？`)">查看更多 ›</text></view>
              <view class="spots">
                <view v-for="sp in HOT_SPOTS" :key="sp.name" class="spot" @click="openChat(`介绍一下${sp.name}，怎么玩`)">
                  <image class="spot-img" :src="sp.image" mode="aspectFill" />
                  <view class="spot-body">
                    <view class="spot-name">{{ sp.name }}</view>
                    <view class="spot-rating">★ {{ sp.rating }} <text class="muted">高德评分</text></view>
                    <view class="spot-tags"><text v-for="t in sp.tags" :key="t" class="mini-tag">{{ t }}</text></view>
                  </view>
                </view>
              </view>
            </view>

            <view class="panel">
              <view class="panel-head"><text class="panel-title">推荐行程方案</text><text class="more" @click="go('/pages/plan/plans')">查看更多方案 ›</text></view>
              <view class="plan-tabs">
                <view v-for="p in PLANS" :key="p.key" class="plan-tab" :class="[p.tone, { on: p.key === planKey }]" @click="planKey = p.key">
                  <view class="plan-tab-name"><Icon :name="p.icon" :size="13" :color="p.key === planKey ? '#fff' : 'var(--tone)'" /><text>{{ p.name }}</text></view>
                  <view class="plan-tab-price">¥{{ p.price.toLocaleString() }}<text class="per"> /人</text></view>
                  <view class="plan-tab-line">{{ p.tagline }}</view>
                </view>
              </view>
              <view class="plan-title-row">
                <text class="plan-title">{{ CITY }} 4 天 3 晚 · {{ currentPlan.name }}</text>
                <view class="save" @click="openDemoTrip"><Icon name="heart" :size="13" color="var(--accent)" /><text>保存方案</text></view>
              </view>
              <view class="mini-days">
                <view v-for="(d, i) in DEMO_TRIP.days" :key="d.date" class="mini-day" :class="{ on: i === miniDay }" @click="miniDay = i">
                  <text class="mini-day-no">D{{ i + 1 }}</text><text class="mini-day-date">{{ shortDate(d.date) }}</text>
                </view>
              </view>
              <view class="mini-timeline">
                <view v-for="(it, i) in DEMO_TRIP.days[miniDay].items.slice(0, 3)" :key="it.id" class="mini-stop" @click="openDemoTrip">
                  <view class="mini-no">{{ i + 1 }}</view>
                  <image v-if="imageFor(it.name)" class="mini-img" :src="imageFor(it.name)" mode="aspectFill" />
                  <view class="mini-body">
                    <view class="mini-name">{{ it.name }}</view>
                    <view class="mini-time">{{ it.start }} – {{ it.end }}</view>
                    <view class="mini-note">{{ it.note }}</view>
                  </view>
                </view>
              </view>
            </view>
          </view>
        </view>

        <view class="col-side">
          <view class="panel">
            <view class="panel-head"><text class="panel-title">{{ CITY }}地图路线</text><text class="more" @click="go('/pages/plan/map')">全屏地图 ›</text></view>
            <image class="home-map" :src="HOME_MAP" mode="aspectFill" />
            <view class="modes">
              <view class="mode on"><Icon name="car" :size="18" color="#fff" /><view><view>驾车</view><view class="mode-sub">{{ HOME_ROUTE.drive }}</view></view></view>
              <view class="mode"><Icon name="route" :size="18" color="var(--ink)" /><view><view>骑行</view><view class="mode-sub">{{ HOME_ROUTE.bike }}</view></view></view>
              <view class="mode"><Icon name="bus" :size="18" color="var(--ink)" /><view><view>公共交通</view><view class="mode-sub">{{ HOME_ROUTE.transit }}</view></view></view>
            </view>
          </view>

          <view class="panel">
            <view class="panel-head"><text class="panel-title">附近美食推荐</text><text class="more" @click="go('/pages/search/food')">查看更多 ›</text></view>
            <view class="foods">
              <view v-for="f in FOODS.slice(0, 4)" :key="f.name" class="food" @click="go('/pages/search/food')">
                <image class="food-img" :src="f.image" mode="aspectFill" />
                <view class="food-name">{{ f.name }}</view>
                <view class="food-meta"><text class="star">★ {{ f.rating }}</text> ¥{{ f.avgPrice }}/人</view>
                <text class="mini-tag">{{ f.area }}</text>
              </view>
            </view>
          </view>

          <view class="panel">
            <view class="seg">
              <text v-for="t in BOOK_TABS" :key="t" class="seg-item" :class="{ on: bookTab === t }" @click="bookTab = t">{{ t }}</text>
            </view>
            <template v-if="bookTab === '机票推荐'">
              <view class="route-row">
                <text class="route-city">深圳</text><Icon name="right" :size="14" color="var(--muted)" /><text class="route-city">{{ CITY }}</text>
                <text class="route-meta">3月10日（周二）</text><text class="route-meta">2成人 1儿童</text>
              </view>
              <view v-for="f in FLIGHTS.slice(0, 3)" :key="f.flightNo" class="d-flight">
                <view class="d-logo" :style="{ background: f.color }">{{ f.short }}</view>
                <view class="d-flight-body">
                  <view class="d-flight-name">{{ f.airline }} <text class="muted">{{ f.flightNo }}</text></view>
                  <view class="d-flight-time">{{ f.dep }} – {{ f.arr }} <text class="muted">直飞 {{ f.duration }}</text></view>
                </view>
                <text class="price">¥{{ f.price }}<text class="qi">起</text></text>
                <button class="btn small" @click="go('/pages/search/flight')">查看</button>
              </view>
            </template>
            <template v-else-if="bookTab === '酒店推荐'">
              <view v-for="h in HOTELS.slice(0, 3)" :key="h.name" class="d-flight">
                <image class="d-hotel-img" :src="h.image" mode="aspectFill" />
                <view class="d-flight-body">
                  <view class="d-flight-name">{{ h.name }}</view>
                  <view class="d-flight-time"><text class="star">★ {{ h.rating }}</text> <text class="muted">{{ h.distance }}</text></view>
                </view>
                <text class="price">¥{{ h.price }}<text class="qi">起</text></text>
                <button class="btn small" @click="go('/pages/search/flight?tab=hotel')">查看</button>
              </view>
            </template>
            <view v-else class="muted ticket-empty">门票比价还在找数据源</view>
            <view class="sample-note">示例价格，真实比价在对话里查询</view>
          </view>
        </view>
      </view>
    </view>

    <view v-else class="home">
    <view class="topbar">
      <view class="brand">
        <view class="logo"><Icon name="chat" :size="16" color="#fff" /></view>
        <text class="brand-name">生活助手</text>
      </view>
      <view class="topbar-link" @click="openChat()">
        <Icon name="chat" :size="20" color="var(--muted)" />
      </view>
    </view>

    <view class="page">
      <view class="hero">
        <image class="hero-img" :src="HERO" mode="aspectFill" />
        <view class="hero-text">
          <view class="hero-title">用 AI 规划更好的旅行</view>
          <view class="hero-sub">告诉我你想去哪、什么时候出发、预算多少</view>
          <view class="hero-sub tight">剩下的交给 AI</view>
        </view>
        <view class="ask">
          <input
            id="home-ask"
            v-model="question"
            class="ask-input"
            confirm-type="send"
            placeholder="例如：帮我规划成都4天3晚，适合亲子出游，预算每人5000元"
            @confirm="ask"
          />
          <view class="ask-send" @click="ask"><Icon name="send" :size="18" color="#fff" /></view>
        </view>
      </view>

      <view class="entries">
        <view v-for="e in ENTRIES" :key="e.label" class="entry" @click="e.go()">
          <view class="entry-icon" :style="{ background: e.bg }"><Icon :name="e.icon" :size="22" :color="e.color" /></view>
          <text class="entry-label">{{ e.label }}</text>
          <text class="entry-sub">{{ e.sub }}</text>
        </view>
      </view>

      <view class="section-head">
        <text class="section-name">大家都在问</text>
      </view>
      <view class="asks">
        <text v-for="q in POPULAR" :key="q" class="ask-chip" @click="openChat(q)">{{ q }}</text>
      </view>

      <view class="section-head">
        <text class="section-name">最近的行程</text>
        <text class="section-more" @click="goTrips">全部</text>
      </view>
      <view class="recent" @click="openDemoTrip">
        <image class="recent-img" :src="coverFor('西安')" mode="aspectFill" />
        <view class="recent-body">
          <view class="recent-title">西安4天3晚 · 舒适方案</view>
          <view class="recent-meta">3月10日 – 3月13日 · 2 大 1 小</view>
          <view class="recent-tags"><text class="tag">示例</text><text class="tag blue">进行中</text></view>
        </view>
        <Icon name="right" :size="18" color="var(--muted)" />
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { computed } from "vue";
import Icon from "../../components/Icon.vue";
import Shell from "../../components/Shell.vue";
import {
  CITY, coverFor, DEMO_TRIP, FLIGHTS, FOODS, HERO, HOME_MAP, HOME_ROUTE, HOT_SPOTS, HOTELS, imageFor, PLANS,
} from "../../demo/xian";
import { shortDate } from "../../utils/format";
import { useWide } from "../../utils/screen";

/** 首页：电脑上是总览面板（大图提问、入口、热门景点、推荐方案、地图、美食、比价），手机上是原型①的单列布局。 */
const wide = useWide();
const question = ref("");
const planKey = ref("comfort");
const miniDay = ref(0);
const bookTab = ref("机票推荐");
const currentPlan = computed(() => PLANS.find((p) => p.key === planKey.value)!);

const FEATURES = [
  { label: "个性化行程", icon: "checkCircle" },
  { label: "智能路线规划", icon: "route" },
  { label: "机票酒店比价", icon: "plane" },
  { label: "美食推荐", icon: "food" },
  { label: "实时天气", icon: "weather" },
];
const QUICK = [`${CITY}4天3晚经典路线`, "亲子游攻略", "美食之旅", "兵马俑一日游", "预算3000元方案", "带老人出行"];
const BOOK_TABS = ["机票推荐", "酒店推荐", "门票推荐"];

const POPULAR = ["北京周末去哪玩？", "帮我规划成都4日游", "推荐适合亲子的海边度假", "帮我比较机票和酒店价格"];

const DESK_ENTRIES = [
  { label: "行程规划", sub: "AI 定制路线", icon: "umbrella", color: "#16a34a", bg: "#e7f6ec", go: () => openChat("帮我规划一次旅行") },
  { label: "机票比价", sub: "多平台价格对比", icon: "plane", color: "#2563eb", bg: "#e6eefd", go: () => go("/pages/search/flight") },
  { label: "酒店比价", sub: "真实房价与评价", icon: "bed", color: "#7c3aed", bg: "#f0e9fd", go: () => go("/pages/search/flight?tab=hotel") },
  { label: "景点推荐", sub: "热门景点攻略", icon: "pin", color: "#e11d48", bg: "#fde8ec", go: () => openChat(`${CITY}有哪些值得去的景点？`) },
  { label: "美食推荐", sub: "地道本地美食", icon: "food", color: "#ea580c", bg: "#fff1e6", go: () => go("/pages/search/food") },
  { label: "地图路线", sub: "可视化行程", icon: "nav", color: "#0d9488", bg: "#e2f5f2", go: () => go("/pages/plan/map") },
  { label: "天气查询", sub: "实时天气建议", icon: "weather", color: "#0284c7", bg: "#e3f2fb", go: () => openChat(`${CITY}这周天气怎么样？`) },
];

const ENTRIES = [
  { label: "旅行规划", sub: "智能生成行程", icon: "umbrella", color: "#16a34a", bg: "#e7f6ec", go: () => openChat("帮我规划一次旅行") },
  { label: "机票比价", sub: "多平台价格对比", icon: "plane", color: "#2563eb", bg: "#e6eefd", go: () => go("/pages/search/flight") },
  { label: "酒店比价", sub: "精选住宿推荐", icon: "bed", color: "#7c3aed", bg: "#f0e9fd", go: () => go("/pages/search/flight?tab=hotel") },
  { label: "美食推荐", sub: "当地特色美食", icon: "pin", color: "#e11d48", bg: "#fde8ec", go: () => go("/pages/search/food") },
  { label: "地图出行", sub: "路线规划导航", icon: "nav", color: "#0d9488", bg: "#e2f5f2", go: () => go("/pages/plan/map") },
  { label: "天气查询", sub: "实时天气提醒", icon: "weather", color: "#0284c7", bg: "#e3f2fb", go: () => openChat("这周末天气怎么样，适合出去玩吗？") },
];

function go(url: string) {
  uni.navigateTo({ url });
}

/** 去对话页，带上问题就直接发出去。 */
function openChat(q?: string) {
  go("/pages/chat/chat" + (q ? `?q=${encodeURIComponent(q)}` : ""));
}

function ask() {
  const q = question.value.trim();
  if (!q) return;
  question.value = "";
  openChat(q);
}

function goTrips() {
  uni.switchTab({ url: "/pages/trips/trips" });
}

function openDemoTrip() {
  go("/pages/trips/detail?id=demo");
}
</script>

<style scoped>
/* ===== 电脑：总览面板 ===== */
.desk { padding: 20px 24px 28px; display: flex; flex-direction: column; gap: 18px; }
.banner { position: relative; border-radius: 20px; overflow: hidden; min-height: 340px; }
.banner-img { position: absolute; inset: 0; width: 100%; height: 100%; }
.banner-shade { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(10, 18, 40, 0.78), rgba(10, 18, 40, 0.35) 60%, rgba(10, 18, 40, 0.1)); }
.banner-body { position: relative; padding: 40px 44px 30px; color: #fff; display: flex; flex-direction: column; gap: 14px; max-width: 860px; }
.banner-title { font-size: 40px; font-weight: 800; letter-spacing: 0.02em; text-wrap: balance; }
.banner-sub { font-size: 17px; opacity: 0.92; }
.features { display: flex; flex-wrap: wrap; gap: 10px; }
.feature { display: flex; align-items: center; gap: 6px; font-size: 13px; padding: 6px 12px; border-radius: 999px; background: rgba(255, 255, 255, 0.16); border: 1px solid rgba(255, 255, 255, 0.3); }
.big-ask { display: flex; align-items: center; gap: 10px; background: #fff; border-radius: 16px; padding: 10px 10px 10px 20px; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25); }
.big-input { flex: 1; font-size: 16px; color: #111827; height: 40px; }
.big-send { width: 48px; height: 48px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; cursor: pointer; }
.quick { display: flex; flex-wrap: wrap; gap: 10px; }
.quick-chip { font-size: 13px; padding: 7px 14px; border-radius: 999px; background: rgba(255, 255, 255, 0.92); color: #1b2333; cursor: pointer; }

.grid { display: grid; grid-template-columns: minmax(0, 1fr) 420px; gap: 18px; align-items: start; }
.col-main, .col-side { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.panel { background: var(--surface); border-radius: 16px; padding: 16px; border: 1px solid var(--line); }
.panel-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }
.panel-title { font-size: 16px; font-weight: 700; }
.more { font-size: 13px; color: var(--accent); cursor: pointer; }
.entries-row { display: grid; grid-template-columns: repeat(7, 1fr); gap: 8px; }
.d-entry { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 12px; border-radius: 12px; cursor: pointer; }
.d-entry:hover { background: var(--surface-2); }
.d-entry-icon { width: 44px; height: 44px; border-radius: 12px; display: flex; align-items: center; justify-content: center; margin-bottom: 4px; }
.d-entry-label { font-weight: 700; font-size: 15px; }
.d-entry-sub { font-size: 12px; color: var(--muted); }

.two { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; }
.spots { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.spot { border-radius: 12px; overflow: hidden; border: 1px solid var(--line); cursor: pointer; }
.spot-img { width: 100%; height: 100px; display: block; }
.spot-body { padding: 8px; }
.spot-name { font-weight: 700; font-size: 14px; }
.spot-rating { font-size: 12px; color: var(--price); }
.spot-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.mini-tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); color: var(--muted); }

.plan-tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.plan-tab { --tone: #16a34a; padding: 10px; border-radius: 12px; border: 1px solid var(--line); background: var(--surface-2); cursor: pointer; }
.plan-tab.blue { --tone: #2563eb; }
.plan-tab.violet { --tone: #7c3aed; }
.plan-tab.on { background: var(--tone); border-color: var(--tone); color: #fff; }
.plan-tab-name { display: flex; align-items: center; gap: 4px; font-size: 13px; font-weight: 700; }
.plan-tab-price { font-size: 18px; font-weight: 800; margin-top: 2px; font-variant-numeric: tabular-nums; }
.plan-tab .per { font-size: 11px; font-weight: 400; opacity: 0.8; }
.plan-tab-line { font-size: 11px; opacity: 0.8; }
.plan-title-row { display: flex; align-items: center; justify-content: space-between; margin: 14px 0 10px; }
.plan-title { font-weight: 700; }
.save { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--accent); border: 1px solid var(--accent); padding: 4px 10px; border-radius: 8px; cursor: pointer; }
.mini-days { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-bottom: 10px; }
.mini-day { display: flex; flex-direction: column; align-items: center; padding: 6px 0; border-radius: 10px; background: var(--surface-2); cursor: pointer; }
.mini-day.on { background: var(--accent); color: #fff; }
.mini-day-no { font-weight: 700; font-size: 14px; }
.mini-day-date { font-size: 11px; opacity: 0.8; }
.mini-timeline { display: flex; flex-direction: column; gap: 8px; }
.mini-stop { display: flex; gap: 10px; align-items: center; padding: 8px; border-radius: 12px; border: 1px solid var(--line); cursor: pointer; }
.mini-no { width: 20px; height: 20px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.mini-img { width: 72px; height: 54px; border-radius: 8px; flex-shrink: 0; }
.mini-body { min-width: 0; }
.mini-name { font-weight: 700; font-size: 14px; }
.mini-time { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.mini-note { font-size: 12px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.home-map { width: 100%; height: 190px; border-radius: 12px; display: block; }
.modes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 10px; }
.mode { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 10px; border: 1px solid var(--line); font-size: 13px; }
.mode.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.mode-sub { font-size: 11px; opacity: 0.8; }
.foods { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.food { cursor: pointer; display: flex; flex-direction: column; gap: 3px; align-items: flex-start; }
.food-img { width: 100%; height: 76px; border-radius: 10px; }
.food-name { font-size: 13px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.food-meta { font-size: 11px; color: var(--muted); }
.star { color: var(--price); }
.seg { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; margin-bottom: 10px; }
.seg-item { flex: 1; text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; cursor: pointer; color: var(--muted); }
.seg-item.on { background: var(--accent); color: #fff; font-weight: 600; }
.route-row { display: flex; align-items: center; gap: 8px; padding: 6px 2px 10px; border-bottom: 1px solid var(--line); }
.route-city { font-weight: 700; }
.route-meta { font-size: 12px; color: var(--muted); margin-left: 8px; }
.d-flight { display: flex; align-items: center; gap: 10px; padding: 10px 2px; border-bottom: 1px solid var(--line); }
.d-flight .btn { margin: 0; }
.d-logo { width: 34px; height: 34px; border-radius: 50%; color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.d-hotel-img { width: 48px; height: 40px; border-radius: 8px; flex-shrink: 0; }
.d-flight-body { flex: 1; min-width: 0; }
.d-flight-name { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.d-flight-time { font-size: 13px; font-variant-numeric: tabular-nums; }
.price { color: var(--price); font-weight: 800; font-size: 17px; font-variant-numeric: tabular-nums; }
.qi { font-size: 11px; font-weight: 400; }
.ticket-empty { text-align: center; padding: 24px 0; font-size: 13px; }
.sample-note { font-size: 11px; color: var(--muted); text-align: right; margin-top: 8px; }
@media (max-width: 1280px) {
  .grid { grid-template-columns: 1fr; }
  .entries-row { grid-template-columns: repeat(4, 1fr); }
}

/* ===== 手机：原型① ===== */
.home { min-height: 100vh; background: var(--bg); }
.topbar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(10px + env(safe-area-inset-top, 0px)) max(16px, env(safe-area-inset-right)) 10px max(16px, env(safe-area-inset-left));
  background: var(--surface);
}
.brand { display: flex; align-items: center; gap: 8px; }
.logo {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-name { font-weight: 700; font-size: 16px; }
.topbar-link { padding: 4px; cursor: pointer; }

.page { max-width: 760px; margin: 0 auto; padding: 12px 16px 24px; box-sizing: border-box; }

.hero { position: relative; border-radius: 18px; overflow: hidden; height: 300px; }
.hero-img { position: absolute; inset: 0; width: 100%; height: 100%; }
.hero-text {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  padding: 22px 20px 40px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0));
}
.hero-title { font-size: 24px; font-weight: 800; color: #111827; text-wrap: balance; }
.hero-sub { font-size: 13px; color: #374151; margin-top: 6px; line-height: 1.6; }
.hero-sub.tight { margin-top: 0; }
.ask {
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border-radius: 14px;
  padding: 8px 8px 8px 14px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.15);
}
.ask-input { flex: 1; min-width: 0; font-size: 14px; color: #111827; height: 36px; }
.ask-send {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}

.entries {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  margin-top: 14px;
  background: var(--surface);
  border-radius: 16px;
  padding: 14px 6px;
}
.entry { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 8px 2px; cursor: pointer; }
.entry-icon { width: 46px; height: 46px; border-radius: 14px; display: flex; align-items: center; justify-content: center; }
.entry-label { font-size: 14px; font-weight: 600; margin-top: 2px; }
.entry-sub { font-size: 11px; color: var(--muted); }

.section-head { display: flex; align-items: baseline; justify-content: space-between; margin: 20px 2px 10px; }
.section-name { font-size: 16px; font-weight: 700; }
.section-more { font-size: 13px; color: var(--muted); cursor: pointer; }
.asks { display: flex; flex-wrap: wrap; gap: 8px; }
.ask-chip {
  font-size: 13px;
  padding: 7px 12px;
  border-radius: 999px;
  background: var(--surface);
  border: 1px solid var(--line);
  cursor: pointer;
}

.recent {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--surface);
  border-radius: 16px;
  padding: 10px;
  cursor: pointer;
}
.recent-img { width: 84px; height: 64px; border-radius: 10px; flex-shrink: 0; }
.recent-body { flex: 1; min-width: 0; }
.recent-title { font-weight: 600; }
.recent-meta { font-size: 12px; color: var(--muted); margin-top: 2px; }
.recent-tags { display: flex; gap: 6px; margin-top: 6px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.tag.blue { background: var(--accent-soft); color: var(--accent); }

@media (min-width: 700px) {
  .entries { grid-template-columns: repeat(6, 1fr); }
  .hero { height: 340px; }
}
</style>
