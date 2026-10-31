<template>
  <Shell active="plan" :show-recent="false">
  <view v-if="wide && days.length" class="editor">
    <view class="ed-head">
      <view class="back" @click="goBack"><Icon name="back" :size="22" color="var(--ink)" /></view>
      <text class="ed-title">{{ trip?.title }} 行程编辑</text>
      <text v-if="isDemo" class="plan-chip"><Icon name="crown" :size="13" color="var(--accent)" /> 舒适方案</text>
      <text class="tag" :class="trip?.status === 'DRAFT' ? '' : 'ok'">{{ trip?.status === "DRAFT" ? "草稿" : "已确认" }}</text>
      <text v-if="isDemo" class="tag">示例</text>
      <view class="ed-actions">
        <view class="ghost-act" @click="notYet('分享')"><Icon name="send" :size="15" color="var(--ink)" />分享</view>
        <view class="ghost-act" @click="notYet('下载 PDF')"><Icon name="layers" :size="15" color="var(--ink)" />下载PDF</view>
        <button class="btn ghost small" @click="changeNeeds">修改需求</button>
        <button v-if="trip?.status === 'DRAFT'" class="btn small" @click="confirmTrip">确认行程</button>
      </view>
    </view>
    <view class="ed-chips">
      <view class="ed-chip"><Icon name="calendar" :size="15" color="var(--accent)" />{{ cnDate(trip?.startDate) }} – {{ cnDate(trip?.endDate) }}（{{ days.length }}天{{ Math.max(days.length - 1, 0) }}晚）</view>
      <view v-if="isDemo" class="ed-chip"><Icon name="users" :size="15" color="var(--accent)" />{{ REQUIREMENT.people }}</view>
      <view class="ed-chip"><Icon name="wallet" :size="15" color="var(--accent)" />景点餐饮约 ¥{{ totalCost }}/人</view>
      <text v-for="t in (isDemo ? REQUIREMENT.tags : [])" :key="t" class="ed-tag">{{ t }}</text>
    </view>

    <view class="ed-cols">
      <!-- 左：当天时间线 -->
      <view class="ed-left">
        <view class="ed-days">
          <view v-for="(d, i) in days" :key="d.date" class="ed-day" :class="{ on: i === dayIndex }" @click="dayIndex = i">
            <text class="ed-day-no">D{{ i + 1 }}</text><text class="ed-day-date">{{ shortDate(d.date) }}</text>
          </view>
          <view class="add-day" @click="notYet('添加一天')"><Icon name="plus" :size="14" color="var(--accent)" />添加一天</view>
        </view>
        <view class="day-h">
          <text class="day-h-no">D{{ dayIndex + 1 }}</text>
          <text class="day-h-date">{{ cnDate(day?.date) }}</text>
          <text class="day-h-theme">{{ day?.theme }}</text>
        </view>
        <view class="day-h-sub">{{ day?.items.filter((x) => x.type !== "TRANSPORT").map((x) => x.name).join(" · ") }}</view>

        <view class="tl">
          <template v-for="(item, i) in day?.items" :key="item.id">
            <view class="tl-item">
              <view class="tl-time">{{ item.start }}</view>
              <view class="tl-dot" />
              <view class="tl-card">
                <view class="tl-main">
                  <image v-if="imageFor(item.name)" class="tl-img" :src="imageFor(item.name)" mode="aspectFill" />
                  <view v-else class="tl-img ph" :class="item.type"><Icon :name="TYPE_ICON[item.type] || 'pin'" :size="24" color="#fff" /></view>
                  <view class="tl-body">
                    <view class="tl-name">
                      <text :class="{ link: item.url }" @click="openUrl(item.url)">{{ item.name }}</text>
                      <text v-if="i === 0 && item.type === 'SPOT'" class="must">必去景点</text>
                    </view>
                    <view class="tl-note">{{ item.note }}</view>
                    <view class="tl-facts">
                      <text><Icon name="calendar" :size="12" color="var(--muted)" /> {{ duration(item) }}</text>
                      <text v-if="item.address"><Icon name="pin" :size="12" color="var(--muted)" /> {{ item.address }}</text>
                      <text v-if="item.cost" class="cost">¥{{ item.cost }}/人</text>
                    </view>
                  </view>
                  <view class="tl-more" @click="notYet('更多操作')"><Icon name="more" :size="16" color="var(--muted)" /></view>
                </view>
                <view v-if="item.type !== 'TRANSPORT'" class="tl-acts">
                  <view class="a keep" :class="{ on: kept[item.id] }" @click="toggleKeep(item.id)"><Icon name="heart" :size="13" :fill="kept[item.id]" color="var(--ok)" />{{ kept[item.id] ? "已保留" : "喜欢，保留" }}</view>
                  <view class="a swap" @click="openReplace(item)"><Icon name="refresh" :size="13" color="var(--accent)" />去过了，换一个</view>
                  <view class="a" @click="dislike(item.name)"><Icon name="close" :size="13" color="var(--muted)" />不感兴趣</view>
                </view>
              </view>
            </view>
            <view v-if="leg(item.id)" class="tl-leg"><Icon name="car" :size="13" color="var(--muted)" />{{ leg(item.id) }}</view>
          </template>
        </view>
        <view class="tl-add" @click="addStop"><Icon name="plus" :size="16" color="var(--accent)" />添加景点 / 活动</view>
      </view>

      <!-- 中：当天概览、美食、酒店 -->
      <view class="ed-mid">
        <view class="day-hero">
          <image class="day-hero-img" :src="dayHero" mode="aspectFill" />
          <view class="overview">
            <view class="ov-title">当天行程概览</view>
            <view class="ov-grid">
              <view class="ov"><Icon name="pin" :size="18" color="var(--accent)" /><view><view class="ov-k">景点</view><view class="ov-v">{{ dayStats.spots }} 个</view></view></view>
              <view class="ov"><Icon name="food" :size="18" color="var(--accent)" /><view><view class="ov-k">美食</view><view class="ov-v">{{ dayStats.foods }} 个</view></view></view>
              <view class="ov"><Icon name="calendar" :size="18" color="var(--accent)" /><view><view class="ov-k">总时长</view><view class="ov-v">{{ dayStats.hours }}</view></view></view>
              <view class="ov"><Icon name="car" :size="18" color="var(--accent)" /><view><view class="ov-k">交通</view><view class="ov-v">{{ isDemo && dayIndex === 0 ? DAY1_SUMMARY.transport : "待计算" }}</view></view></view>
            </view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="food" :size="16" color="var(--accent)" /> 行程中的美食推荐</text><text class="more" @click="go('/pages/search/food')">查看更多 ›</text></view>
          <view class="muted small">基于行程位置推荐，已按用餐时间排序 <text v-if="!isDemo">（示例）</text></view>
          <view v-for="f in FOODS.slice(0, 3)" :key="f.name" class="food">
            <image class="food-img" :src="f.image" mode="aspectFill" />
            <view class="food-body">
              <view class="food-name">{{ f.name }}</view>
              <view class="food-meta"><text class="star">★ {{ f.rating }}</text> <text class="muted">高德评分</text></view>
              <view class="food-tags"><text class="ftag">{{ f.tags[0] }}</text><text class="ftag">人均 ¥{{ f.avgPrice }}</text></view>
              <view class="muted small">距离景点 {{ f.distance }}</view>
            </view>
            <button class="btn ghost small add" :class="{ done: addedFood[f.name] }" @click="addFood(f.name)">{{ addedFood[f.name] ? "已加入" : "加入行程" }}</button>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="bed" :size="16" color="var(--accent)" /> 当日酒店</text></view>
          <view class="muted small"><Icon name="checkCircle" :size="12" color="var(--ok)" /> {{ cnDate(day?.date) }} 入住 · 钟楼附近 <text v-if="!isDemo">（示例）</text></view>
          <view class="food">
            <image class="food-img" :src="HOTELS[0].image" mode="aspectFill" />
            <view class="food-body">
              <view class="food-name">{{ HOTELS[0].name }}</view>
              <view class="food-meta"><text class="star">★ {{ HOTELS[0].rating }}</text> <text class="muted">高德评分</text></view>
              <view class="food-tags"><text v-for="t in HOTELS[0].tags" :key="t" class="ftag blue">{{ t }}</text></view>
            </view>
            <view class="hotel-side">
              <text class="price">¥{{ HOTELS[0].price }}<text class="qi">起</text></text>
              <button class="btn ghost small add" @click="go('/pages/search/flight?tab=hotel')">更换酒店</button>
            </view>
          </view>
        </view>
      </view>

      <!-- 右：地图、路线、附近推荐、建议 -->
      <view class="ed-right">
        <view class="panel">
          <view class="seg">
            <text class="seg-item" :class="{ on: rightTab === 'map' }" @click="rightTab = 'map'">地图路线</text>
            <text class="seg-item" :class="{ on: rightTab === 'time' }" @click="rightTab = 'time'">时间安排</text>
          </view>
          <template v-if="rightTab === 'map'">
            <image v-if="isDemo && dayIndex === 0" class="r-map" :src="ROUTE_MAP_WIDE" mode="aspectFill" @click="go('/pages/plan/map')" />
            <view v-else class="r-map empty-map"><Icon name="nav" :size="28" color="var(--muted)" /><text class="muted small">这一天的路线地图在阶段二接入</text></view>
            <view class="route-info">
              <view class="ri-title">路线信息</view>
              <view class="ri-grid">
                <view class="ri"><Icon name="car" :size="18" color="var(--accent)" /><view><view class="ov-k">总距离</view><view class="ov-v">{{ isDemo && dayIndex === 0 ? DAY1_SUMMARY.distance : "待计算" }}</view></view></view>
                <view class="ri"><Icon name="calendar" :size="18" color="var(--accent)" /><view><view class="ov-k">车程</view><view class="ov-v">{{ isDemo && dayIndex === 0 ? DAY1_SUMMARY.drive : "待计算" }}</view></view></view>
                <view class="ri"><Icon name="wallet" :size="18" color="var(--accent)" /><view><view class="ov-k">交通费用</view><view class="ov-v">{{ isDemo && dayIndex === 0 ? DAY1_SUMMARY.transport : "待计算" }}</view></view></view>
              </view>
            </view>
          </template>
          <view v-else class="times">
            <view v-for="item in day?.items" :key="item.id" class="time-row"><text class="time-k">{{ item.start }}–{{ item.end }}</text><text>{{ item.name }}</text></view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="sparkle" :size="16" color="var(--accent)" /> 附近其他推荐景点</text></view>
          <view class="near-grid">
            <view v-for="r in REPLACEMENTS.slice(0, 3)" :key="r.name" class="near">
              <image class="near-img" :src="r.image" mode="aspectFill" />
              <view class="near-name">{{ r.name }}</view>
              <view class="near-meta"><text class="star">★ {{ r.rating }}</text> · {{ r.distance }}</view>
              <view class="near-btn" @click="openReplace(day!.items[0], r.name)">替换为此景点</view>
            </view>
          </view>
        </view>

        <view class="panel">
          <view class="panel-head"><text class="panel-title"><Icon name="chat" :size="16" color="var(--accent)" /> AI 调整建议</text></view>
          <view v-for="sg in (isDemo ? SUGGESTIONS : ['想调整哪里直接说，例如：第二天轻松一点'])" :key="sg" class="sg"><Icon name="checkCircle" :size="14" color="var(--ok)" /><text>{{ sg }}</text></view>
          <button class="btn ghost regen" @click="regenerate">重新生成当天行程</button>
        </view>
      </view>
    </view>

    <ReplaceModal v-if="replacing" :item="replacing" :day-no="dayIndex + 1" :initial="replaceTo" @close="replacing = null" @confirm="confirmReplace" />
  </view>

  <view v-else class="page">
    <view v-if="trip" class="head">
      <view class="title-row">
        <text class="title">{{ trip.title }}</text>
        <text class="tag" :class="trip.status === 'DRAFT' ? '' : 'ok'">{{ trip.status === "DRAFT" ? "草稿" : "已确认" }}</text>
        <text v-if="isDemo" class="tag">示例</text>
      </view>
      <view class="meta">{{ trip.destination }} · {{ cnDate(trip.startDate) }} – {{ cnDate(trip.endDate) }}</view>
    </view>

    <view class="tabs">
      <text v-for="t in TABS" :key="t" class="tab" :class="{ active: tab === t }" @click="switchTab(t)">{{ t }}</text>
    </view>

    <template v-if="days.length">
      <scroll-view scroll-x class="days">
        <view class="days-inner">
          <view v-for="(d, i) in days" :key="d.date" class="day-tab" :class="{ active: i === dayIndex }" @click="dayIndex = i">
            <text class="day-no">D{{ i + 1 }}</text>
            <text class="day-date">{{ shortDate(d.date) }}</text>
          </view>
        </view>
      </scroll-view>

      <view v-if="tab === '行程总览' && day" class="timeline">
        <view v-if="day.theme" class="day-theme">{{ day.theme }}</view>
        <view v-for="(item, i) in day.items" :key="item.id" class="stop">
          <view class="rail">
            <view class="marker" :class="item.type">{{ i + 1 }}</view>
          </view>
          <view class="stop-card">
            <view class="stop-main">
              <image v-if="imageFor(item.name)" class="thumb" :src="imageFor(item.name)" mode="aspectFill" />
              <view v-else class="thumb placeholder" :class="item.type"><Icon :name="TYPE_ICON[item.type] || 'pin'" :size="22" color="#fff" /></view>
              <view class="stop-body">
                <view class="stop-name" :class="{ link: item.url }" @click="openUrl(item.url)">{{ item.name }}</view>
                <view class="stop-time">{{ item.start }}<text v-if="item.end"> - {{ item.end }}</text><text v-if="item.cost" class="cost"> · ¥{{ item.cost }}/人</text></view>
                <view v-if="item.note" class="stop-note">{{ item.note }}</view>
              </view>
            </view>
            <view v-if="item.type !== 'TRANSPORT'" class="stop-actions">
              <view class="act keep" :class="{ on: kept[item.id] }" @click="toggleKeep(item.id)">
                <Icon name="heart" :size="13" :fill="kept[item.id]" color="var(--ok)" />
                <text>{{ kept[item.id] ? "已保留" : "喜欢，保留" }}</text>
              </view>
              <view class="act" @click="replace(item.name)">
                <Icon name="refresh" :size="13" color="var(--ink)" />
                <text>换一个</text>
              </view>
              <view class="act ghost" @click="dislike(item.name)">
                <Icon name="close" :size="13" color="var(--muted)" />
                <text>不感兴趣</text>
              </view>
              <view class="grip" @click="edit"><Icon name="grip" :size="16" color="var(--muted)" /></view>
            </view>
          </view>
        </view>
        <view class="add" @click="addStop"><Icon name="plus" :size="16" color="var(--accent)" /><text>添加景点/活动</text></view>
      </view>

      <view v-if="tab === '费用明细'" class="costs">
        <view v-for="(d, i) in days" :key="d.date" class="cost-day">
          <view class="cost-day-head"><text>D{{ i + 1 }} · {{ shortDate(d.date) }}</text><text class="price">¥{{ dayCost(d) }}</text></view>
          <view v-for="item in d.items.filter((x) => x.cost)" :key="item.id" class="cost-row">
            <text>{{ item.name }}</text><text class="muted">¥{{ item.cost }}</text>
          </view>
        </view>
        <view class="cost-total"><text>景点和餐饮合计（每人）</text><text class="price big">¥{{ totalCost }}</text></view>
        <view class="muted small">不含机票和住宿；机票、酒店价格见比价页。</view>
      </view>

      <view v-if="tips && tab === '行程总览'" class="tips">
        <view class="tips-title">注意事项</view>
        <MarkdownView :text="tips" />
      </view>
    </template>
    <view v-else-if="detail" class="card body">
      <MarkdownView :text="detail.version.content || ''" />
    </view>

    <view v-if="versions.length > 1" class="versions">
      <text class="muted small">历史版本：</text>
      <text v-for="v in versions" :key="v.version" class="version" :class="{ active: detail && v.version === detail.version.version }" @click="load(v.version)">
        第 {{ v.version }} 版
      </text>
    </view>

    <view v-if="events.length" class="card reminders">
      <view class="tips-title">关联的提醒</view>
      <view v-for="e in events" :key="e.id" class="reminder">
        <text class="reminder-time">{{ eventTime(e.startAt) }}</text>
        <text>{{ e.title }}</text>
      </view>
    </view>

    <view v-if="trip" class="bottom">
      <button v-if="trip.status === 'DRAFT'" class="btn" @click="confirmTrip">确认这个行程</button>
      <button v-if="!isDemo" class="btn danger" @click="removeTrip">删除</button>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { ItineraryDay, ItineraryItem, ScheduleEvent, TripDetail, TripVersion } from "../../api/types";
import Icon from "../../components/Icon.vue";
import MarkdownView from "../../components/MarkdownView.vue";
import ReplaceModal from "../../components/ReplaceModal.vue";
import Shell from "../../components/Shell.vue";
import {
  DAY1_LEGS, DAY1_SUMMARY, DAY_HERO, DEMO_TRIP, FOODS, HOTELS, imageFor, REPLACEMENTS, REQUIREMENT, ROUTE_MAP_WIDE, SUGGESTIONS,
} from "../../demo/xian";
import { useWide } from "../../utils/screen";
import { hm, openUrl, shortDate } from "../../utils/format";

/** 行程详情（原型④）：按天切换的时间线，每项可以保留、换一个、不感兴趣；id=demo 时显示示例行程。 */
const TABS = ["行程总览", "地图路线", "费用明细"];
const TYPE_ICON: Record<string, string> = { SPOT: "pin", FOOD: "food", HOTEL: "bed", TRANSPORT: "car", FREE: "sparkle" };

const isDemo = ref(false);
const tripId = ref(0);
const detail = ref<TripDetail | null>(null);
const versions = ref<TripVersion[]>([]);
const events = ref<ScheduleEvent[]>([]);
const tab = ref("行程总览");
const dayIndex = ref(0);
const kept = reactive<Record<string, boolean>>({});
const wide = useWide();
const rightTab = ref("map");
const addedFood = reactive<Record<string, boolean>>({});
const replacing = ref<ItineraryItem | null>(null);
const replaceTo = ref("");
/** 示例行程的可编辑副本，替换景点后直接改它 */
const demoDays = ref<ItineraryDay[]>(JSON.parse(JSON.stringify(DEMO_TRIP.days)));

/** 页面头部要的信息，示例和真实行程统一成一个形状 */
const trip = computed(() =>
  isDemo.value
    ? DEMO_TRIP
    : detail.value && {
        title: detail.value.trip.title,
        destination: detail.value.trip.destination,
        startDate: detail.value.trip.startDate,
        endDate: detail.value.trip.endDate,
        status: detail.value.trip.status,
      },
);
const days = computed<ItineraryDay[]>(() => (isDemo.value ? demoDays.value : detail.value?.version.itinerary?.days || []));
/** 当天概览：景点数、吃饭数、从第一项开始到最后一项结束的时长 */
const dayStats = computed(() => {
  const items = day.value?.items || [];
  const first = items.find((x) => x.start)?.start;
  const last = [...items].reverse().find((x) => x.end)?.end;
  const minutes = first && last ? toMin(last) - toMin(first) : 0;
  return {
    spots: items.filter((x) => x.type === "SPOT").length,
    foods: items.filter((x) => x.type === "FOOD").length + (isDemo.value && dayIndex.value === 0 ? 2 : 0),
    hours: minutes > 0 ? `约 ${Math.round(minutes / 60)} 小时` : "待定",
  };
});
const dayHero = computed(() => imageFor(day.value?.items.find((x) => x.type === "SPOT")?.name) || DAY_HERO);
const tips = computed(() => (isDemo.value ? DEMO_TRIP.tips : detail.value?.version.itinerary?.tips));
const day = computed(() => days.value[dayIndex.value]);
const totalCost = computed(() => days.value.reduce((s, d) => s + dayCost(d), 0));

onLoad(async (query) => {
  if (query?.id === "demo") {
    isDemo.value = true;
    uni.setNavigationBarTitle({ title: DEMO_TRIP.title });
    return;
  }
  tripId.value = Number(query?.id);
  await Promise.all([load(), loadVersions(), loadEvents()]);
});

function toMin(t: string) {
  const [h, m] = t.split(":").map(Number);
  return h * 60 + m;
}

function duration(it: ItineraryItem) {
  if (!it.start || !it.end) return "时长待定";
  const hours = (toMin(it.end) - toMin(it.start)) / 60;
  return `约 ${Number.isInteger(hours) ? hours : hours.toFixed(1)} 小时`;
}

/** 示例第 1 天各站之间的交通；真实行程的交通在阶段二由高德计算 */
function leg(id: string) {
  return isDemo.value ? DAY1_LEGS[id] : "";
}

function go(url: string) {
  uni.navigateTo({ url });
}

function goBack() {
  const pages = getCurrentPages();
  if (pages.length > 1) uni.navigateBack();
  else uni.reLaunch({ url: "/pages/trips/trips" });
}

function notYet(what: string) {
  uni.showToast({ title: `${what}还没接入`, icon: "none" });
}

function changeNeeds() {
  go("/pages/chat/chat?q=" + encodeURIComponent(`我想修改行程「${trip.value?.title}」的需求`));
}

function regenerate() {
  if (isDemo.value) {
    uni.showToast({ title: "示例行程：真实行程会让助手重排这一天", icon: "none" });
    return;
  }
  go("/pages/chat/chat?q=" + encodeURIComponent(`帮我重新安排行程「${trip.value?.title}」（tripId=${tripId.value}）的第${dayIndex.value + 1}天`));
}

function addFood(name: string) {
  addedFood[name] = !addedFood[name];
  if (addedFood[name]) uni.showToast({ title: `已把${name}加到第${dayIndex.value + 1}天`, icon: "none" });
}

function openReplace(item: ItineraryItem, to = "") {
  replaceTo.value = to;
  replacing.value = item;
}

/** 确认替换：示例行程直接改；真实行程交给助手改并保存新版本。 */
function confirmReplace(name: string) {
  const item = replacing.value!;
  replacing.value = null;
  if (isDemo.value) {
    const target = demoDays.value[dayIndex.value].items.find((x) => x.id === item.id)!;
    const row = REPLACEMENTS.find((r) => r.name === name);
    target.name = name;
    target.note = row?.desc || "";
    target.address = undefined;
    uni.showToast({ title: `已换成${name}，后续路线已重新规划`, icon: "none" });
    return;
  }
  go("/pages/chat/chat?q=" + encodeURIComponent(`把行程「${trip.value?.title}」（tripId=${tripId.value}）第${dayIndex.value + 1}天的「${item.name}」换成「${name}」，其他不变`));
}

function dayCost(d: ItineraryDay) {
  return d.items.reduce((s, x) => s + (x.cost || 0), 0);
}

/** "2026-03-10" 显示成"3月10日"。 */
function cnDate(date?: string | null) {
  return date ? `${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日` : "";
}

function switchTab(t: string) {
  if (t === "地图路线") {
    uni.navigateTo({ url: `/pages/plan/map?day=${dayIndex.value + 1}` });
    return;
  }
  tab.value = t;
}

function toggleKeep(id: string) {
  kept[id] = !kept[id];
}

function replace(name: string) {
  if (wide.value) {
    const item = day.value?.items.find((x) => x.name === name);
    if (item) openReplace(item);
    return;
  }
  uni.navigateTo({ url: `/pages/plan/replace?name=${encodeURIComponent(name)}` });
}

function dislike(name: string) {
  uni.showToast({ title: `已记下：不想去${name}`, icon: "none" });
}

function edit() {
  uni.navigateTo({ url: `/pages/plan/edit?day=${dayIndex.value + 1}` });
}

function addStop() {
  uni.navigateTo({ url: `/pages/chat/chat?q=${encodeURIComponent(`第${dayIndex.value + 1}天再加一个景点`)}` });
}

async function loadEvents() {
  try {
    events.value = await api.tripEvents(tripId.value);
  } catch (e) {
    showError(e);
  }
}

function eventTime(iso: string) {
  const d = new Date(iso);
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hm(d)}`;
}

async function confirmTrip() {
  if (isDemo.value) {
    uni.showToast({ title: "这是示例行程", icon: "none" });
    return;
  }
  try {
    detail.value!.trip = await api.confirmTrip(tripId.value);
    uni.showToast({ title: "已确认", icon: "none" });
  } catch (e) {
    showError(e);
  }
}

function removeTrip() {
  uni.showModal({
    title: "删除行程",
    content: events.value.length ? `行程和 ${events.value.length} 条关联提醒都会删掉。` : "删除这个行程？",
    confirmText: "删除",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await api.deleteTrip(tripId.value);
        uni.navigateBack();
      } catch (e) {
        showError(e);
      }
    },
  });
}

async function load(version?: number) {
  try {
    detail.value = await api.trip(tripId.value, version);
    dayIndex.value = 0;
    uni.setNavigationBarTitle({ title: detail.value.trip.title });
  } catch (e) {
    showError(e);
  }
}

async function loadVersions() {
  try {
    versions.value = await api.tripVersions(tripId.value);
  } catch (e) {
    showError(e);
  }
}
</script>

<style scoped>
/* ===== 电脑：行程编辑 ===== */
.editor { padding: 18px 24px 28px; }
.ed-head { display: flex; align-items: center; gap: 10px; }
.back { cursor: pointer; padding: 4px; }
.ed-title { font-size: 24px; font-weight: 800; }
.plan-chip { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--accent); background: var(--accent-soft); padding: 3px 10px; border-radius: 8px; }
.ed-actions { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.ed-actions .btn { margin: 0; }
.ghost-act { display: flex; align-items: center; gap: 4px; font-size: 14px; cursor: pointer; }
.ed-chips { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0 16px; }
.ed-chip { display: flex; align-items: center; gap: 6px; font-size: 14px; padding: 8px 14px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); }
.ed-tag { font-size: 13px; padding: 8px 12px; border-radius: 10px; background: var(--surface-2); }
.ed-cols { display: grid; grid-template-columns: minmax(420px, 1.15fr) minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
.ed-left, .ed-mid, .ed-right { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.ed-left { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 14px; }
.ed-days { display: flex; gap: 8px; align-items: center; }
.ed-day { flex: 1; display: flex; align-items: baseline; justify-content: center; gap: 4px; padding: 9px 0; border-radius: 10px; background: var(--surface-2); cursor: pointer; }
.ed-day.on { background: var(--accent); color: #fff; }
.ed-day-no { font-weight: 800; }
.ed-day-date { font-size: 12px; opacity: 0.8; }
.add-day { display: flex; align-items: center; gap: 4px; font-size: 13px; color: var(--accent); border: 1px solid var(--accent); border-radius: 10px; padding: 8px 10px; cursor: pointer; white-space: nowrap; }
.day-h { display: flex; align-items: baseline; gap: 10px; margin-top: 4px; }
.day-h-no, .day-h-date { font-size: 20px; font-weight: 800; }
.day-h-theme { font-size: 18px; font-weight: 700; }
.day-h-sub { font-size: 13px; color: var(--muted); margin-top: -8px; }
.tl { display: flex; flex-direction: column; }
.tl-item { display: grid; grid-template-columns: 50px 16px minmax(0, 1fr); gap: 0 8px; position: relative; }
.tl-time { font-weight: 700; font-variant-numeric: tabular-nums; padding-top: 10px; }
.tl-dot { width: 12px; height: 12px; border-radius: 50%; border: 3px solid var(--accent); background: var(--surface); margin-top: 13px; box-sizing: border-box; z-index: 1; }
.tl-item::before { content: ""; position: absolute; left: 63px; top: 24px; bottom: -40px; width: 2px; background: var(--accent-soft); }
.tl-card { border: 1px solid var(--line); border-radius: 14px; padding: 10px; margin-bottom: 4px; }
.tl-main { display: flex; gap: 10px; }
.tl-img { width: 96px; height: 76px; border-radius: 10px; flex-shrink: 0; }
.tl-img.ph { display: flex; align-items: center; justify-content: center; background: var(--accent); }
.tl-img.ph.FOOD { background: var(--price); }
.tl-img.ph.TRANSPORT, .tl-img.ph.FREE { background: #94a3b8; }
.tl-body { flex: 1; min-width: 0; }
.tl-name { font-weight: 800; font-size: 16px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.must { font-size: 11px; font-weight: 600; color: #e11d48; background: #fde8ec; padding: 1px 6px; border-radius: 4px; }
.tl-note { font-size: 13px; color: var(--muted); margin-top: 2px; }
.tl-facts { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: var(--muted); margin-top: 4px; }
.tl-facts text { display: inline-flex; align-items: center; gap: 3px; }
.tl-more { cursor: pointer; padding: 2px; }
.tl-acts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.a { display: flex; align-items: center; gap: 3px; font-size: 12px; padding: 5px 8px; border-radius: 8px; border: 1px solid var(--line); cursor: pointer; white-space: nowrap; }
.a.keep { color: var(--ok); background: var(--ok-soft); border-color: transparent; }
.a.keep.on { border-color: var(--ok); }
.a.swap { color: var(--accent); border-color: var(--accent); }
.tl-leg { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--muted); padding: 10px 0 10px 74px; }
.tl-add { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 12px; border: 1.5px dashed var(--line); border-radius: 12px; color: var(--accent); cursor: pointer; }
.day-hero { position: relative; border-radius: 16px; overflow: hidden; height: 300px; }
.day-hero-img { width: 100%; height: 100%; }
.overview { position: absolute; left: 12px; right: 12px; bottom: 12px; background: var(--surface); border-radius: 14px; padding: 12px 14px; box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15); }
.ov-title { font-weight: 800; margin-bottom: 8px; }
.ov-grid, .ri-grid { display: grid; grid-template-columns: repeat(4, auto); justify-content: space-between; gap: 8px; }
.ri-grid { grid-template-columns: repeat(3, 1fr); }
.ov, .ri { display: flex; align-items: center; gap: 6px; }
.ov-k { font-size: 11px; color: var(--muted); }
.ov-v { font-size: 13px; font-weight: 700; white-space: nowrap; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 14px; }
.panel-head { display: flex; justify-content: space-between; align-items: baseline; }
.panel-title { font-weight: 800; display: flex; align-items: center; gap: 6px; }
.more { font-size: 13px; color: var(--accent); cursor: pointer; }
.food { display: flex; gap: 10px; align-items: center; padding: 10px 0; border-bottom: 1px solid var(--line); }
.food:last-child { border-bottom: none; }
.food-img { width: 120px; height: 84px; border-radius: 10px; flex-shrink: 0; }
.food-body { flex: 1; min-width: 0; }
.food-name { font-weight: 800; }
.food-meta { font-size: 12px; }
.star { color: var(--price); font-weight: 700; }
.food-tags { display: flex; flex-wrap: wrap; gap: 4px; margin: 4px 0; }
.ftag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: #fff1e6; color: #c2560c; }
.ftag.blue { background: var(--accent-soft); color: var(--accent); }
.add { margin: 0; color: var(--accent); flex-shrink: 0; }
.add.done { color: var(--ok); border-color: var(--ok); }
.hotel-side { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.price { color: var(--price); font-weight: 800; font-size: 18px; }
.qi { font-size: 11px; font-weight: 400; }
.seg { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; margin-bottom: 10px; }
.seg-item { flex: 1; text-align: center; font-size: 13px; padding: 7px 0; border-radius: 8px; cursor: pointer; }
.seg-item.on { background: var(--accent); color: #fff; font-weight: 600; }
.r-map { width: 100%; height: 270px; border-radius: 12px; display: block; cursor: pointer; }
.empty-map { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; background: var(--surface-2); cursor: default; }
.route-info { margin-top: 12px; }
.ri-title { font-weight: 800; margin-bottom: 8px; }
.times { display: flex; flex-direction: column; gap: 6px; }
.time-row { display: flex; gap: 12px; font-size: 14px; }
.time-k { color: var(--accent); font-variant-numeric: tabular-nums; width: 96px; flex-shrink: 0; }
.near-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 10px; }
.near-img { width: 100%; height: 70px; border-radius: 8px; }
.near-name { font-weight: 700; font-size: 13px; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.near-meta { font-size: 11px; color: var(--muted); }
.near-btn { font-size: 12px; text-align: center; color: var(--accent); background: var(--accent-soft); border-radius: 6px; padding: 4px 0; margin-top: 4px; cursor: pointer; }
.sg { display: flex; gap: 6px; align-items: flex-start; font-size: 13px; margin-top: 8px; }
.regen { margin: 12px 0 0; width: 100%; color: var(--accent); border-color: var(--accent); }
@media (max-width: 1400px) {
  .ed-cols { grid-template-columns: minmax(400px, 1fr) minmax(0, 1fr); }
  .ed-right { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, 1fr); }
}

/* ===== 手机 ===== */
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { margin-bottom: 10px; }
.title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.title { font-size: 19px; font-weight: 700; }
.meta { font-size: 13px; color: var(--muted); margin-top: 2px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.tag.ok { background: var(--ok-soft); color: var(--ok); }

.tabs { display: flex; gap: 22px; border-bottom: 1px solid var(--line); margin-bottom: 12px; }
.tab { padding: 8px 0; font-size: 14px; color: var(--muted); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab.active { color: var(--accent); font-weight: 600; border-color: var(--accent); }

.days { white-space: nowrap; margin-bottom: 12px; }
.days-inner { display: inline-flex; gap: 8px; }
.day-tab {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  width: 64px;
  padding: 8px 0;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  cursor: pointer;
}
.day-tab.active { background: var(--accent); border-color: var(--accent); color: #fff; }
.day-no { font-weight: 700; font-size: 16px; }
.day-date { font-size: 11px; opacity: 0.8; }

.timeline { display: flex; flex-direction: column; }
.day-theme { font-weight: 700; margin: 0 0 10px 2px; }
.stop { display: flex; gap: 10px; }
.rail { width: 24px; display: flex; flex-direction: column; align-items: center; position: relative; flex-shrink: 0; }
.rail::after { content: ""; position: absolute; top: 26px; bottom: -6px; border-left: 2px dashed var(--line); }
.stop:last-of-type .rail::after { display: none; }
.marker {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 14px;
  z-index: 1;
}
.marker.FOOD { background: var(--price); }
.marker.TRANSPORT, .marker.FREE { background: var(--muted); }
.stop-card { flex: 1; min-width: 0; background: var(--surface); border-radius: 14px; padding: 10px; margin-bottom: 10px; border: 1px solid var(--line); }
.stop-main { display: flex; gap: 10px; }
.thumb { width: 76px; height: 64px; border-radius: 10px; flex-shrink: 0; }
.placeholder { display: flex; align-items: center; justify-content: center; background: var(--accent); }
.placeholder.FOOD { background: var(--price); }
.placeholder.TRANSPORT, .placeholder.FREE { background: #94a3b8; }
.stop-body { flex: 1; min-width: 0; }
.stop-name { font-weight: 700; }
.stop-time { font-size: 12px; color: var(--muted); margin-top: 1px; font-variant-numeric: tabular-nums; }
.cost { color: var(--price); }
.stop-note { font-size: 13px; margin-top: 2px; color: var(--ink); opacity: 0.85; }
.stop-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--line);
  cursor: pointer;
}
.act.keep { color: var(--ok); border-color: var(--ok); }
.act.keep.on { background: var(--ok-soft); }
.act.ghost { color: var(--muted); }
.grip { margin-left: auto; padding: 2px; cursor: pointer; }
.add {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-left: 34px;
  padding: 12px;
  border: 1.5px dashed var(--accent);
  border-radius: 14px;
  color: var(--accent);
  font-size: 14px;
  cursor: pointer;
}

.costs { display: flex; flex-direction: column; gap: 10px; }
.cost-day { background: var(--surface); border-radius: 14px; padding: 12px 14px; border: 1px solid var(--line); }
.cost-day-head { display: flex; justify-content: space-between; font-weight: 600; }
.cost-row { display: flex; justify-content: space-between; font-size: 13px; margin-top: 4px; }
.cost-total { display: flex; justify-content: space-between; align-items: baseline; padding: 4px 4px 0; font-weight: 600; }
.big { font-size: 20px; }
.small { font-size: 12px; }

.tips { margin-top: 14px; background: var(--surface); border-radius: 14px; padding: 12px 14px; border: 1px solid var(--line); }
.tips-title { font-size: 13px; color: var(--muted); margin-bottom: 4px; }
.body { padding: 16px 18px; }
.versions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 14px; }
.version { font-size: 12px; padding: 3px 10px; border-radius: 999px; border: 1px solid var(--line); cursor: pointer; }
.version.active { border-color: var(--accent); color: var(--accent); }
.reminders { margin-top: 12px; }
.reminder { display: flex; gap: 12px; padding: 4px 0; font-size: 14px; }
.reminder-time { color: var(--accent); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.bottom { display: flex; gap: 10px; margin-top: 18px; }
.bottom .btn { margin: 0; flex: 1; }
.bottom .btn.danger { flex: 0 0 auto; }
</style>
