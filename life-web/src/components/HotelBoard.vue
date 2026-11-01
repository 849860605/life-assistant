<template>
  <view class="hb">
    <!-- 搜索 -->
    <view class="panel search">
      <view v-if="trip" class="trip-link">
        <Icon name="trip" :size="14" color="var(--accent)" />
        <text>基于你的行程：<text class="strong">{{ trip.title }}</text></text>
        <text class="unlink" @click="unlink">不关联</text>
      </view>
      <view class="sbar">
        <view class="sfield"><text class="sk">目的地</text><input id="hb-city" v-model="form.city" class="sv" placeholder="城市" /></view>
        <view class="sfield grow"><text class="sk">附近</text><input id="hb-near" v-model="form.near" class="sv" placeholder="景点、商圈、地铁站" /></view>
        <picker mode="date" :value="form.checkIn" :start="today" @change="setIn($event.detail.value)">
          <view class="sfield"><text class="sk">入住</text><text class="sv">{{ shortDate(form.checkIn) }} {{ weekday(form.checkIn) }}</text></view>
        </picker>
        <picker mode="date" :value="form.checkOut" :start="form.checkIn" @change="form.checkOut = $event.detail.value">
          <view class="sfield"><text class="sk">退房</text><text class="sv">{{ shortDate(form.checkOut) }} {{ weekday(form.checkOut) }}</text></view>
        </picker>
        <view class="sfield"><text class="sk">晚数</text><text class="sv">共 {{ nights }} 晚</text></view>
        <button class="btn sbtn" :disabled="loading" @click="search"><Icon name="search" :size="16" color="#fff" /><text>{{ loading ? "查询中…" : "查询酒店" }}</text></button>
      </view>
      <view class="muted small">价格是 1 间房每晚的价格，来自飞猪；选中一家后可以比较携程、美团的价格。</view>
    </view>

    <!-- 排序和筛选 -->
    <view class="toolbar">
      <view class="sorts">
        <text v-for="s in SORTS" :key="s.key" class="chip" :class="{ on: sort === s.key }" @click="sort = s.key">{{ s.label }}</text>
      </view>
      <view class="fgroup">
        <text class="flabel">档次</text>
        <text v-for="t in TIERS" :key="t" class="chip" :class="{ on: tiers.includes(t) }" @click="toggle(tiers, t)">{{ t }}</text>
      </view>
      <view class="fgroup">
        <text class="flabel">类型</text>
        <text v-for="t in TYPES" :key="t.key" class="chip" :class="{ on: type === t.key }" @click="setType(t.key)">{{ t.label }}</text>
      </view>
      <view class="fgroup">
        <text class="flabel">每晚</text>
        <text v-for="p in PRICES" :key="p.label" class="chip" :class="{ on: maxPrice === p.value }" @click="maxPrice = p.value">{{ p.label }}</text>
      </view>
      <view class="fgroup">
        <text class="flabel">距离</text>
        <text v-for="r in RADII" :key="r" class="chip" :class="{ on: radius === r }" @click="radius = r">{{ r / 1000 }} 公里内</text>
      </view>
    </view>

    <view v-if="currentStay" class="current">
      <Icon name="bed" :size="16" color="var(--accent)" />
      <text>行程现在住：<text class="strong">{{ currentStay.name }}</text><text v-if="currentStay.price"> · ¥{{ currentStay.price }}/晚（{{ currentStay.platform }}）</text></text>
      <text v-if="stayBooked" class="badge ok">已预约</text>
    </view>

    <view v-if="loading" class="state">正在飞猪上找{{ form.near ? form.near + "附近" : form.city }}的酒店，约 5–10 秒…</view>
    <view v-else-if="error" class="state err">{{ error }}</view>
    <view v-else-if="!result" class="state muted">填好城市和日期后点"查询酒店"。</view>
    <view v-else class="grid">
      <!-- 左：酒店列表 -->
      <view class="list">
        <view class="lhead">
          <text class="panel-title">为你推荐<text class="muted">（{{ shown.length }} 家）</text></text>
          <text class="muted small refresh-line">{{ queriedText }} · <text class="link" @click="refreshList">{{ refreshing ? "刷新中…" : "刷新" }}</text></text>
        </view>
        <view v-if="result.note" class="muted small">{{ result.note }}</view>
        <view
          v-for="h in shown"
          :id="'ht-' + letterOf(h)"
          :key="h.id || h.name"
          class="card"
          :class="{ on: selected?.name === h.name }"
          @click="select(h)"
        >
          <view class="img-wrap">
            <image v-if="h.photo && !broken[h.name]" class="img" :src="h.photo" mode="aspectFill" @error="broken[h.name] = true" />
            <view v-else class="img blank"><Icon name="bed" :size="26" color="#fff" /></view>
            <text class="letter">{{ letterOf(h) }}</text>
            <text v-if="pickTitle(h)" class="pick-badge">{{ pickTitle(h) }}</text>
          </view>
          <view class="body">
            <view class="name">{{ h.name }}</view>
            <view class="meta">
              <text v-if="h.rating" class="rating">★ {{ h.rating }}</text><text v-else class="muted">暂无评分</text>
              <text v-if="h.star" class="sep">·</text><text v-if="h.star">{{ h.star }}</text>
              <text v-if="h.renovated" class="sep">·</text><text v-if="h.renovated" class="muted">{{ h.renovated }} 年装修</text>
            </view>
            <view class="meta">
              <Icon name="pin" :size="12" color="var(--muted)" />
              <text v-if="h.distance != null">离{{ result.near }} {{ distanceText(h.distance) }}</text>
              <text v-if="tripKm(h, days) != null" class="muted"> · 到行程景点平均 {{ tripKm(h, days) }} 公里</text>
            </view>
            <view class="tags">
              <text v-if="h.near" class="tag">{{ h.near }}</text>
              <text v-if="isCurrent(h)" class="tag cur">行程现在住这家</text>
              <text v-if="isHostel(h)" class="tag warn">青旅/床位</text>
            </view>
          </view>
          <view class="price-col">
            <text class="price">{{ h.price != null ? "¥" + h.price : "—" }}</text><text class="muted small">/晚</text>
            <text v-if="h.price != null" class="muted small">{{ nights }} 晚约 ¥{{ h.price * nights }}</text>
          </view>
        </view>
        <view v-if="!shown.length" class="state muted">没有符合筛选的酒店，放宽档次、价格或距离试试。</view>
      </view>

      <!-- 中：地图和推荐理由 -->
      <view class="mid">
        <view class="panel">
          <view class="panel-title">酒店位置与行程</view>
          <RouteMap ref="mapRef" :stops="mapStops" :pois="mapPois" :route="false" :active-poi="selected ? selected.id || selected.name : null" color="7C3AED" :w="mapW" :h="mapH" @poi="onPickPoi" />
          <view class="legend">
            <text class="lg"><text class="lg-dot orange" />候选酒店</text>
            <text v-if="mapStops.length" class="lg"><text class="lg-dot purple" />行程景点</text>
            <text class="muted small">直线距离按高德坐标算</text>
          </view>
        </view>
        <view v-if="selected && wide" class="panel">
          <view class="panel-title"><Icon name="sparkle" :size="15" color="var(--accent)" /> 推荐理由</view>
          <view v-for="r in reasons" :key="r.text" class="reason" :class="r.tone"><Icon :name="r.tone === 'bad' ? 'bell' : 'checkCircle'" :size="13" :color="r.tone === 'bad' ? 'var(--price)' : 'var(--ok)'" /><text>{{ r.text }}</text></view>
          <view class="muted small">按价格、距离、评分、档次、装修年份算出来，不是 AI 写的。</view>
        </view>
      </view>

      <!-- 右：详情（手机上是底部弹层） -->
      <view v-if="selected && (wide || detailOpen)" class="detail-wrap" :class="{ sheet: !wide }" @click="detailOpen = false">
        <view class="detail panel" @click.stop>
          <view class="d-head">
            <view>
              <view class="d-title">{{ selected.name }}</view>
              <view class="muted small">{{ selected.address }}</view>
            </view>
            <view v-if="!wide" class="d-x" @click="detailOpen = false"><Icon name="close" :size="16" color="var(--muted)" /></view>
          </view>

          <view v-if="!wide" class="d-sec">
            <view class="d-sub">推荐理由</view>
            <view v-for="r in reasons" :key="r.text" class="reason" :class="r.tone"><Icon :name="r.tone === 'bad' ? 'bell' : 'checkCircle'" :size="13" :color="r.tone === 'bad' ? 'var(--price)' : 'var(--ok)'" /><text>{{ r.text }}</text></view>
          </view>

          <view class="d-sec">
            <view class="d-sub">多平台价格对比</view>
            <view class="pf">
              <text class="pf-name">飞猪</text>
              <text class="pf-price">{{ selected.price != null ? "¥" + selected.price : "—" }}</text>
              <text class="muted small pf-basis">候选列表里的每晚最低价</text>
              <text v-if="selected.url" class="pf-go" @click="openUrl(selected.url!)">去预订 ›</text>
            </view>
            <template v-if="quoteOf(selected)?.data">
              <view v-for="q in otherQuotes" :key="q.platform" class="pf">
                <text class="pf-name">{{ q.platform }}</text>
                <text class="pf-price" :class="{ best: q.price != null && q.price === lowestQuote }">{{ q.price != null ? "¥" + q.price : "—" }}</text>
                <text class="muted small pf-basis">{{ q.status === "OK" ? q.basis : q.note || "没查到" }}<text v-if="q.matchedName && !sameHotel(q.matchedName, selected.name)" class="warn-t">；平台上匹配到的是「{{ q.matchedName }}」，确认是同一家</text></text>
                <text v-if="q.url" class="pf-go" @click="openUrl(q.url!)">去预订 ›</text>
              </view>
            </template>
            <view v-else-if="quoteOf(selected)?.error" class="err small">{{ quoteOf(selected)!.error }}</view>
            <button v-else class="btn ghost small" :disabled="quoteOf(selected)?.loading" @click="loadQuote(selected)">{{ quoteOf(selected)?.loading ? "正在查携程、美团、飞猪，约 15 秒…" : "比较各平台价格" }}</button>
            <view v-if="quoteOf(selected)?.data" class="muted small">{{ ago(quoteOf(selected)!.data!.queriedAt) }}比价 · <text class="link" @click="loadQuote(selected, true)">{{ quoteOf(selected)!.loading ? "重新比价中…" : "重新比价" }}</text></view>
            <view class="muted small">各平台的价格口径不同（有的是酒店起价、有的是具体房型），以平台页面为准。预订链接部分带推广参数，不影响排序和推荐；付款在平台上由你自己完成。</view>
          </view>

          <view class="d-sec">
            <view class="d-sub">取消政策和早餐</view>
            <view class="muted small">取消政策和含不含早要看具体房型，这里拿不到可靠数据，不展示；预订前在平台的房型页确认。</view>
          </view>

          <view v-if="trip" class="d-sec">
            <view class="d-sub">与我的行程联动</view>
            <view class="flow">
              <view v-if="arrival" class="flow-step"><Icon name="plane" :size="14" color="var(--accent)" /><view><view class="small strong">抵达</view><view class="muted small">{{ arrival }}</view></view></view>
              <view class="flow-step"><Icon name="bed" :size="14" color="var(--accent)" /><view><view class="small strong">入住</view><view class="muted small">{{ selected.name }}</view></view></view>
              <view v-if="nextUp" class="flow-step"><Icon name="calendar" :size="14" color="var(--accent)" /><view><view class="small strong">继续行程</view><view class="muted small">{{ nextUp }}</view></view></view>
            </view>
            <button class="btn" :disabled="isCurrent(selected) || stayBooked || !!staySheet" @click="openStayPreview(selected)">{{ isCurrent(selected) ? "行程现在就住这家" : stayBooked ? "现在的住宿已预约，先在行程里取消" : currentStay ? "替换当前行程酒店（预览）" : "设为行程住宿（预览）" }}</button>
          </view>
        </view>
      </view>
    </view>

    <!-- 换酒店预览 -->
    <view v-if="staySheet" class="mask" @click="closeSheet">
      <view class="sheet-box" @click.stop>
        <view class="sh-head">
          <text class="sh-title">{{ currentStay ? "替换行程酒店" : "设为行程住宿" }}</text>
          <text class="preview-tag">预览，还没保存</text>
          <view class="d-x" @click="closeSheet"><Icon name="close" :size="16" color="var(--muted)" /></view>
        </view>
        <view v-if="staySheet.error" class="err">{{ staySheet.error }}</view>
        <view v-else-if="!staySheet.preview" class="muted small">正在用高德重算每天的路线…</view>
        <template v-else>
          <view class="swap-box">
            <view v-if="staySheet.preview.replacesName" class="old">{{ staySheet.preview.replacesName }}<text v-if="staySheet.preview.oldPrice" class="muted small"> · ¥{{ staySheet.preview.oldPrice }}/晚</text></view>
            <view class="new">{{ staySheet.preview.stay.name }}<text v-if="staySheet.preview.stay.price" class="muted small"> · ¥{{ staySheet.preview.stay.price }}/晚</text></view>
            <view class="muted small">{{ staySheet.preview.stay.checkIn }} 入住 → {{ staySheet.preview.stay.checkOut }} 退房，{{ staySheet.preview.nights }} 晚</view>
          </view>
          <view class="days">
            <view class="drow head"><text>日期</text><text>路上时间</text><text>里程</text><text>打车费</text></view>
            <view v-for="d in staySheet.preview.days" :key="d.day" class="drow">
              <text class="strong">D{{ d.day }}</text>
              <text>{{ duration(d.before.minutes) }} → {{ duration(d.after.minutes) }}<text class="nowrap" :class="tone(d.after.minutes - d.before.minutes)"> {{ delta(d.after.minutes - d.before.minutes, " 分钟") }}</text></text>
              <text>{{ d.before.km }} → {{ d.after.km }}</text>
              <text>{{ d.before.taxi != null ? "¥" + d.before.taxi : "—" }} → {{ d.after.taxi != null ? "¥" + d.after.taxi : "—" }}</text>
            </view>
          </view>
          <view v-if="!staySheet.preview.replacesId" class="muted small">原来没登记住宿时，往返酒店的路没算进去，所以加上住宿后路上时间会变长，这是更真实的数字。</view>
          <view class="checks">
            <view v-for="n in staySheet.preview.notes" :key="n" class="reason note"><Icon name="bell" :size="13" color="var(--muted)" /><text>{{ n }}</text></view>
          </view>
          <view class="acts">
            <button class="btn" :disabled="saving" @click="confirmStay">{{ saving ? "保存中…" : "确认并保存" }}</button>
            <button class="btn ghost" :disabled="saving" @click="closeSheet">不用了</button>
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref } from "vue";
import Icon from "./Icon.vue";
import RouteMap from "./RouteMap.vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { HotelComparison, HotelOption, HotelSearchResult, ItineraryDay, Stay, StayPick, StayPreview, TripDetail } from "../api/types";
import { addDays, ago, openUrl, shortDate, weekday, ymd } from "../utils/format";
import { cityOf, currentCity, distanceText, duration, type MapStop } from "../utils/place";
import { useWide } from "../utils/screen";
import { TIERS, centralSpot, hotelPicks, hotelReasons, isHostel, sortHotels, tierOf, tripKm, tripSpots, type HotelSort } from "../utils/hotels";

/**
 * 酒店比价：飞猪按地标附近找候选（带这几天的价格），地图上和行程景点放在一起看，选中一家后比较各平台价格、
 * 看推荐理由；带 trip 参数时日期、地标从行程来，可以预览后替换行程里的酒店（重算每天路线）。只跳到平台预订，不下单。
 * query：city、place、checkIn、checkOut、trip、stay
 */
const props = defineProps<{ query: Record<string, string | undefined> }>();

const SORTS: { key: HotelSort; label: string }[] = [
  { key: "recommend", label: "推荐排序" },
  { key: "price", label: "价格从低到高" },
  { key: "rating", label: "评分从高到低" },
  { key: "distance", label: "距离最近" },
];
const TYPES = [
  { key: "", label: "不限" },
  { key: "hotel", label: "酒店" },
  { key: "homestay", label: "民宿" },
  { key: "inn", label: "客栈" },
];
const PRICES: { label: string; value: number | null }[] = [
  { label: "不限", value: null },
  { label: "¥200 内", value: 200 },
  { label: "¥400 内", value: 400 },
  { label: "¥700 内", value: 700 },
];
const RADII = [1000, 3000, 5000, 10000];
const LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

const wide = useWide();
const today = ymd(new Date());
const form = reactive({ city: currentCity(), near: "", checkIn: ymd(new Date(Date.now() + 7 * 86400000)), checkOut: ymd(new Date(Date.now() + 8 * 86400000)) });
const trip = ref<{ id: number; title: string; days: ItineraryDay[]; stays: Stay[]; booked: string[] } | null>(null);
const stayId = ref<string | null>(null);
const result = ref<HotelSearchResult | null>(null);
const loading = ref(false);
const error = ref("");
const sort = ref<HotelSort>("recommend");
const tiers = ref<string[]>([]);
const type = ref("");
const maxPrice = ref<number | null>(null);
const radius = ref(5000);
const selected = ref<HotelOption | null>(null);
const detailOpen = ref(false);
const broken = reactive<Record<string, boolean>>({});
const quotes = reactive<Record<string, { loading: boolean; data: HotelComparison | null; error: string }>>({});
const staySheet = ref<{ pick: StayPick; preview: StayPreview | null; error: string } | null>(null);
const saving = ref(false);
const mapRef = ref<{ focus: (id: string) => void; fitTo: (points: number[][]) => void } | null>(null);
/** 搜索、预览各自的请求序号：条件变了或关掉弹层时丢掉旧的结果 */
let seq = 0;
let previewSeq = 0;

const days = computed(() => trip.value?.days || []);
const nights = computed(() => Math.max(1, Math.round((Date.parse(form.checkOut) - Date.parse(form.checkIn)) / 86400000) || 1));
const currentStay = computed(() => (trip.value ? trip.value.stays.find((s) => s.id === stayId.value) || null : null));
const stayBooked = computed(() => !!currentStay.value && !!trip.value?.booked.includes(currentStay.value.id));
const shown = computed(() => {
  const list = (result.value?.hotels || []).filter(
    (h) =>
      (!tiers.value.length || tiers.value.includes(tierOf(h.star) || "")) &&
      (maxPrice.value == null || (h.price != null && h.price <= maxPrice.value)) &&
      (h.distance == null || h.distance <= radius.value),
  );
  return sortHotels(list, sort.value, days.value);
});
const picks = computed(() => hotelPicks(shown.value, days.value));
const reasons = computed(() => (selected.value ? hotelReasons(selected.value, shown.value, days.value, result.value?.near || null, nights.value) : []));
const queriedText = computed(() => (result.value?.queriedAt ? ago(result.value.queriedAt) + "查询" : ""));
/** 正在刷新候选列表（旧列表先留着） */
const refreshing = ref(false);
const mapW = computed(() => (wide.value ? 560 : 600));
const mapH = computed(() => (wide.value ? 400 : 360));
const mapStops = computed<MapStop[]>(() => tripSpots(days.value).slice(0, 12).map((s) => ({ id: s.id, name: s.name.replace(/[（(].*?[）)]/g, ""), lng: s.lng!, lat: s.lat! })));
const mapPois = computed(() =>
  shown.value
    .slice(0, 15)
    .filter((h) => h.lng != null && h.lat != null)
    .map((h) => ({ id: h.id || h.name, name: h.name, lng: h.lng!, lat: h.lat!, label: letterOf(h) })),
);
const otherQuotes = computed(() => (selected.value ? quoteOf(selected.value)?.data?.hotels[0]?.quotes.filter((q) => q.platform !== "飞猪" || q.price !== selected.value!.price) || [] : []));
const lowestQuote = computed(() => {
  const ps = otherQuotes.value.map((q) => q.price).filter((p): p is number => p != null);
  if (selected.value?.price != null) ps.push(selected.value.price);
  return ps.length ? Math.min(...ps) : null;
});
/** 第一天落地的航班（行程里带航班号的交通项） */
const arrival = computed(() => {
  const f = days.value[0]?.items.find((i) => i.type === "TRANSPORT" && /[A-Z0-9]{2}\d{3,4}/.test(i.name));
  return f ? `${shortDate(days.value[0].date)} ${f.end || f.start} · ${f.name.split(" ").slice(0, 2).join(" ")}` : null;
});
/** 入住后第一项景点或吃饭 */
const nextUp = computed(() => {
  const d = days.value[0];
  const it = d?.items.find((i) => i.type === "SPOT" || i.type === "FOOD");
  return it ? `D1 ${it.start || ""} · ${it.name.replace(/^(早餐|午餐|晚餐)[：:]\s*/, "")}` : null;
});

init();

async function init() {
  const q = props.query;
  if (q.city) form.city = decodeURIComponent(q.city);
  if (q.place) form.near = decodeURIComponent(q.place);
  if (q.checkIn) form.checkIn = q.checkIn;
  if (q.checkOut) form.checkOut = q.checkOut;
  if (q.trip) await linkTrip(Number(q.trip), q.stay);
  if (form.city && (q.trip || q.city)) search();
}

/** 关联行程：城市取目的地，日期取要换的那段住宿（没有就取行程首尾），地标取市区行程景点的中心 */
async function linkTrip(id: number, wantStay?: string) {
  try {
    const d: TripDetail = await api.trip(id);
    const it = d.version.itinerary;
    if (!it?.days?.length) return;
    const stays = it.stays || [];
    trip.value = { id, title: d.trip.title, days: it.days, stays, booked: it.booked || [] };
    const stay = stays.find((s) => s.id === wantStay) || stays[0] || null;
    stayId.value = stay?.id || null;
    form.city = cityOf(d.trip.destination) || form.city;
    form.checkIn = stay?.checkIn || it.days[0].date;
    form.checkOut = stay?.checkOut || it.days[it.days.length - 1].date;
    if (form.checkOut <= form.checkIn) form.checkOut = addDays(form.checkIn, 1);
    if (!form.near) form.near = centralSpot(it.days) || "";
  } catch (e) {
    showError(e);
  }
}

function unlink() {
  trip.value = null;
  stayId.value = null;
}

function setIn(v: string) {
  form.checkIn = v;
  if (form.checkOut <= v) form.checkOut = addDays(v, 1);
}

function setType(t: string) {
  if (type.value === t) return;
  type.value = t;
  search();
}

function search() {
  return runSearch(false);
}

/** 刷新：跳过缓存重新找，旧列表和选中的酒店先留着 */
async function refreshList() {
  if (refreshing.value || loading.value) return;
  refreshing.value = true;
  try {
    await runSearch(true);
  } finally {
    refreshing.value = false;
  }
}

async function runSearch(refresh: boolean) {
  if (!form.city.trim()) {
    uni.showToast({ title: "填一下城市", icon: "none" });
    return;
  }
  const mine = ++seq;
  const keepName = refresh ? selected.value?.name : undefined;
  if (!refresh) {
    loading.value = true;
    selected.value = null;
  }
  error.value = "";
  try {
    const r = await api.hotelCandidates({
      city: form.city.trim(),
      near: form.near.trim() || undefined,
      checkIn: form.checkIn,
      checkOut: form.checkOut,
      type: type.value || undefined,
      radius: 10000,
      refresh: refresh || undefined,
    });
    if (mine !== seq) return;
    result.value = r;
    const kept = keepName ? r.hotels.find((h) => h.name === keepName) : null;
    if (kept) selected.value = kept;
    else if (wide.value) selected.value = picks.value[0]?.hotel || shown.value[0] || null;
    if (refresh) return;
    await nextTick();
    mapRef.value?.fitTo([
      ...(r.anchorLng != null ? [[r.anchorLng, r.anchorLat!]] : []),
      ...mapPois.value.slice(0, 10).map((p) => [p.lng, p.lat]),
    ]);
  } catch (e) {
    // 刷新失败时留着旧结果，只弹提示
    if (mine === seq && refresh && result.value) showError(e);
    else if (mine === seq) error.value = e instanceof Error ? e.message : String(e);
  } finally {
    if (mine === seq) loading.value = false;
  }
}

function toggle(list: string[], v: string) {
  const i = list.indexOf(v);
  i >= 0 ? list.splice(i, 1) : list.push(v);
}

function letterOf(h: HotelOption) {
  return LETTERS[shown.value.indexOf(h)] || "";
}

function pickTitle(h: HotelOption) {
  return picks.value.find((p) => p.hotel.name === h.name)?.title || "";
}

function select(h: HotelOption) {
  selected.value = h;
  if (!wide.value) detailOpen.value = true;
  else if (h.lng != null) mapRef.value?.focus(h.id || h.name);
}

function onPickPoi(id: string) {
  const h = shown.value.find((x) => (x.id || x.name) === id);
  if (!h) return;
  selected.value = h;
  // #ifdef H5
  document.getElementById("ht-" + letterOf(h))?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  // #endif
}

function isCurrent(h: HotelOption) {
  return !!currentStay.value && sameHotel(currentStay.value.name, h.name);
}

/** 名字去掉括号和空格后互相包含，就当是同一家 */
function sameHotel(a: string, b: string) {
  const n = (s: string) => s.replace(/[（(].*?[）)]/g, "").replace(/\s|·/g, "");
  return n(a).includes(n(b)) || n(b).includes(n(a));
}

function quoteOf(h: HotelOption) {
  return quotes[h.name + "|" + form.checkIn + "|" + form.checkOut];
}

async function loadQuote(h: HotelOption, refresh = false) {
  const key = h.name + "|" + form.checkIn + "|" + form.checkOut;
  // 重新比价时先留着旧结果
  if (refresh && quotes[key]?.data) quotes[key].loading = true;
  else quotes[key] = { loading: true, data: null, error: "" };
  try {
    quotes[key].data = await api.hotelQuote(h.name, form.city, form.checkIn, form.checkOut, refresh);
    quotes[key].error = "";
  } catch (e) {
    quotes[key].error = e instanceof Error ? e.message : String(e);
  } finally {
    quotes[key].loading = false;
  }
}

/** 换进行程的酒店：价格取各平台里最低的（查过的话），没查过就用飞猪候选价 */
function pickOf(h: HotelOption): StayPick {
  const ok = (quoteOf(h)?.data?.hotels[0]?.quotes || []).filter((q) => q.status === "OK" && q.price != null);
  const best = [...ok].sort((a, b) => a.price! - b.price!)[0];
  const useBest = best && (h.price == null || best.price! < h.price);
  return {
    name: h.name,
    address: h.address,
    lng: h.lng,
    lat: h.lat,
    photo: h.photo,
    rating: h.rating,
    price: useBest ? best.price : h.price,
    platform: useBest ? best.platform : "飞猪",
    url: useBest ? best.url : h.url,
    quotes: ok.length ? ok.map((q) => ({ platform: q.platform, price: q.price!, url: q.url || undefined, basis: q.basis || undefined, matchedName: q.matchedName || undefined })) : null,
  };
}

async function openStayPreview(h: HotelOption) {
  const t = trip.value;
  if (!t) return;
  const pick = pickOf(h);
  const mine = ++previewSeq;
  staySheet.value = { pick, preview: null, error: "" };
  try {
    const p = await api.previewStay(t.id, stayId.value, pick);
    if (mine === previewSeq && staySheet.value) staySheet.value.preview = p;
  } catch (e) {
    if (mine === previewSeq && staySheet.value) staySheet.value.error = e instanceof Error ? e.message : String(e);
  }
}

function closeSheet() {
  if (saving.value) return;
  previewSeq++;
  staySheet.value = null;
}

async function confirmStay() {
  const s = staySheet.value;
  const t = trip.value;
  if (!s?.preview || !t) return;
  saving.value = true;
  try {
    const d = await api.applyStay(t.id, stayId.value, s.pick, s.preview.replacesId);
    const it = d.version.itinerary;
    t.days = it?.days || t.days;
    t.stays = it?.stays || t.stays;
    t.booked = it?.booked || [];
    stayId.value = s.preview.stay.id;
    staySheet.value = null;
    const to = d.version.version;
    uni.showModal({
      title: `已换好，存成第 ${to} 版`,
      content: `住宿：${s.pick.name}。还没有下单，请到平台预订付款，订好后在行程里标记"已预约"。`,
      confirmText: "看前后对比",
      cancelText: "留在这里",
      success: (r) => {
        if (r.confirm) uni.navigateTo({ url: `/pages/plan/updated?id=${t.id}&from=${to - 1}&to=${to}` });
      },
    });
  } catch (e) {
    showError(e);
    staySheet.value = null;
  } finally {
    saving.value = false;
  }
}

function delta(n: number, unit: string) {
  return n === 0 ? "" : (n > 0 ? "+" : "−") + Math.abs(n) + unit;
}

function tone(n: number) {
  return n > 0 ? "warn-t" : n < 0 ? "ok-t" : "";
}
</script>

<style scoped>
.hb { display: flex; flex-direction: column; gap: 12px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.panel-title { font-weight: 800; font-size: 15px; display: flex; align-items: center; gap: 4px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.strong { font-weight: 700; }
.err { color: var(--price); font-size: 13px; line-height: 1.5; }
.state { text-align: center; font-size: 13px; padding: 28px 0; }
.state.err { color: var(--price); }
.ok-t { color: var(--ok); }
.warn-t { color: var(--price); }

.trip-link { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.unlink { margin-left: auto; font-size: 12px; color: var(--muted); cursor: pointer; }
.sbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: stretch; }
.sfield { display: flex; flex-direction: column; gap: 2px; padding: 8px 12px; border-radius: 10px; background: var(--surface-2); min-width: 92px; cursor: pointer; box-sizing: border-box; }
.sfield.grow { flex: 1; min-width: 160px; }
.sk { font-size: 11px; color: var(--muted); }
.sv { font-size: 15px; font-weight: 700; white-space: nowrap; }
.sbtn { margin: 0; gap: 6px; padding: 0 22px; }

.toolbar { display: flex; flex-wrap: wrap; gap: 8px 18px; align-items: center; }
.sorts, .fgroup { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.flabel { font-size: 12px; color: var(--muted); }
.chip { font-size: 12px; padding: 5px 11px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; white-space: nowrap; }
.chip.on { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }

.current { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; padding: 10px 12px; border-radius: 12px; background: var(--accent-soft); }
.badge { font-size: 11px; padding: 1px 6px; border-radius: 4px; }
.badge.ok { background: var(--ok-soft); color: var(--ok); }

.grid { display: flex; flex-direction: column; gap: 12px; }
.list { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.lhead { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.refresh-line { white-space: nowrap; }
.link { color: var(--accent); cursor: pointer; }
.card { display: grid; grid-template-columns: 120px minmax(0, 1fr) auto; gap: 12px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 10px; cursor: pointer; scroll-margin: 12px; }
.card.on { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.img-wrap { position: relative; }
.img { width: 120px; height: 96px; border-radius: 10px; display: block; background: var(--surface-2); }
.img.blank { background: linear-gradient(135deg, #a5b4fc, #6366f1); display: flex; align-items: center; justify-content: center; }
.letter { position: absolute; left: 6px; top: 6px; width: 20px; height: 20px; border-radius: 6px; background: #e8590c; color: #fff; font-size: 11px; font-weight: 800; display: flex; align-items: center; justify-content: center; border: 2px solid #fff; }
.pick-badge { position: absolute; left: 0; right: 0; bottom: 0; font-size: 10px; font-weight: 700; color: #fff; background: rgba(37, 99, 235, 0.88); padding: 2px 6px; border-radius: 0 0 10px 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.body { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.name { font-weight: 800; font-size: 15px; }
.meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; font-size: 12px; }
.sep { color: var(--muted); }
.rating { color: var(--price); font-weight: 700; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); color: var(--muted); }
.tag.cur { background: var(--accent-soft); color: var(--accent); font-weight: 700; }
.tag.warn { background: #fff4ea; color: #c2560c; }
.price-col { display: flex; flex-direction: column; align-items: flex-end; justify-content: center; gap: 2px; white-space: nowrap; }
.price { font-size: 20px; font-weight: 800; color: var(--price); font-variant-numeric: tabular-nums; }

.mid { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.legend { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; font-size: 12px; }
.lg { display: inline-flex; align-items: center; gap: 6px; }
.lg-dot { display: inline-block; width: 12px; height: 12px; border-radius: 50%; }
.lg-dot.orange { background: #e8590c; border-radius: 3px; }
.lg-dot.purple { background: #7c3aed; }
.reason { display: flex; align-items: flex-start; gap: 6px; font-size: 13px; line-height: 1.5; }
.reason.bad { color: var(--price); }
.reason.note { color: var(--muted); }

.detail { gap: 14px; }
.d-head { display: flex; justify-content: space-between; gap: 8px; }
.d-title { font-weight: 800; font-size: 16px; }
.d-x { cursor: pointer; padding: 4px; margin-left: auto; }
.d-sec { display: flex; flex-direction: column; gap: 8px; border-top: 1px solid var(--line); padding-top: 12px; }
.d-sec .btn { margin: 0; }
.d-sub { font-weight: 800; font-size: 14px; }
.pf { display: grid; grid-template-columns: 40px auto minmax(0, 1fr) auto; gap: 8px; align-items: baseline; font-size: 13px; }
.pf-name { font-weight: 600; }
.pf-price { font-weight: 800; font-variant-numeric: tabular-nums; }
.pf-price.best { color: var(--price); }
.pf-basis { line-height: 1.4; }
.pf-go { color: var(--accent); cursor: pointer; white-space: nowrap; }
.flow { display: flex; flex-direction: column; gap: 8px; }
.flow-step { display: flex; align-items: flex-start; gap: 8px; }

.detail-wrap.sheet { position: fixed; inset: 0; z-index: 40; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; }
.detail-wrap.sheet .detail { width: 100%; max-height: 86vh; overflow-y: auto; border-radius: 18px 18px 0 0; padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px)); box-sizing: border-box; }

.mask { position: fixed; inset: 0; z-index: 50; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; justify-content: center; }
.sheet-box { width: 100%; max-width: 580px; max-height: 88vh; overflow-y: auto; background: var(--surface); border-radius: 18px 18px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom, 0px)); box-sizing: border-box; display: flex; flex-direction: column; gap: 12px; }
.sh-head { display: flex; align-items: center; gap: 8px; }
.sh-title { font-weight: 800; font-size: 16px; }
.preview-tag { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--price); }
.swap-box { display: flex; flex-direction: column; gap: 4px; padding: 10px; border-radius: 10px; background: var(--surface-2); font-size: 14px; }
.swap-box .old { color: var(--muted); text-decoration: line-through; }
.swap-box .new { font-weight: 800; }
.days { display: flex; flex-direction: column; gap: 4px; padding: 10px; border-radius: 10px; background: var(--surface-2); overflow-x: auto; }
.nowrap { white-space: nowrap; }
.drow { display: grid; grid-template-columns: 36px minmax(210px, 1.6fr) minmax(90px, 1fr) minmax(90px, 1fr); gap: 8px; font-size: 13px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.drow.head { color: var(--muted); font-size: 12px; }
.checks { display: flex; flex-direction: column; gap: 6px; }
.acts { display: grid; grid-template-columns: 2fr 1fr; gap: 8px; }
.acts .btn { margin: 0; }

@media (min-width: 1024px) {
  .grid { display: grid; grid-template-columns: minmax(0, 1fr) 420px; align-items: start; gap: 16px; }
  .mid { position: sticky; top: 20px; }
  .detail-wrap { grid-column: 2; }
  .mask { align-items: center; }
  .sheet-box { border-radius: 18px; }
}
@media (min-width: 1440px) {
  .grid { grid-template-columns: minmax(0, 1fr) 400px 340px; }
  .detail-wrap { grid-column: 3; position: sticky; top: 20px; }
}
@media (max-width: 480px) {
  .card { grid-template-columns: 92px minmax(0, 1fr); }
  .img { width: 92px; height: 80px; }
  .price-col { grid-column: 2; flex-direction: row; align-items: baseline; justify-content: flex-start; gap: 6px; }
}
</style>
