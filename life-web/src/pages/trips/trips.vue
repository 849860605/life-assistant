<template>
  <Shell active="trips" :show-recent="false">
  <view class="page">
    <view class="head">
      <view class="head-icon"><Icon name="calendar" :size="22" color="var(--accent)" /></view>
      <view class="head-text">
        <text class="title">我的行程</text>
        <text class="muted sub">管理旅行计划，查看每天的安排、预订状态和出行提醒</text>
      </view>
      <button v-if="trips.length" class="btn new-btn" @click="toChat"><Icon name="plus" :size="16" color="#fff" /><text>新建行程</text></button>
    </view>

    <view v-if="!loaded" class="state muted">加载中…</view>

    <!-- 第一次进来：空状态，不放示例数据 -->
    <view v-else-if="!trips.length" class="empty-card">
      <Icon name="trip" :size="40" color="var(--muted)" />
      <view class="empty-title">还没有旅行计划</view>
      <view class="muted">告诉 AI 你想去哪里、什么时候出发、预算多少，创建你的第一份专属行程。</view>
      <button class="btn empty-btn" @click="toChat"><Icon name="plus" :size="16" color="#fff" /><text>创建我的第一份行程</text></button>
    </view>

    <view v-else class="cols">
      <!-- 左：行程列表 -->
      <view class="col list-col">
        <view class="filters">
          <text v-for="f in FILTERS" :key="f" class="filter" :class="{ on: filter === f }" @click="filter = f">{{ f }}<text v-if="countOf(f)" class="cnt">{{ countOf(f) }}</text></text>
        </view>
        <view
          v-for="r in shown"
          :key="r.id"
          class="card"
          :class="{ on: wide && r.id === selectedId }"
          @click="pick(r.id)"
        >
          <image v-if="covers[r.city]" class="cover" :src="covers[r.city]" mode="aspectFill" />
          <view v-else class="cover blank"><Icon name="trip" :size="26" color="#fff" /></view>
          <view class="card-body">
            <view class="card-name">{{ r.title }}</view>
            <view class="meta"><Icon name="calendar" :size="12" color="var(--muted)" />{{ r.dates }}</view>
            <view v-if="r.city" class="meta"><Icon name="pin" :size="12" color="var(--muted)" />{{ r.destination }}</view>
            <text class="state-tag" :class="r.tone">{{ r.label }}</text>
          </view>
          <view class="more" @click.stop="menuFor = menuFor === r.id ? 0 : r.id"><Icon name="more" :size="18" color="var(--muted)" /></view>
          <view v-if="menuFor === r.id" class="menu" @click.stop>
            <text class="menu-item" @click="openDetail(r.id)">查看详情</text>
            <text class="menu-item" @click="edit(r.id)">继续编辑</text>
            <text class="menu-item" @click="copy(r.id)">{{ copying === r.id ? "复制中…" : "复制行程" }}</text>
            <text class="menu-item" @click="share">分享</text>
            <text class="menu-item danger" @click="askDelete(r.id)">删除</text>
          </view>
          <view v-if="deleting === r.id" class="confirm" @click.stop>
            <text>删除后找不回来，确定删除「{{ r.title }}」？</text>
            <view class="confirm-acts">
              <button class="btn small danger-btn" @click="doDelete(r.id)">删除</button>
              <button class="btn ghost small" @click="deleting = 0">取消</button>
            </view>
          </view>
        </view>
        <view v-if="!shown.length" class="state muted">这个分类下还没有行程</view>
      </view>

      <!-- 中：行程详情（电脑上） -->
      <view v-if="wide" class="col main-col">
        <view v-if="detailLoading && !detail" class="state muted">加载中…</view>
        <template v-else-if="detail && selected">
          <view class="banner" :style="covers[selected.city] ? { backgroundImage: `linear-gradient(90deg, rgba(15,23,42,.72), rgba(15,23,42,.15)), url(${covers[selected.city]})` } : {}">
            <view class="banner-top">
              <text class="banner-title">{{ detail.trip.title }}</text>
              <view class="banner-acts">
                <button class="btn small light" @click="edit(selected.id)">编辑行程</button>
                <button class="btn small light" @click="toggleVersions">版本历史</button>
                <button class="btn small light" @click="openDetail(selected.id)">完整页面</button>
              </view>
            </view>
            <view class="banner-meta">
              <text><Icon name="calendar" :size="13" color="#fff" /> {{ selected.dates }}<text v-if="days.length">（{{ days.length }} 天 {{ Math.max(days.length - 1, 0) }} 晚）</text></text>
              <text v-if="detail.trip.destination"><Icon name="pin" :size="13" color="#fff" /> {{ detail.trip.destination }}</text>
              <text><Icon name="wallet" :size="13" color="#fff" /> 预算 未设置</text>
              <text class="state-tag light" :class="selected.tone">{{ selected.label }}</text>
            </view>
          </view>

          <!-- 版本历史 -->
          <view v-if="versionsOpen" class="panel">
            <view class="ph"><text class="panel-title">版本历史</text><text class="muted small">恢复会把那一版存成新版本，历史不会删</text></view>
            <view v-if="!versions.length" class="muted small">加载中…</view>
            <view v-for="v in versions" :key="v.version" class="ver">
              <text class="ver-no" :class="{ cur: v.version === detail.version.version }">第 {{ v.version }} 版</text>
              <view class="ver-body">
                <view class="small">{{ v.changeNote || (v.version === 1 ? "生成行程" : "修改") }}</view>
                <view class="muted small">{{ queriedAt(v.createdAt) }} · {{ v.createdBy === "AI" ? "助手" : "我" }}</view>
              </view>
              <template v-if="v.version !== detail.version.version">
                <text class="link small" @click="compare(v.version)">和当前对比</text>
                <text v-if="restoring !== v.version" class="link small" @click="restoring = v.version">恢复</text>
                <view v-else class="ver-confirm">
                  <button class="btn small" @click="restore(v.version)">确认恢复</button>
                  <button class="btn ghost small" @click="restoring = 0">取消</button>
                </view>
              </template>
              <text v-else class="tag-cur">当前</text>
            </view>
          </view>

          <view class="tabs">
            <view v-for="t in TABS" :key="t.key" class="tab" :class="{ on: tab === t.key }" @click="tab = t.key"><Icon :name="t.icon" :size="15" :color="tab === t.key ? '#fff' : 'var(--ink)'" />{{ t.label }}</view>
          </view>

          <view v-if="!days.length" class="panel muted small">这个行程是早期保存的，只有正文，没有逐日安排；可以在对话里让助手重新整理。</view>

          <!-- 行程安排 -->
          <view v-else-if="tab === 'plan'" class="panel">
            <view class="days">
              <view v-for="(d, i) in days" :key="d.date" class="day" :class="{ on: i === dayIndex }" @click="dayIndex = i">
                <text class="strong">第{{ i + 1 }}天</text><text class="small">{{ cnDay(d.date) }}</text>
              </view>
            </view>
            <view class="day-title">第{{ dayIndex + 1 }}天<text v-if="day?.theme"> · {{ day.theme }}</text><text v-if="dayProgress.total" class="progress">已完成 {{ dayProgress.done }}/{{ dayProgress.total }}</text></view>
            <view v-for="it in day?.items || []" :key="it.id" class="tl" :class="{ done: isDone(it.id) }">
              <text class="tl-time">{{ it.start || "" }}</text>
              <view class="tl-icon"><Icon :name="isDone(it.id) ? 'check' : TYPE_ICON[it.type] || 'pin'" :size="15" :color="isDone(it.id) ? 'var(--ok)' : 'var(--accent)'" /></view>
              <view class="tl-body">
                <view class="tl-name">{{ it.name }}</view>
                <view v-if="it.note || it.address" class="muted small">{{ it.note || it.address }}</view>
                <text v-if="doneable(it.type)" class="done-btn" :class="{ on: isDone(it.id) }" @click="toggleDone(it)">{{ isDone(it.id) ? "✓ " : "" }}{{ doneText(it.type, isDone(it.id)) }}</text>
              </view>
              <BookingTag v-if="bookable(it)" :state="stateOf(it.id)" :url="it.url || null" @set="(s: BookingState) => setBooking(it.id, s)" @open="openBooking(it.id, it.url!)" />
            </view>
          </view>

          <!-- 交通信息 -->
          <view v-else-if="tab === 'transport'" class="panel">
            <view v-if="!transports.length" class="muted small">行程里没有交通安排。</view>
            <view v-for="t in transports" :key="t.item.id" class="tl">
              <text class="tl-time">D{{ t.day + 1 }} {{ t.item.start || "" }}</text>
              <view class="tl-icon"><Icon :name="/[A-Z0-9]{2}\d{3,4}/.test(t.item.name) ? 'plane' : 'car'" :size="15" color="var(--accent)" /></view>
              <view class="tl-body">
                <view class="tl-name">{{ t.item.name }}</view>
                <view class="muted small">{{ t.item.start }}<text v-if="t.item.end"> – {{ t.item.end }}</text><text v-if="t.item.cost"> · ¥{{ t.item.cost }}</text><text v-if="t.item.note"> · {{ t.item.note }}</text></view>
              </view>
              <BookingTag :state="stateOf(t.item.id)" :url="t.item.url || null" @set="(s: BookingState) => setBooking(t.item.id, s)" @open="openBooking(t.item.id, t.item.url!)" />
            </view>
            <text class="link small" @click="go(`/pages/search/flight?tab=flight&trip=${selected.id}`)">去机票比价 ›</text>
          </view>

          <!-- 住宿信息 -->
          <view v-else-if="tab === 'stay'" class="panel">
            <view v-if="!stays.length" class="muted small">行程里还没有登记住宿。<text class="link" @click="go(`/pages/search/hotel?trip=${selected.id}`)">去比价并设为住宿 ›</text></view>
            <view v-for="st in stays" :key="st.id" class="tl no-time">
              <view class="tl-icon"><Icon name="bed" :size="15" color="var(--accent)" /></view>
              <view class="tl-body">
                <view class="tl-name">{{ st.name }}</view>
                <view class="muted small">{{ st.checkIn }} 入住 → {{ st.checkOut }} 退房 · {{ nightsOf(st) }} 晚<text v-if="st.price"> · ¥{{ st.price }}/晚（{{ st.platform }}）</text></view>
                <text class="link small" @click="go(`/pages/search/hotel?trip=${selected.id}&stay=${st.id}`)">比价换酒店 ›</text>
              </view>
              <BookingTag :state="stateOf(st.id)" :url="st.url || null" @set="(s: BookingState) => setBooking(st.id, s)" @open="openBooking(st.id, st.url!)" />
            </view>
          </view>

          <!-- 景点门票 -->
          <view v-else-if="tab === 'ticket'" class="panel">
            <view v-for="(d, i) in days" :key="d.date" class="ticket-day">
              <view class="ph"><text class="strong">第{{ i + 1 }}天 · {{ cnDay(d.date) }}</text><text class="link small" @click="go(`/pages/search/ticket?trip=${selected.id}&day=${i + 1}`)">查这天门票 ›</text></view>
              <view v-for="it in d.items.filter((x) => x.type === 'SPOT')" :key="it.id" class="tl no-time">
                <view class="tl-icon"><Icon name="ticket" :size="15" color="var(--accent)" /></view>
                <view class="tl-body"><view class="tl-name">{{ it.name }}</view><view class="muted small">{{ it.cost ? `行程里写的门票约 ¥${it.cost}` : "门票价格待查" }}</view></view>
                <BookingTag :state="stateOf(it.id)" :url="it.url || null" @set="(s: BookingState) => setBooking(it.id, s)" @open="openBooking(it.id, it.url!)" />
              </view>
              <view v-if="!d.items.some((x) => x.type === 'SPOT')" class="muted small">这天没有景点</view>
            </view>
          </view>

          <!-- 预算结算 -->
          <view v-else class="panel">
            <view v-for="b in budget.rows" :key="b.label" class="budget-row">
              <text>{{ b.label }}</text>
              <text class="muted small">{{ b.unknown ? `${b.unknown} 项没写花费` : "" }}</text>
              <text class="strong">{{ b.sum ? "¥" + b.sum.toLocaleString() : "—" }}</text>
            </view>
            <view class="budget-row total"><text>合计（已知部分）</text><text /><text class="price">¥{{ budget.total.toLocaleString() }}</text></view>
            <view class="muted small">按行程里写的每项花费加起来：吃饭、门票、交通按每人，住宿按每晚价格 × 晚数（每间）。没写花费的项标了"未知"，没算进合计；购物、打车等临时花费不在里面。</view>
          </view>
        </template>
      </view>

      <!-- 右：天气、地图、建议（电脑上） -->
      <view v-if="wide && detail && selected" class="col side-col">
        <view class="panel weather" @click="go(`/pages/weather/weather?trip=${selected.id}`)">
          <view class="ph"><text class="strong"><Icon name="weather" :size="15" color="var(--accent)" /> {{ selected.city || "目的地" }} · 今天天气</text><Icon name="right" :size="14" color="var(--muted)" /></view>
          <view v-if="weather" class="w-now"><text class="w-temp">{{ weather.tempMin }} ~ {{ weather.tempMax }}°C</text><text>{{ weather.text }}</text></view>
          <view v-else class="muted small">{{ weatherError || "加载中…" }}</view>
          <view class="muted small">点开看行程期间每天的天气</view>
        </view>
        <view class="panel">
          <view class="ph"><text class="panel-title">当日行程地图</text><text class="link small" @click="go(`/pages/plan/map?id=${selected.id}&day=${dayIndex + 1}`)">查看大图 ›</text></view>
          <RouteMap :stops="mapStops" color="2563EB" :w="420" :h="300" />
        </view>
        <view class="panel">
          <view class="panel-title"><Icon name="sparkle" :size="15" color="var(--accent)" /> 行程建议（第{{ dayIndex + 1 }}天）</view>
          <view v-for="s in suggestions" :key="s.text" class="reason" :class="{ warn: s.warn }"><Icon :name="s.warn ? 'bell' : 'checkCircle'" :size="13" :color="s.warn ? 'var(--price)' : 'var(--ok)'" /><text>{{ s.text }}</text></view>
          <view class="muted small">按行程里的时间、路程和安排算出来，不是 AI 编的。</view>
          <view class="side-acts">
            <button class="btn ghost" @click="edit(selected.id, dayIndex + 1)">调整行程</button>
            <button class="btn" @click="go(`/pages/plan/map?id=${selected.id}&day=${dayIndex + 1}`)">查看完整路线</button>
          </view>
        </view>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import Icon from "../../components/Icon.vue";
import RouteMap from "../../components/RouteMap.vue";
import BookingTag from "../../components/BookingTag.vue";
import { computed, ref, watch } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { BookingState, ItineraryItem, Stay, Trip, TripDetail, TripVersion } from "../../api/types";
import { cityCover } from "../../utils/cover";
import { doneable, doneText, openUrl, queriedAt, ymd } from "../../utils/format";
import { cityOf, duration, type MapStop } from "../../utils/place";
import { useWide } from "../../utils/screen";

/**
 * 我的行程（原型⑫）：左边行程列表（按草稿、即将出行、进行中、已完成筛选），中间选中行程的详情
 * （每天安排、交通、住宿、门票、预算），右边天气、当日地图和按数据算的建议。没有行程时显示空状态，不放示例数据。
 * 支持复制行程、恢复历史版本、区分预订状态（计划预订 / 已跳转平台 / 已确认订单）。手机上只显示列表，点开进详情页。
 */
const FILTERS = ["全部", "草稿", "即将出行", "进行中", "已完成"] as const;
const TABS = [
  { key: "plan", label: "行程安排", icon: "calendar" },
  { key: "transport", label: "交通信息", icon: "plane" },
  { key: "stay", label: "住宿信息", icon: "bed" },
  { key: "ticket", label: "景点门票", icon: "ticket" },
  { key: "budget", label: "预算结算", icon: "wallet" },
] as const;
const TYPE_ICON: Record<string, string> = { SPOT: "pin", FOOD: "food", HOTEL: "bed", TRANSPORT: "car", FREE: "sparkle" };

interface Row {
  id: number;
  title: string;
  destination: string;
  city: string;
  dates: string;
  label: string;
  tone: string;
  group: string;
}

const wide = useWide();
const trips = ref<Trip[]>([]);
const loaded = ref(false);
const filter = ref<(typeof FILTERS)[number]>("全部");
const covers = ref<Record<string, string>>({});
const selectedId = ref(0);
const detail = ref<TripDetail | null>(null);
const detailLoading = ref(false);
const tab = ref<(typeof TABS)[number]["key"]>("plan");
const dayIndex = ref(0);
const menuFor = ref(0);
const deleting = ref(0);
const copying = ref(0);
const versionsOpen = ref(false);
const versions = ref<TripVersion[]>([]);
const restoring = ref(0);
const weather = ref<{ text: string; tempMin: number; tempMax: number } | null>(null);
const weatherError = ref("");
let detailSeq = 0;

const today = ymd(new Date());
const rows = computed<Row[]>(() => trips.value.map(rowOf));
const shown = computed(() => (filter.value === "全部" ? rows.value : rows.value.filter((r) => r.group === filter.value)));
const selected = computed(() => rows.value.find((r) => r.id === selectedId.value));
const days = computed(() => detail.value?.version.itinerary?.days || []);
const day = computed(() => days.value[dayIndex.value]);
const stays = computed<Stay[]>(() => detail.value?.version.itinerary?.stays || []);
const transports = computed(() => days.value.flatMap((d, i) => d.items.filter((x) => x.type === "TRANSPORT").map((item) => ({ day: i, item }))));
const mapStops = computed<MapStop[]>(() => (day.value?.items || []).filter((i) => i.lng != null && i.lat != null).map((i) => ({ id: i.id, name: i.name, lng: i.lng!, lat: i.lat! })));

/** 状态按真实数据算：草稿看 status，已确认的按日期分即将出行、进行中、已完成 */
function rowOf(t: Trip): Row {
  const city = cityOf(t.destination || t.title);
  const base = { id: t.id, title: t.title, destination: t.destination || "", city, dates: t.startDate ? `${t.startDate} 至 ${t.endDate || t.startDate}` : "日期待定" };
  if (t.status === "DRAFT") return { ...base, label: t.endDate && t.endDate < today ? "草稿 · 日期已过" : "草稿", tone: "draft", group: "草稿" };
  if (t.status === "FINISHED" || (t.endDate && t.endDate < today)) return { ...base, label: "已完成", tone: "done", group: "已完成" };
  if (t.startDate && t.startDate <= today) return { ...base, label: "进行中", tone: "ok", group: "进行中" };
  if (!t.startDate) return { ...base, label: "日期待定", tone: "draft", group: "即将出行" };
  const n = Math.round((Date.parse(t.startDate) - Date.parse(today)) / 86400000);
  return { ...base, label: `即将出行 · ${n === 1 ? "明天" : n + " 天后"}`, tone: "soon", group: "即将出行" };
}

function countOf(f: string) {
  return f === "全部" ? 0 : rows.value.filter((r) => r.group === f).length;
}

/** 预算：按行程里写的花费分类加起来，住宿按每晚 × 晚数 */
const budget = computed(() => {
  const items = days.value.flatMap((d) => d.items);
  const group = (label: string, list: ItineraryItem[]) => ({ label, sum: list.reduce((s, i) => s + (i.cost || 0), 0), unknown: list.filter((i) => i.cost == null).length });
  const rowsOut = [
    group("吃饭（每人）", items.filter((i) => i.type === "FOOD")),
    group("景点门票（每人）", items.filter((i) => i.type === "SPOT")),
    group("交通（每人）", items.filter((i) => i.type === "TRANSPORT")),
    {
      label: "住宿（每间）",
      sum: stays.value.reduce((s, st) => s + (st.price || 0) * nightsOf(st), 0),
      unknown: stays.value.filter((st) => !st.price).length + (stays.value.length ? 0 : days.value.length > 1 ? 1 : 0),
    },
  ];
  return { rows: rowsOut, total: rowsOut.reduce((s, r) => s + r.sum, 0) };
});

/** 当天的提醒：路上太久、没安排午饭、一天排太满、地点没对上坐标（和行程详情页一样的规则） */
const suggestions = computed(() => {
  const out: { text: string; warn: boolean }[] = [];
  const d = day.value;
  if (!d) return out;
  const name = (id: string) => d.items.find((x) => x.id === id)?.name || "";
  for (const l of d.legs || []) {
    if (l.minutes >= 60) out.push({ text: `${name(l.from)} → ${name(l.to)} 路上要 ${duration(l.minutes)}，可以考虑换近一点的地方`, warn: true });
  }
  const lunch = d.items.some((x) => x.type === "FOOD" && x.start && toMin(x.start) >= 600 && toMin(x.start) <= 840);
  if (!lunch && d.items.length > 2) out.push({ text: "这一天没有安排午饭，可以在美食推荐里加一家", warn: true });
  const times = d.items.flatMap((x) => [x.start, x.end]).filter((x): x is string => !!x).map(toMin);
  if (times.length && Math.max(...times) - Math.min(...times) >= 11 * 60) out.push({ text: `这一天从早到晚排了 ${duration(Math.max(...times) - Math.min(...times))}，带老人小孩可以删掉一两项`, warn: true });
  const missing = d.items.filter((x) => ["SPOT", "FOOD", "HOTEL"].includes(x.type) && x.lng == null).length;
  if (missing && detail.value?.version.itinerary?.geo) out.push({ text: `有 ${missing} 个地点在高德上没对上，地图和路程里没算它们`, warn: false });
  const unpriced = d.items.filter((x) => x.type === "SPOT" && x.cost == null).length;
  if (unpriced) out.push({ text: `${unpriced} 个景点的门票价格待查，可以在"景点门票"里查`, warn: false });
  if (!out.length) out.push({ text: "这一天的路线和时间都比较顺，没发现明显问题", warn: false });
  return out;
});

onShow(load);

async function load() {
  try {
    trips.value = await api.trips();
    for (const city of new Set(rows.value.map((r) => r.city))) {
      if (city && !(city in covers.value)) cityCover(city).then((url) => (covers.value = { ...covers.value, [city]: url }));
    }
    if (wide.value && trips.value.length && !trips.value.some((t) => t.id === selectedId.value)) {
      // 默认选最近要出发的，其次第一个
      const soon = rows.value.filter((r) => r.group === "进行中" || r.group === "即将出行");
      pick((soon[0] || rows.value[0]).id);
    } else if (selectedId.value) {
      loadDetail(selectedId.value);
    }
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
}

/** 选中一条：电脑上在中间显示详情，手机上进详情页 */
function pick(id: number) {
  menuFor.value = 0;
  if (!wide.value) {
    openDetail(id);
    return;
  }
  if (selectedId.value === id && detail.value) return;
  selectedId.value = id;
  dayIndex.value = 0;
  tab.value = "plan";
  versionsOpen.value = false;
  loadDetail(id);
}

async function loadDetail(id: number) {
  const mine = ++detailSeq;
  detailLoading.value = true;
  try {
    const d = await api.trip(id);
    if (mine !== detailSeq) return;
    detail.value = d;
    dayIndex.value = Math.min(dayIndex.value, Math.max((d.version.itinerary?.days.length || 1) - 1, 0));
  } catch (e) {
    if (mine === detailSeq) showError(e);
  } finally {
    if (mine === detailSeq) detailLoading.value = false;
  }
}

watch(selected, (r) => {
  weather.value = null;
  weatherError.value = "";
  if (!r?.city) return;
  api.weatherToday(r.city).then((w) => (weather.value = w)).catch((e) => (weatherError.value = e instanceof Error ? e.message : "天气暂时查不到"));
});

function toMin(hhmm: string) {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + (m || 0);
}

function cnDay(d: string) {
  const w = "日一二三四五六"[new Date(d + "T00:00:00").getDay()];
  return `${Number(d.slice(5, 7))}月${Number(d.slice(8, 10))}日（周${w}）`;
}

function nightsOf(st: Stay) {
  return Math.max(0, Math.round((Date.parse(st.checkOut) - Date.parse(st.checkIn)) / 86400000));
}

/** 能预订的项才显示预订状态：住宿、交通，以及有门票或预订链接的景点 */
function bookable(it: ItineraryItem) {
  return it.type === "HOTEL" || it.type === "TRANSPORT" || (it.type === "SPOT" && (!!it.cost || !!it.url));
}

function isDone(id: string) {
  return !!detail.value?.version.itinerary?.done?.includes(id);
}

/** 这天景点和吃饭里标了去过 / 吃过的有几项 */
const dayProgress = computed(() => {
  const items = (day.value?.items || []).filter((x) => doneable(x.type));
  return { done: items.filter((x) => isDone(x.id)).length, total: items.length };
});

/** 标记或取消"去过了 / 吃过了"：只记在当前版本上，不算新版本 */
async function toggleDone(it: ItineraryItem) {
  if (!selected.value) return;
  const done = !isDone(it.id);
  try {
    detail.value = await api.setDone(selected.value.id, it.id, done);
  } catch (e) {
    showError(e);
  }
}

function stateOf(id: string): BookingState {
  const it = detail.value?.version.itinerary;
  if (it?.booked?.includes(id)) return "CONFIRMED";
  return it?.bookingStatus?.[id] === "OPENED" ? "OPENED" : "PLANNED";
}

async function setBooking(targetId: string, status: BookingState) {
  if (!selected.value) return;
  try {
    detail.value = await api.setBooking(selected.value.id, targetId, status);
  } catch (e) {
    showError(e);
  }
}

/** 点预订链接：打开平台，同时记成"已跳转平台"（还不算订好，要用户自己确认） */
function openBooking(targetId: string, url: string) {
  openUrl(url);
  if (stateOf(targetId) === "PLANNED") setBooking(targetId, "OPENED");
}

function go(url: string) {
  uni.navigateTo({ url });
}

function openDetail(id: number) {
  menuFor.value = 0;
  go(`/pages/trips/detail?id=${id}`);
}

/** 有逐日安排的去编辑页，早期只有正文的去对话里改 */
async function edit(id: number, dayNo = 1) {
  menuFor.value = 0;
  try {
    const d = detail.value?.trip.id === id ? detail.value : await api.trip(id);
    if (d.version.itinerary?.days?.length) {
      go(`/pages/plan/edit?id=${id}&day=${dayNo}`);
      return;
    }
    uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent(`我想修改行程「${d.trip.title}」（tripId=${id}）`) });
  } catch (e) {
    showError(e);
  }
}

/** 复制成新的草稿行程，预订状态清空 */
async function copy(id: number) {
  if (copying.value) return;
  copying.value = id;
  try {
    const d = await api.copyTrip(id);
    menuFor.value = 0;
    uni.showToast({ title: "已复制成新草稿", icon: "none" });
    await load();
    filter.value = "全部";
    pick(d.trip.id);
  } catch (e) {
    showError(e);
  } finally {
    copying.value = 0;
  }
}

function share() {
  menuFor.value = 0;
  uni.showToast({ title: "分享还没做", icon: "none" });
}

function askDelete(id: number) {
  menuFor.value = 0;
  deleting.value = id;
}

async function doDelete(id: number) {
  try {
    await api.deleteTrip(id);
    deleting.value = 0;
    if (selectedId.value === id) {
      selectedId.value = 0;
      detail.value = null;
    }
    await load();
  } catch (e) {
    showError(e);
  }
}

async function toggleVersions() {
  versionsOpen.value = !versionsOpen.value;
  if (versionsOpen.value && selected.value) {
    versions.value = [];
    try {
      versions.value = (await api.tripVersions(selected.value.id)).sort((a, b) => b.version - a.version);
    } catch (e) {
      showError(e);
    }
  }
}

function compare(v: number) {
  if (!detail.value) return;
  go(`/pages/plan/updated?id=${detail.value.trip.id}&from=${v}&to=${detail.value.version.version}`);
}

/** 恢复：把那一版存成新版本 */
async function restore(v: number) {
  if (!selected.value) return;
  try {
    detail.value = await api.restoreVersion(selected.value.id, v);
    restoring.value = 0;
    uni.showToast({ title: `已恢复到第 ${v} 版的内容`, icon: "none" });
    versions.value = (await api.tripVersions(selected.value.id)).sort((a, b) => b.version - a.version);
    trips.value = await api.trips();
  } catch (e) {
    showError(e);
  }
}

function toChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.head-icon { width: 44px; height: 44px; border-radius: 12px; background: var(--accent-soft); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.head-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.title { font-size: 20px; font-weight: 800; }
.sub { font-size: 13px; }
.new-btn { margin: 0; gap: 6px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.strong { font-weight: 700; }
.link { color: var(--accent); cursor: pointer; }
.state { text-align: center; padding: 40px 0; font-size: 13px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.panel-title { font-weight: 800; font-size: 15px; display: flex; align-items: center; gap: 4px; }
.ph { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }

.empty-card { display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center; padding: 48px 24px; border-radius: 18px; border: 1px solid var(--line); background: var(--surface); line-height: 1.6; }
.empty-title { font-size: 20px; font-weight: 800; }
.empty-btn { margin: 6px 0 0; gap: 6px; border-radius: 999px; padding: 0 24px; }

.cols { display: flex; flex-direction: column; gap: 14px; }
.list-col { display: flex; flex-direction: column; gap: 10px; }
.filters { display: flex; flex-wrap: wrap; gap: 6px; padding: 4px; border-radius: 12px; background: var(--surface); border: 1px solid var(--line); }
.filter { font-size: 13px; padding: 6px 12px; border-radius: 8px; cursor: pointer; color: var(--muted); }
.filter.on { background: var(--accent); color: #fff; font-weight: 600; }
.cnt { margin-left: 4px; font-size: 11px; opacity: 0.8; }
.card { position: relative; display: grid; grid-template-columns: 96px minmax(0, 1fr) 24px; gap: 12px; padding: 10px; border-radius: 14px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.card.on { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.cover { width: 96px; height: 96px; border-radius: 10px; background: var(--surface-2); }
.cover.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); display: flex; align-items: center; justify-content: center; }
.card-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.card-name { font-weight: 800; font-size: 15px; }
.meta { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--muted); }
.state-tag { align-self: flex-start; font-size: 12px; padding: 2px 10px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.state-tag.soon { background: var(--accent-soft); color: var(--accent); }
.state-tag.ok { background: var(--ok-soft); color: var(--ok); }
.state-tag.draft { background: #fff4ea; color: #c2560c; }
.state-tag.light { background: rgba(255, 255, 255, 0.9); }
.more { align-self: end; cursor: pointer; padding: 2px; }
.menu { position: absolute; right: 8px; bottom: 36px; z-index: 10; display: flex; flex-direction: column; min-width: 120px; padding: 6px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); box-shadow: 0 10px 30px rgba(15, 23, 42, 0.15); cursor: default; }
.menu-item { font-size: 13px; padding: 7px 10px; border-radius: 6px; cursor: pointer; }
.menu-item:hover { background: var(--surface-2); }
.menu-item.danger { color: #dc2626; }
.confirm { grid-column: 1 / -1; display: flex; flex-direction: column; gap: 8px; padding: 10px; border-radius: 10px; background: #fef2f2; font-size: 13px; cursor: default; }
.confirm-acts { display: flex; gap: 8px; }
.confirm-acts .btn { margin: 0; }
.danger-btn { background: #dc2626 !important; }

.main-col, .side-col { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.banner { border-radius: 16px; padding: 22px 22px 18px; background: linear-gradient(110deg, #1e3a8a, #2563eb); background-size: cover; background-position: center; color: #fff; display: flex; flex-direction: column; gap: 14px; }
.banner-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; flex-wrap: wrap; }
.banner-title { font-size: 24px; font-weight: 800; text-shadow: 0 1px 6px rgba(0, 0, 0, 0.3); }
.banner-acts { display: flex; gap: 6px; flex-wrap: wrap; }
.banner-acts .btn { margin: 0; }
.btn.light { background: rgba(255, 255, 255, 0.92) !important; color: var(--ink) !important; }
.banner-meta { display: flex; flex-wrap: wrap; gap: 8px 18px; align-items: center; font-size: 13px; }
.tabs { display: flex; gap: 8px; flex-wrap: wrap; }
.tab { display: inline-flex; align-items: center; gap: 6px; padding: 9px 16px; border-radius: 10px; background: var(--surface); border: 1px solid var(--line); font-size: 14px; cursor: pointer; }
.tab.on { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 700; }
.days { display: flex; gap: 6px; flex-wrap: wrap; }
.day { display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 7px 14px; border-radius: 10px; border: 1px solid var(--line); cursor: pointer; }
.day.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.day-title { font-weight: 800; font-size: 15px; }
.tl { display: grid; grid-template-columns: 52px 28px minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--line); }
.tl:last-child { border-bottom: none; }
/* 住宿、门票没有时间列 */
.tl.done .tl-name { color: var(--muted); }
.tl.done .tl-icon { background: var(--ok-soft); }
.done-btn { align-self: flex-start; margin-top: 2px; font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--line); color: var(--muted); cursor: pointer; }
.done-btn.on { border-color: transparent; background: var(--ok-soft); color: var(--ok); font-weight: 600; }
.progress { margin-left: 8px; font-size: 12px; font-weight: 600; color: var(--ok); }
.tl.no-time { grid-template-columns: 28px minmax(0, 1fr) auto; }
.tl-time { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.tl-icon { width: 28px; height: 28px; border-radius: 8px; background: var(--accent-soft); display: flex; align-items: center; justify-content: center; }
.tl-body { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.tl-name { font-weight: 700; font-size: 14px; }
.ticket-day { display: flex; flex-direction: column; gap: 4px; padding-bottom: 8px; }
.budget-row { display: grid; grid-template-columns: 1fr auto 110px; gap: 10px; align-items: baseline; padding: 8px 0; border-bottom: 1px solid var(--line); font-size: 14px; }
.budget-row > :last-child { text-align: right; }
.budget-row.total { border-bottom: none; font-weight: 800; }
.price { font-size: 20px; font-weight: 800; color: var(--price); }
.ver { display: grid; grid-template-columns: 64px minmax(0, 1fr) auto auto; gap: 10px; align-items: center; padding: 6px 0; border-bottom: 1px solid var(--line); }
.ver-no { font-weight: 700; font-size: 13px; }
.ver-no.cur { color: var(--accent); }
.ver-body { min-width: 0; }
.ver-confirm { display: flex; gap: 6px; grid-column: 3 / 5; }
.ver-confirm .btn { margin: 0; }
.tag-cur { font-size: 11px; padding: 1px 8px; border-radius: 6px; background: var(--accent-soft); color: var(--accent); grid-column: 3 / 5; justify-self: end; }

.weather { cursor: pointer; }
.w-now { display: flex; align-items: baseline; gap: 10px; }
.w-temp { font-size: 22px; font-weight: 800; }
.reason { display: flex; align-items: flex-start; gap: 6px; font-size: 13px; line-height: 1.5; }
.reason.warn { color: var(--price); }
.side-acts { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.side-acts .btn { margin: 0; }

@media (min-width: 1024px) {
  .page { max-width: 1600px; padding: 20px 24px; }
  .title { font-size: 24px; }
  .cols { display: grid; grid-template-columns: 340px minmax(0, 1fr) 340px; align-items: start; gap: 16px; }
  .side-col { position: sticky; top: 20px; }
}
</style>
