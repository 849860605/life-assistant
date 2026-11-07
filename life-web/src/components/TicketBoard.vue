<template>
  <view class="tb">
    <!-- 搜索 -->
    <view class="panel search">
      <view v-if="trip" class="trip-link">
        <Icon name="trip" :size="14" color="var(--accent)" />
        <text>基于你的行程：<text class="strong">{{ trip.title }}</text></text>
        <text class="unlink" @click="unlink">不关联</text>
      </view>
      <view class="sbar">
        <view class="sfield"><text class="sk">目的地</text><input id="tb-city" v-model="city" class="sv" placeholder="城市" /></view>
        <view class="sfield grow"><text class="sk">搜索景点</text><input id="tb-keyword" v-model="keyword" class="sv" placeholder="如：兵马俑、大雁塔" @confirm="loadSpots" /></view>
        <view class="sfield">
          <text class="sk">游玩人数</text>
          <view class="counts">
            <text>成人</text><text class="step" @click="adults = Math.max(1, adults - 1)">−</text><text class="strong">{{ adults }}</text><text class="step" @click="adults = Math.min(9, adults + 1)">+</text>
            <text>儿童</text><text class="step" @click="kids = Math.max(0, kids - 1)">−</text><text class="strong">{{ kids }}</text><text class="step" @click="kids = Math.min(9, kids + 1)">+</text>
          </view>
        </view>
        <button class="btn sbtn" :disabled="loading" @click="loadSpots"><Icon name="search" :size="16" color="#fff" /><text>{{ loading ? "查询中…" : "查询门票" }}</text></button>
      </view>
    </view>

    <view class="toolbar">
      <view class="fgroup">
        <text v-for="c in CATS" :key="c.key" class="chip" :class="{ on: category === c.key }" @click="setCategory(c.key)">{{ c.label }}</text>
      </view>
      <view class="fgroup">
        <text class="flabel">票种</text>
        <text v-for="t in TYPES" :key="t" class="chip" :class="{ on: ticketType === t }" @click="ticketType = t">{{ t }}</text>
      </view>
    </view>

    <view class="grid">
      <!-- 左：景点列表 -->
      <view class="list">
        <view class="lhead"><text class="panel-title">景点<text class="muted">（{{ spots.length }} 个）</text></text><text class="muted small">飞猪 · <text class="link" @click="refreshSpots">{{ spotsRefreshing ? "刷新中…" : "刷新" }}</text></text></view>
        <view v-if="loading" class="state">正在飞猪上找景点…</view>
        <view v-else-if="error" class="state err">{{ error }}</view>
        <view v-for="(s, i) in spots" :id="'sp-' + i" :key="s.id || s.name" class="card" :class="{ on: selected?.name === s.name }" @click="select(s)">
          <view class="card-top">
            <view class="img-wrap">
              <image v-if="s.photo && !broken[s.name]" class="img" :src="s.photo" mode="aspectFill" @error="broken[s.name] = true" />
              <view v-else class="img blank"><Icon name="ticket" :size="24" color="#fff" /></view>
              <text class="letter">{{ LETTERS[i] }}</text>
            </view>
            <view class="body">
              <view class="name-row">
                <view class="name">{{ s.name }}<text v-if="s.level" class="lvl">{{ s.level }}A</text></view>
                <FavButton :item="{ kind: 'SPOT', refKey: s.id || s.name, title: s.name, subtitle: s.category || s.address, city, url: s.url || amapUrl(s, city), photo: s.photo }" />
              </view>
              <view class="tags">
                <text v-if="s.category" class="tag">{{ s.category }}</text>
                <text v-if="s.free === true" class="tag warn">免费 · 是否要预约需确认</text>
                <text v-else-if="s.free === false" class="tag">收费</text>
                <text v-if="tripDayOf(s)" class="tag cur">行程 {{ tripDayOf(s) }}</text>
              </view>
              <view v-if="s.rank" class="muted small">{{ s.rank }}</view>
              <view v-if="s.description" class="desc">{{ s.description }}</view>
            </view>
          </view>
          <view class="card-acts" @click.stop>
            <button v-if="s.free !== true" class="btn small" :disabled="ticketsOf(s)?.loading" @click="toggleTickets(s)">{{ ticketsOf(s)?.open ? "收起门票" : ticketsOf(s)?.loading ? "查询中…" : "查门票" }}</button>
            <button class="btn ghost small" :disabled="rulesOf(s)?.loading" @click="loadRules(s)">{{ rulesOf(s)?.loading ? "问携程中，约 15 秒…" : s.free ? "查预约入口和规则" : "查预约规则" }}</button>
            <button class="btn ghost small" :disabled="tuniuOf(s)?.loading" @click="loadTuniu(s)">{{ tuniuOf(s)?.loading ? "正在查途牛…" : "查途牛价格" }}</button>
            <text v-if="s.url" class="link" @click="openUrl(s.url!)">飞猪景点页 ›</text>
          </view>
          <view v-if="ticketsOf(s)?.open" class="products" @click.stop>
            <view v-if="ticketsOf(s)!.error" class="err small">{{ ticketsOf(s)!.error }}</view>
            <view v-else-if="!productsOf(s).length" class="muted small">飞猪上没找到这个景点的门票商品，去景区官方渠道看看。</view>
            <view v-for="p in productsOf(s)" :key="p.title" class="product">
              <view class="p-head">
                <text class="p-title">{{ p.title }}</text>
                <text v-if="p.bundle" class="tag warn">含讲解/套餐</text>
              </view>
              <view class="skus">
                <text v-for="k in skusOf(p)" :key="k.name" class="sku">{{ k.name }} <text class="strong">{{ k.price != null ? "¥" + k.price : "—" }}</text></text>
                <text v-if="!skusOf(p).length" class="muted small">{{ ticketType === "全部" ? "票种名称看不出类别" : `没有明确的${ticketType}票` }}<text v-if="p.price != null">，标价 ¥{{ p.price }}（可能是优惠票或起价）</text></text>
              </view>
              <text v-if="p.url" class="link" @click="openUrl(p.url!)">去飞猪预订 ›</text>
            </view>
          </view>
          <view v-if="tuniuOf(s)?.data || tuniuOf(s)?.error" class="products" @click.stop>
            <view class="p-head"><text class="p-title">途牛</text><text class="muted small">起价是可售日期里的最低价，以下单页为准</text></view>
            <view v-if="tuniuOf(s)!.error" class="err small">{{ tuniuOf(s)!.error }}</view>
            <view v-else-if="!tuniuList(s).length" class="muted small">{{ tuniuOf(s)!.data!.length ? `途牛上没有${ticketType}票` : "途牛上没找到这个景点的门票" }}</view>
            <view v-for="t in tuniuList(s)" :key="t.name" class="tuniu">
              <view class="tn-head">
                <text class="tn-name">{{ t.name }}</text>
                <text class="tn-price">{{ t.price != null ? `¥${t.price}起` : "—" }}</text>
              </view>
              <view class="tn-tags">
                <text v-if="t.type" class="tag">{{ t.type }}</text>
                <text v-if="t.category && t.category !== '景点门票'" class="tag warn">{{ t.category }}</text>
                <text v-if="t.advance" class="tag" :class="{ warn: t.advance !== '可当天订' }">{{ t.advance }}</text>
                <text v-if="t.refund" class="tag">{{ t.refund }}</text>
                <text v-if="t.enter" class="tag">{{ t.enter }}</text>
              </view>
            </view>
            <view v-if="tuniuOf(s)!.data" class="muted small">途牛没给预订链接，去途牛 App 搜「{{ s.name }}」预订。<text v-if="tuniuDate(s)">按 {{ tuniuDate(s) }} 出游查的。</text><text class="link" @click="loadTuniu(s, true)">{{ tuniuOf(s)!.loading ? "重新查询中…" : "重新查（耗途牛额度）" }}</text></view>
          </view>
          <view v-if="rulesOf(s)?.data || rulesOf(s)?.error" class="rules" @click.stop>
            <view v-if="rulesOf(s)!.error" class="err small">{{ rulesOf(s)!.error }}</view>
            <template v-else>
              <view class="res" :class="rulesOf(s)!.data!.reservation">{{ RES_TEXT[rulesOf(s)!.data!.reservation] }}</view>
              <view v-for="(v, k) in rulesOf(s)!.data!.items" :key="k" class="rule"><text class="rule-k">{{ k }}</text><text>{{ v }}</text></view>
              <view v-if="!Object.keys(rulesOf(s)!.data!.items).length" class="small">{{ rulesOf(s)!.data!.summary }}</view>
              <view class="muted small">来自携程问道，规则会变，以景区官方为准。{{ ago(rulesOf(s)!.data!.queriedAt) }}查询 · <text class="link" @click="loadRules(s, true)">{{ rulesOf(s)!.loading ? "重新查询中…" : "重新查" }}</text></view>
            </template>
          </view>
        </view>
        <view v-if="!loading && !error && !spots.length" class="state muted">没找到景点，换个关键词或分类。</view>
      </view>

      <!-- 中：地图和门票助手 -->
      <view class="mid">
        <view class="panel">
          <view class="panel-title">景点分布<text v-if="trip"> 与行程（D{{ dayIndex + 1 }}）</text></view>
          <RouteMap ref="mapRef" :stops="dayStops" :pois="mapPois" :active-poi="selected ? selected.id || selected.name : null" color="2563EB" :w="mapW" :h="mapH" empty-text="搜到景点后在这里标出位置" @poi="onPickPoi" />
          <view class="legend">
            <text v-if="dayStops.length" class="lg"><text class="lg-dot blue" />D{{ dayIndex + 1 }} 游览顺序</text>
            <text class="lg"><text class="lg-dot orange" />搜到的景点</text>
          </view>
        </view>
        <view v-if="trip" class="panel">
          <view class="panel-title"><Icon name="sparkle" :size="15" color="var(--accent)" /> 门票提醒</view>
          <view v-for="t in tips" :key="t.text" class="reason" :class="t.tone"><Icon :name="t.tone === 'bad' ? 'bell' : 'checkCircle'" :size="13" :color="t.tone === 'bad' ? 'var(--price)' : 'var(--ok)'" /><text>{{ t.text }}</text></view>
          <view class="muted small">按行程、飞猪门票和票种名称算出来，不是 AI 编的；预约规则要点"查预约规则"问携程。</view>
        </view>
      </view>

      <!-- 右：行程门票清单 -->
      <view v-if="trip" class="side">
        <view class="panel">
          <view class="ph">
            <text class="panel-title">行程门票安排</text>
            <view class="days">
              <text v-for="(d, i) in trip.days" :key="d.date" class="chip" :class="{ on: i === dayIndex }" @click="pickDay(i)">D{{ i + 1 }}</text>
            </view>
          </view>
          <view class="muted small">{{ day?.date }} · 成人 {{ adults }}<text v-if="kids"> · 儿童 {{ kids }}</text><text v-if="dayTickets"> · {{ queriedText }} · <text class="link" @click="loadDay(true)">{{ dayRefreshing ? "刷新中…" : "刷新" }}</text></text></view>
          <view v-if="dayLoading" class="muted small pad">正在逐个查这天景点的门票…</view>
          <view v-else-if="dayError" class="err small">{{ dayError }}</view>
          <template v-else-if="dayTickets">
            <view v-if="!dayTickets.spots.length" class="muted small">这天没有景点安排。</view>
            <view v-for="r in rows" :key="r.itemId" class="row" :class="{ off: !picked[r.itemId] }">
              <checkbox :checked="picked[r.itemId]" :disabled="r.kind !== 'priced'" @click="picked[r.itemId] = !picked[r.itemId]" />
              <view class="row-body">
                <view class="row-name">{{ r.name }}</view>
                <view class="small" :class="r.kind === 'priced' ? 'muted' : 'warn-t'">{{ r.detail }}</view>
              </view>
              <view class="row-right">
                <text v-if="r.kind === 'priced'" class="strong">¥{{ r.total }}</text>
                <text v-else-if="r.kind === 'booked'" class="ok-t small">已预约</text>
                <text v-else-if="r.kind === 'free'" class="small">¥0</text>
                <text v-else class="muted small">待查</text>
                <text v-if="r.url" class="link small" @click="openUrl(r.url)">去订 ›</text>
              </view>
            </view>
            <view class="total">
              <text>门票合计</text>
              <text class="price">¥{{ total }}</text>
            </view>
            <view v-if="pending" class="muted small">还有 {{ pending }} 项价格待查，没算进合计。</view>
            <view class="muted small">价格来自飞猪（{{ queriedText }}），按票种名称挑的成人价、儿童价；没有明确儿童票时儿童按待查算。预订链接会跳到平台，部分带推广参数，不影响价格；付款在平台上由你自己完成，订好后回行程里标记"已预约"。</view>
          </template>
        </view>
        <view class="panel">
          <view class="panel-title">数据说明</view>
          <view class="muted small">门票商品来自飞猪，途牛的票种和起价点"查途牛价格"才查（途牛每天只有 50 次额度）；携程问道只用来查预约规则和开放时间（每天额度有限）。"免费"只表示不收门票，很多免费博物馆仍要实名预约。</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref } from "vue";
import Icon from "./Icon.vue";
import FavButton from "./FavButton.vue";
import RouteMap from "./RouteMap.vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { DayTickets, ItineraryDay, SpotRules, TicketProduct, TicketSpot, TripDetail, TuniuTicket } from "../api/types";
import { ago, openUrl } from "../utils/format";
import { amapUrl, cityOf, currentCity, type MapStop } from "../utils/place";
import { useWide } from "../utils/screen";

/**
 * 景点门票：飞猪查景点和门票商品（按票种名称分出成人、儿童、学生、老人），携程问道按需查预约规则；
 * 带 trip 参数时右边列出行程某天每个景点的门票和合计，地图上画当天路线。免费不等于不用预约，免费景点一律提示去确认预约。
 * 只跳到平台预订，不下单。query：city、keyword、trip、day
 */
const props = defineProps<{ query: Record<string, string | undefined> }>();

const CATS = [
  { key: "", label: "全部景点" },
  { key: "historic site", label: "历史文化" },
  { key: "museum", label: "博物馆" },
  { key: "temple", label: "寺庙" },
  { key: "nature", label: "自然风光" },
  { key: "landmark", label: "地标" },
  { key: "theme park", label: "主题乐园" },
  { key: "ancient town", label: "古镇" },
];
const TYPES = ["全部", "成人", "儿童", "学生", "老人", "套票"];
const LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const RES_TEXT: Record<string, string> = {
  NEED: "需要提前预约",
  NO_NEED: "说明里写了无需预约",
  UNKNOWN: "说明里没写清要不要预约，去官方渠道确认",
};

const wide = useWide();
const city = ref(currentCity());
const keyword = ref("");
const category = ref("");
const ticketType = ref("全部");
const adults = ref(2);
const kids = ref(0);
const spots = ref<TicketSpot[]>([]);
const loading = ref(false);
const error = ref("");
const selected = ref<TicketSpot | null>(null);
const broken = reactive<Record<string, boolean>>({});
const tickets = reactive<Record<string, { open: boolean; loading: boolean; data: TicketProduct[]; error: string }>>({});
const rules = reactive<Record<string, { loading: boolean; data: SpotRules | null; error: string }>>({});
const trip = ref<{ id: number; title: string; days: ItineraryDay[]; booked: string[] } | null>(null);
const dayIndex = ref(0);
const dayTickets = ref<DayTickets | null>(null);
const dayLoading = ref(false);
const dayError = ref("");
const picked = reactive<Record<string, boolean>>({});
const mapRef = ref<{ focus: (id: string) => void; fitTo: (points: number[][]) => void } | null>(null);
let daySeq = 0;

const day = computed(() => trip.value?.days[dayIndex.value]);
const mapW = computed(() => (wide.value ? 560 : 600));
const mapH = computed(() => (wide.value ? 420 : 360));
const dayStops = computed<MapStop[]>(() =>
  (day.value?.items || []).filter((i) => i.type === "SPOT" && i.lng != null && i.lat != null).map((i) => ({ id: i.id, name: i.name.replace(/[（(].*?[）)]/g, ""), lng: i.lng!, lat: i.lat! })),
);
const mapPois = computed(() =>
  spots.value
    .map((s, i) => ({ s, i }))
    .filter(({ s }) => s.lng != null && s.lat != null)
    .slice(0, 15)
    .map(({ s, i }) => ({ id: s.id || s.name, name: s.name, lng: s.lng!, lat: s.lat!, label: LETTERS[i] })),
);
const queriedText = computed(() => (dayTickets.value ? ago(dayTickets.value.queriedAt) + "查询" : ""));
/** 刷新中：旧结果先留着，只在按钮上显示 */
const spotsRefreshing = ref(false);
const dayRefreshing = ref(false);

/** 清单里的一行：priced 有成人价、free 免费、booked 已预约、unknown 待查 */
const rows = computed(() =>
  (dayTickets.value?.spots || []).map((t) => {
    const booked = trip.value?.booked.includes(t.itemId);
    const p = t.product;
    if (booked) return { itemId: t.itemId, name: t.name, kind: "booked", detail: "你标了已预约", total: 0, url: p?.url || null };
    if (t.free) return { itemId: t.itemId, name: t.name, kind: "free", detail: "免费，但可能要实名预约，点左边\"查预约入口和规则\"", total: 0, url: t.spot?.url || null };
    if (p?.adult != null) {
      const childPart = kids.value ? (p.child != null ? ` + ¥${p.child} × ${kids.value}` : ` + 儿童 ${kids.value} 张待查`) : "";
      const total = p.adult * adults.value + (kids.value && p.child != null ? p.child * kids.value : 0);
      return { itemId: t.itemId, name: t.name, kind: "priced", detail: `¥${p.adult} × ${adults.value}${childPart}`, total, url: p.url };
    }
    return { itemId: t.itemId, name: t.name, kind: "unknown", detail: t.error || (t.spot ? "飞猪上没有看得出成人价的门票" : "飞猪上没对上这个景点"), total: 0, url: t.spot?.url || null };
  }),
);
const total = computed(() => rows.value.filter((r) => r.kind === "priced" && picked[r.itemId]).reduce((s, r) => s + r.total, 0));
const pending = computed(() => rows.value.filter((r) => r.kind === "unknown").length + (kids.value ? rows.value.filter((r) => r.kind === "priced" && r.detail.includes("待查")).length : 0));

/** 门票提醒：按行程和门票数据算 */
const tips = computed(() => {
  const out: { tone: "good" | "bad"; text: string }[] = [];
  for (const t of dayTickets.value?.spots || []) {
    const name = t.name.replace(/[（(].*?[）)]/g, "");
    if (t.free) out.push({ tone: "bad", text: `${name} 免费，但免费不等于不用预约，去之前确认要不要实名预约` });
    const slots = (t.product?.skus || []).filter((k) => /\d{1,2}[点:：]\d{0,2}\s*[-~至到]/.test(k.name));
    if (slots.length) out.push({ tone: "bad", text: `${name} 门票分时段入园，行程是 ${t.start || "?"} 到，订票时选对应时段` });
    if (!t.free && !t.product && !trip.value?.booked.includes(t.itemId)) out.push({ tone: "bad", text: `${name} 的门票价格待查，点"去订"看平台页面` });
  }
  if (!out.length && dayTickets.value?.spots.length) out.push({ tone: "good", text: "这天的门票都查到了价格" });
  return out;
});

init();

async function init() {
  const q = props.query;
  if (q.city) city.value = decodeURIComponent(q.city);
  if (q.keyword) keyword.value = decodeURIComponent(q.keyword);
  if (q.trip) await linkTrip(Number(q.trip), Number(q.day) || 1);
  loadSpots();
}

async function linkTrip(id: number, dayNo: number) {
  try {
    const d: TripDetail = await api.trip(id);
    const it = d.version.itinerary;
    if (!it?.days?.length) return;
    trip.value = { id, title: d.trip.title, days: it.days, booked: it.booked || [] };
    city.value = cityOf(d.trip.destination) || city.value;
    dayIndex.value = Math.min(Math.max(0, dayNo - 1), it.days.length - 1);
    loadDay();
  } catch (e) {
    showError(e);
  }
}

function unlink() {
  trip.value = null;
  dayTickets.value = null;
}

function loadSpots() {
  return runSpots(false);
}

/** 刷新景点：跳过缓存重新查，旧列表和选中的景点先留着 */
async function refreshSpots() {
  if (spotsRefreshing.value || loading.value) return;
  spotsRefreshing.value = true;
  try {
    await runSpots(true);
  } finally {
    spotsRefreshing.value = false;
  }
}

async function runSpots(refresh: boolean) {
  if (!city.value.trim()) return;
  if (!refresh) loading.value = true;
  error.value = "";
  try {
    const keepName = refresh ? selected.value?.name : undefined;
    spots.value = await api.spots(city.value.trim(), keyword.value.trim() || undefined, category.value || undefined, refresh);
    if (keepName) selected.value = spots.value.find((x) => x.name === keepName) || selected.value;
    if (refresh) return;
    if (wide.value && !selected.value) selected.value = spots.value[0] || null;
    await nextTick();
    mapRef.value?.fitTo([...dayStops.value.map((s) => [s.lng, s.lat]), ...mapPois.value.slice(0, 10).map((p) => [p.lng, p.lat])]);
  } catch (e) {
    // 刷新失败时留着旧列表，只弹提示
    if (refresh && spots.value.length) showError(e);
    else error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function setCategory(c: string) {
  if (category.value === c) return;
  category.value = c;
  loadSpots();
}

/** 查这天的门票；refresh 为 true 时跳过缓存，旧清单和勾选先留着 */
async function loadDay(refresh = false) {
  const t = trip.value;
  if (!t || (refresh && dayRefreshing.value)) return;
  const mine = ++daySeq;
  if (refresh) dayRefreshing.value = true;
  else {
    dayLoading.value = true;
    dayTickets.value = null;
  }
  dayError.value = "";
  try {
    const r = await api.tripTickets(t.id, dayIndex.value + 1, refresh);
    if (mine !== daySeq) return;
    dayTickets.value = r;
    r.spots.forEach((s) => {
      if (!refresh || picked[s.itemId] === undefined) picked[s.itemId] = true;
    });
  } catch (e) {
    // 刷新失败时留着旧清单，只弹提示
    if (mine === daySeq && refresh && dayTickets.value) showError(e);
    else if (mine === daySeq) dayError.value = e instanceof Error ? e.message : String(e);
  } finally {
    if (mine === daySeq) {
      dayLoading.value = false;
      dayRefreshing.value = false;
    }
  }
}

function pickDay(i: number) {
  if (i === dayIndex.value) return;
  dayIndex.value = i;
  loadDay();
}

/** 搜到的景点在行程第几天 */
function tripDayOf(s: TicketSpot) {
  const core = s.name.replace(/[（(].*?[）)]/g, "");
  const alias = s.name.match(/[（(]([^）)]{2,10})[）)]/)?.[1];
  const i = (trip.value?.days || []).findIndex((d) => d.items.some((it) => it.type === "SPOT" && (it.name.includes(core) || core.includes(it.name.replace(/[（(].*?[）)]/g, "").split(/\s*[·・]\s*/)[0]) || (alias && it.name.includes(alias)))));
  return i >= 0 ? `D${i + 1}` : "";
}

function select(s: TicketSpot) {
  selected.value = s;
  if (s.lng != null) mapRef.value?.focus(s.id || s.name);
}

function onPickPoi(id: string) {
  const i = spots.value.findIndex((s) => (s.id || s.name) === id);
  if (i < 0) return;
  selected.value = spots.value[i];
  // #ifdef H5
  document.getElementById("sp-" + i)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  // #endif
}

function ticketsOf(s: TicketSpot) {
  return tickets[s.name];
}

/** 门票商品的查询名：括号里的简称（"秦始皇帝陵博物院（兵马俑）"查"兵马俑"） */
function ticketName(s: TicketSpot) {
  return s.name.match(/[（(]([^）)]{2,10})[）)]/)?.[1] || s.name.replace(/[（(].*?[）)]/g, "");
}

async function toggleTickets(s: TicketSpot) {
  const cur = tickets[s.name];
  if (cur && !cur.loading) {
    cur.open = !cur.open;
    return;
  }
  tickets[s.name] = { open: true, loading: true, data: [], error: "" };
  try {
    tickets[s.name].data = await api.spotTickets(city.value, ticketName(s));
  } catch (e) {
    tickets[s.name].error = e instanceof Error ? e.message : String(e);
  } finally {
    tickets[s.name].loading = false;
  }
}

/** 按票种筛选：套票只看含讲解、套餐的商品，其他票种只看单纯门票 */
function productsOf(s: TicketSpot) {
  const list = tickets[s.name]?.data || [];
  if (ticketType.value === "套票") return list.filter((p) => p.bundle);
  if (ticketType.value === "全部") return list;
  return list.filter((p) => !p.bundle);
}

function skusOf(p: TicketProduct) {
  if (ticketType.value === "全部" || ticketType.value === "套票") return p.skus.filter((k) => k.type || p.bundle).slice(0, 8);
  return p.skus.filter((k) => k.type === ticketType.value).slice(0, 8);
}

/** 途牛门票：每天只有 50 次额度，点了才查 */
const tuniu = reactive<Record<string, { loading: boolean; data: TuniuTicket[] | null; error: string }>>({});

function tuniuOf(s: TicketSpot) {
  return tuniu[s.name];
}

/** 行程里有这个景点就按那天查，否则不带日期 */
function tuniuDate(s: TicketSpot) {
  const d = tripDayOf(s);
  return d ? trip.value?.days[Number(d.slice(1)) - 1]?.date || null : null;
}

/** 按票种筛选：套票看园内项目和讲解，其他票种只看对应类别 */
function tuniuList(s: TicketSpot) {
  const list = tuniu[s.name]?.data || [];
  if (ticketType.value === "全部") return list;
  if (ticketType.value === "套票") return list.filter((t) => t.category !== "景点门票");
  // "通用"是不限人群的票，成人也按它买
  return list.filter((t) => t.category === "景点门票" && (t.type === ticketType.value || (ticketType.value === "成人" && t.type === "通用")));
}

async function loadTuniu(s: TicketSpot, refresh = false) {
  if (tuniu[s.name]?.data && !refresh) return;
  if (refresh && tuniu[s.name]?.data) tuniu[s.name].loading = true;
  else tuniu[s.name] = { loading: true, data: null, error: "" };
  try {
    tuniu[s.name].data = await api.tuniuTickets(s.name, tuniuDate(s), refresh);
    tuniu[s.name].error = "";
  } catch (e) {
    if (refresh && tuniu[s.name].data) showError(e);
    else tuniu[s.name].error = e instanceof Error ? e.message : String(e);
  } finally {
    tuniu[s.name].loading = false;
  }
}

function rulesOf(s: TicketSpot) {
  return rules[s.name];
}

async function loadRules(s: TicketSpot, refresh = false) {
  // 重新查时先留着旧结果
  if (refresh && rules[s.name]?.data) rules[s.name].loading = true;
  else rules[s.name] = { loading: true, data: null, error: "" };
  try {
    rules[s.name].data = await api.spotRules(city.value, s.name, refresh);
    rules[s.name].error = "";
  } catch (e) {
    rules[s.name].error = e instanceof Error ? e.message : String(e);
  } finally {
    rules[s.name].loading = false;
  }
}
</script>

<style scoped>
.tb { display: flex; flex-direction: column; gap: 12px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.panel-title { font-weight: 800; font-size: 15px; display: flex; align-items: center; gap: 4px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.strong { font-weight: 700; }
.pad { padding: 10px 0; }
.err { color: var(--price); font-size: 13px; line-height: 1.5; }
.state { text-align: center; font-size: 13px; padding: 24px 0; }
.state.err { color: var(--price); }
.ok-t { color: var(--ok); }
.warn-t { color: var(--price); }
.link { color: var(--accent); cursor: pointer; font-size: 12px; white-space: nowrap; }

.trip-link { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.unlink { margin-left: auto; font-size: 12px; color: var(--muted); cursor: pointer; }
.sbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: stretch; }
.sfield { display: flex; flex-direction: column; gap: 2px; padding: 8px 12px; border-radius: 10px; background: var(--surface-2); min-width: 100px; box-sizing: border-box; }
.sfield.grow { flex: 1; min-width: 180px; }
.sk { font-size: 11px; color: var(--muted); }
.sv { font-size: 15px; font-weight: 700; }
.counts { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.step { width: 22px; height: 22px; border-radius: 6px; background: var(--surface); border: 1px solid var(--line); display: inline-flex; align-items: center; justify-content: center; cursor: pointer; }
.sbtn { margin: 0; gap: 6px; padding: 0 22px; }

.toolbar { display: flex; flex-wrap: wrap; gap: 8px 18px; align-items: center; }
.fgroup { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.flabel { font-size: 12px; color: var(--muted); }
.chip { font-size: 12px; padding: 5px 11px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; white-space: nowrap; }
.chip.on { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }

.grid { display: flex; flex-direction: column; gap: 12px; }
.list { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.lhead { display: flex; justify-content: space-between; align-items: baseline; }
.card { display: flex; flex-direction: column; gap: 10px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 10px; cursor: pointer; scroll-margin: 12px; }
.card.on { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.card-top { display: grid; grid-template-columns: 116px minmax(0, 1fr); gap: 12px; }
.img-wrap { position: relative; }
.img { width: 116px; height: 92px; border-radius: 10px; display: block; background: var(--surface-2); }
.img.blank { background: linear-gradient(135deg, #93c5fd, #2563eb); display: flex; align-items: center; justify-content: center; }
.letter { position: absolute; left: 6px; top: 6px; width: 20px; height: 20px; border-radius: 6px; background: #e8590c; color: #fff; font-size: 11px; font-weight: 800; display: flex; align-items: center; justify-content: center; border: 2px solid #fff; }
.body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.name { font-weight: 800; font-size: 15px; }
.lvl { font-size: 11px; font-weight: 800; color: #c2560c; background: #fff4ea; padding: 1px 6px; border-radius: 4px; margin-left: 6px; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); color: var(--muted); white-space: nowrap; }
.tag.warn { background: #fff4ea; color: #c2560c; }
.tag.cur { background: var(--accent-soft); color: var(--accent); font-weight: 700; }
.desc { font-size: 12px; color: var(--muted); line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-acts { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.card-acts .btn { margin: 0; }
.tuniu { display: flex; flex-direction: column; gap: 4px; padding: 6px 0; border-bottom: 1px solid var(--line); }
.tuniu:last-of-type { border-bottom: none; }
.tn-head { display: flex; justify-content: space-between; gap: 8px; }
.tn-name { font-size: 13px; font-weight: 600; min-width: 0; }
.tn-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.tn-tags .tag { background: var(--surface); }
.tn-price { font-weight: 800; color: var(--price); font-variant-numeric: tabular-nums; white-space: nowrap; }
.products, .rules { display: flex; flex-direction: column; gap: 8px; padding: 10px; border-radius: 10px; background: var(--surface-2); cursor: default; }
.product { display: flex; flex-direction: column; gap: 4px; padding-bottom: 8px; border-bottom: 1px solid var(--line); }
.product:last-child { border-bottom: none; padding-bottom: 0; }
.p-head { display: flex; gap: 6px; align-items: flex-start; }
.p-title { font-size: 13px; font-weight: 600; flex: 1; min-width: 0; }
.skus { display: flex; flex-wrap: wrap; gap: 6px; }
.sku { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--surface); }
.res { font-size: 13px; font-weight: 700; color: var(--price); }
.res.NO_NEED { color: var(--ok); }
.rule { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 8px; font-size: 12px; line-height: 1.5; }
.rule-k { color: var(--muted); }

.mid, .side { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.legend { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 12px; }
.lg { display: inline-flex; align-items: center; gap: 6px; }
.lg-dot { display: inline-block; width: 12px; height: 12px; border-radius: 50%; }
.lg-dot.blue { background: #2563eb; }
.lg-dot.orange { background: #e8590c; border-radius: 3px; }
.reason { display: flex; align-items: flex-start; gap: 6px; font-size: 13px; line-height: 1.5; }
.reason.bad { color: var(--price); }

.ph { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.days { display: flex; gap: 4px; }
.row { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--line); }
.row.off .row-name { color: var(--muted); }
.row-name { font-weight: 700; font-size: 13px; }
.row-right { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; white-space: nowrap; }
.total { display: flex; justify-content: space-between; align-items: baseline; font-weight: 800; }
.price { font-size: 22px; font-weight: 800; color: var(--price); font-variant-numeric: tabular-nums; }

/* 手机上关联了行程时，门票清单放最前面 */
.side { order: -1; }
@media (min-width: 1024px) {
  .side { order: 0; }
  .grid { display: grid; grid-template-columns: minmax(0, 1fr) 420px; align-items: start; gap: 16px; }
  .mid { position: sticky; top: 20px; }
  .side { grid-column: 2; }
}
@media (min-width: 1440px) {
  .grid { grid-template-columns: minmax(0, 1fr) 420px 340px; }
  .side { grid-column: 3; grid-row: 1; position: sticky; top: 20px; }
}
.name-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
</style>
