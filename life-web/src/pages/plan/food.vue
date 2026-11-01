<template>
  <Shell active="food" :show-recent="false">
  <view class="page">
    <view v-if="!tripId && !loading" class="empty">
      <Icon name="food" :size="34" color="var(--muted)" />
      <view>还没有行程，先按城市找找</view>
      <button class="btn small" @click="toCitySearch">按城市找美食</button>
    </view>
    <template v-else>
      <view class="top">
        <view v-if="wide" class="back" @click="goBack"><Icon name="back" :size="22" color="var(--ink)" /></view>
        <text class="title">{{ trip?.title || "行程" }} · 美食推荐</text>
        <view class="views">
          <text class="view-tab" @click="toDetail">行程总览</text>
          <text class="view-tab" @click="toMap">路线地图</text>
          <text class="view-tab on">美食推荐</text>
        </view>
      </view>

      <!-- 筛选 -->
      <view class="filters panel">
        <view class="frow">
          <text class="flabel">旅行日</text>
          <view class="chips">
            <text v-for="(d, i) in days" :key="d.date" class="chip" :class="{ on: i === dayIndex }" @click="pickDay(i)">D{{ i + 1 }} {{ shortDate(d.date) }}</text>
          </view>
        </view>
        <view class="frow">
          <text class="flabel">餐次</text>
          <view class="chips">
            <text v-for="m in MEALS" :key="m.value" class="chip" :class="{ on: meal === m.value }" @click="pickMeal(m.value)">{{ m.label }}</text>
          </view>
        </view>
        <view class="frow">
          <text class="flabel">类型</text>
          <scroll-view scroll-x class="chips-scroll">
            <view class="chips nowrap">
              <text v-for="k in KINDS" :key="k" class="chip" :class="{ on: kind === k }" @click="setFilter(() => (kind = k))">{{ k }}</text>
            </view>
          </scroll-view>
        </view>
        <view class="frow two">
          <view class="fgroup">
            <text class="flabel">人均</text>
            <view class="chips">
              <text v-for="b in BUDGETS" :key="b.label" class="chip" :class="{ on: budget === b.value }" @click="setFilter(() => (budget = b.value))">{{ b.label }}</text>
            </view>
          </view>
          <view class="fgroup">
            <text class="flabel">距离</text>
            <view class="chips">
              <text v-for="r in RADII" :key="r" class="chip" :class="{ on: radius === r }" @click="setFilter(() => (radius = r))">{{ r < 1000 ? r + " 米" : r / 1000 + " 公里" }}内</text>
            </view>
          </view>
        </view>
        <view class="frow">
          <text class="flabel">口味</text>
          <view class="chips">
            <text class="chip toggle" :class="{ on: noSpicy }" @click="setFilter(() => (noSpicy = !noSpicy))">不吃辣</text>
            <text class="chip toggle" :class="{ on: local }" @click="setFilter(() => (local = !local))">优先本地特色</text>
          </view>
        </view>
      </view>

      <view class="cols">
        <!-- 右：地图和三餐（手机上在列表前面） -->
        <view id="food-map" class="col side-col">
          <view class="panel">
            <RouteMap ref="mapRef" :stops="mapStops" :pois="mapPois" :active-id="currentMealItem?.id || null" :active-poi="activePoi" color="2563EB" :w="mapW" :h="mapH" @pick="onPickStop" @poi="onPickPoi" />
            <view class="legend">
              <text class="lg"><text class="lg-dot blue" />第 {{ dayIndex + 1 }} 天的安排</text>
              <text class="lg"><text class="lg-dot orange" />候选餐厅</text>
              <text class="muted small">地图、距离和路程来自高德</text>
            </view>
          </view>

          <view class="panel">
            <view class="panel-title">D{{ dayIndex + 1 }} 三餐安排</view>
            <view v-for="m in mealPlan" :key="m.value" class="meal" :class="{ on: meal === m.value }" @click="pickMeal(m.value)">
              <text class="meal-k">{{ m.label }}</text>
              <view class="meal-body">
                <template v-if="m.item">
                  <view class="meal-name">{{ stripMeal(m.item.name) }}</view>
                  <view class="muted small">
                    {{ m.item.start }}<text v-if="m.item.end"> – {{ m.item.end }}</text>
                    <text v-if="m.item.cost"> · 人均 ¥{{ m.item.cost }}</text>
                    <text v-if="m.item.locked" class="badge">已锁定</text>
                    <text v-if="booked.has(m.item.id)" class="badge ok">已预约</text>
                  </view>
                </template>
                <view v-else class="muted small">还没安排</view>
              </view>
              <text class="meal-act">{{ meal === m.value ? "正在找" : m.item ? "换一家" : "找一家" }}</text>
            </view>
            <view class="muted small">点某一顿，左边按这一顿前后的安排找顺路的餐厅；加入时有饭的那顿会换掉，没饭的插进空档。</view>
          </view>
        </view>

        <!-- 左：餐厅列表 -->
        <view class="col list-col">
          <view v-if="result" class="context">
            <template v-if="asked">
              <view class="ai-head"><Icon name="sparkle" :size="14" color="var(--accent)" /><text class="strong">美食助手</text><text class="clear-ai" @click="clearAsk">回到筛选</text></view>
              <view class="ai-q">“{{ asked }}”</view>
              <view class="ai-a">{{ result.summary }}</view>
              <view class="chips">
                <text v-for="c in askedChips" :key="c" class="chip mini">{{ c }}</text>
              </view>
            </template>
            <view v-else class="muted small">
              按{{ mealLabel(result.meal) }}前后的安排找：<text class="strong">{{ shortName(result.anchor) }}</text> 附近<text v-if="result.prevName && result.nextName">，在{{ shortName(result.prevName) }}和{{ shortName(result.nextName) }}之间</text>，按评分和顺路程度排。地图右下角的定位按钮可以看全天路线
            </view>
          </view>

          <view v-if="searching" class="muted small pad">{{ asked ? "正在理解你的需求，再到高德找餐厅…" : "正在高德上找…" }}</view>
          <view v-else-if="error" class="err">{{ error }}</view>
          <template v-else>
            <view
              v-for="(c, i) in visible"
              :id="'fd-' + i"
              :key="c.place.id || c.place.name"
              class="card"
              :class="{ on: c.place.id === activePoi }"
              @click="onSelectCard(c)"
            >
              <view class="card-img-wrap">
                <image v-if="c.place.photo && !broken[c.place.id]" class="card-img" :src="c.place.photo" mode="aspectFill" @error="broken[c.place.id] = true" />
                <view v-else class="card-img blank"><Icon name="food" :size="26" color="#fff" /></view>
                <text class="letter">{{ letterOf(c) }}</text>
              </view>
              <view class="card-body">
                <view class="card-name">{{ c.place.name }}</view>
                <view class="card-meta">
                  <text v-if="c.place.rating" class="rating">★ {{ c.place.rating }}</text>
                  <text v-else class="muted">暂无评分</text>
                  <text class="sep">·</text>
                  <text :class="{ muted: c.place.cost == null }">{{ c.place.cost != null ? `人均 ¥${c.place.cost}` : "人均未知" }}</text>
                  <text v-if="c.place.distance != null" class="sep">·</text>
                  <text v-if="c.place.distance != null">离{{ shortName(result?.anchor) }} {{ distanceText(c.place.distance) }}</text>
                </view>
                <view class="card-tags">
                  <text v-if="c.place.category" class="tag">{{ c.place.category }}</text>
                  <text v-for="t in c.place.tags || []" :key="t" class="tag">{{ t }}</text>
                  <text v-if="c.local" class="tag local">本地特色</text>
                  <text v-if="c.detourKm != null" class="tag" :class="c.detourKm < 0.3 ? 'good' : c.detourKm > 2 ? 'far' : ''">{{ c.detourKm < 0.3 ? "基本顺路" : `绕路约 ${c.detourKm} 公里` }}</text>
                  <text v-if="c.minutes" class="tag good">{{ c.mode === "WALK" ? "步行" : "打车" }}约 {{ c.minutes }} 分钟</text>
                  <text v-if="savedIds[c.place.name]" class="tag saved">已收藏</text>
                </view>
                <view class="card-acts" @click.stop>
                  <text v-if="isCurrent(c)" class="current">已是这天的{{ mealLabel(meal) }}</text>
                  <button v-else class="btn small add" :disabled="!!previewing" @click="openPreview(c)">{{ previewing === c.place.name ? "计算中…" : `加入${mealLabel(meal)}` }}</button>
                  <text class="act" :class="{ on: savedIds[c.place.name] }" @click="toggleSaved(c)"><Icon name="heart" :size="13" :color="savedIds[c.place.name] ? '#e11d48' : 'var(--muted)'" />{{ savedIds[c.place.name] ? "已收藏" : "收藏" }}</text>
                  <text class="act" @click="hide(c, 'VISITED')">去过了</text>
                  <text class="act" @click="hide(c, 'DISLIKE')">不喜欢</text>
                </view>
              </view>
            </view>
            <view v-if="result && !visible.length" class="muted small pad">没找到合适的餐厅，放宽人均或距离试试。</view>
            <view v-if="hiddenNow.length" class="muted small hidden-bar">
              这次隐藏了 {{ hiddenNow.length }} 家，以后推荐也会避开<text class="link" @click="undoHidden">撤销</text>
            </view>
          </template>
          <view class="foot">店名、评分、人均、照片和距离来自高德；"绕路"是按直线算的估计，加入前的预览按高德实际路线重新算。高德没有评价条数和是否可订，要看口碑和订座请到点评类平台。<text class="link" @click="toCitySearch">按城市找 ›</text></view>
        </view>
      </view>

      <!-- AI 美食助手 -->
      <view class="ask-bar">
        <view class="ask-inner">
          <Icon name="sparkle" :size="16" color="var(--accent)" />
          <input id="food-ask" v-model="askText" class="ask-input" :placeholder="`说说想吃什么，比如：我不吃辣，想在${firstSpot || '景点'}附近吃午饭，人均 50 元以内，尽量不绕路`" confirm-type="send" @confirm="ask" />
          <button class="btn small" :disabled="searching || !askText.trim()" @click="ask">{{ searching && asked ? "找中…" : "问助手" }}</button>
        </view>
        <view class="ask-note">助手只把你的话拆成筛选条件，餐厅和路程都从高德查，不会编造店名和价格</view>
      </view>
    </template>

    <!-- 加入预览 -->
    <view v-if="sheet" class="mask" @click="closeSheet">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">加入第 {{ dayIndex + 1 }} 天{{ mealLabel(sheet.meal) }}</text>
          <text class="preview-tag">预览，还没保存</text>
          <view class="sheet-x" @click="closeSheet"><Icon name="close" :size="16" color="var(--muted)" /></view>
        </view>
        <view class="swap">
          <view v-if="sheet.preview?.replacesName" class="swap-old">{{ stripMeal(sheet.preview.replacesName) }}</view>
          <Icon v-if="sheet.preview?.replacesName" name="right" :size="14" color="var(--muted)" />
          <view class="swap-new">{{ sheet.place.name }}<text v-if="sheet.place.cost" class="muted"> · 人均 ¥{{ sheet.place.cost }}</text></view>
        </view>
        <view v-if="sheet.error" class="err">{{ sheet.error }}</view>
        <view v-else-if="!sheet.preview" class="muted small pad">正在用高德算路线…</view>
        <template v-else>
          <view class="slot">
            <Icon name="calendar" :size="14" color="var(--accent)" />
            <text>{{ sheet.preview.start }} – {{ sheet.preview.end }}</text>
            <text class="muted">{{ sheet.preview.replacesId ? "沿用原来那顿的时间" : "插进空档" }}<text v-if="sheet.preview.prevName">，在「{{ stripMeal(sheet.preview.prevName) }}」之后</text><text v-if="sheet.preview.nextName">、「{{ stripMeal(sheet.preview.nextName) }}」之前</text></text>
          </view>
          <view class="cmp">
            <view v-for="row in sheetRows" :key="row.label" class="cmp-row">
              <text class="cmp-k">{{ row.label }}</text>
              <text class="cmp-b">{{ row.before }}</text>
              <text class="cmp-arrow">→</text>
              <text class="cmp-a">{{ row.after }}</text>
              <text class="cmp-d" :class="row.tone">{{ row.delta }}</text>
            </view>
          </view>
          <view class="checks">
            <view class="check" :class="budgetCheck.tone"><Icon :name="budgetCheck.tone === 'bad' ? 'bell' : 'checkCircle'" :size="14" :color="budgetCheck.tone === 'bad' ? 'var(--price)' : 'var(--ok)'" /><text>{{ budgetCheck.text }}</text></view>
            <view v-if="!sheet.preview.impacts.length" class="check good"><Icon name="checkCircle" :size="14" color="var(--ok)" /><text>不影响后面的安排，锁定和已预约的都不动</text></view>
            <view v-for="n in sheet.preview.notes" :key="n" class="check bad"><Icon name="bell" :size="14" color="var(--price)" /><text>{{ n }}</text></view>
            <view v-for="im in sheet.preview.impacts" :key="im.id" class="check bad">
              <Icon name="bell" :size="14" color="var(--price)" />
              <text>按高德路程，到「{{ stripMeal(im.name) }}」会比原来晚约 {{ im.minutes }} 分钟<text v-if="im.fixed" class="strong">（这一项你锁定或已预约了）</text></text>
            </view>
          </view>
          <view class="acts">
            <button class="btn" :disabled="saving" @click="confirmAdd">{{ saving ? "保存中…" : "确认加入并保存" }}</button>
            <button class="btn ghost" :disabled="saving" @click="closeSheet">不用了</button>
          </view>
          <view class="muted small">保存后存成新版本，旧版本可以在行程页回看。</view>
        </template>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import RouteMap from "../../components/RouteMap.vue";
import Icon from "../../components/Icon.vue";
import { computed, nextTick, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { FoodCandidate, FoodResult, ItineraryItem, Meal, MealPreview, MemoryItem, PlacePick, TripDetail } from "../../api/types";
import { shortDate } from "../../utils/format";
import { compareTotals, distanceText, type MapStop } from "../../utils/place";
import { useWide } from "../../utils/screen";

/**
 * 行程里的美食推荐：按某天某一顿前后的安排找顺路的餐厅（高德），地图上同时画当天路线和候选餐厅。
 * 加入行程先出预览（时间段、路程变化、吃饭花费、会不会让后面的安排来不及），确认后才存新版本。
 * 底部的美食助手只把一句话拆成筛选条件，店名、人均、距离都来自高德。参数 ?id=行程&day=第几天&meal=LUNCH
 */
const MEALS: { value: Meal; label: string; from: string; to: string }[] = [
  { value: "BREAKFAST", label: "早餐", from: "06:30", to: "10:00" },
  { value: "LUNCH", label: "午餐", from: "11:00", to: "14:30" },
  { value: "DINNER", label: "晚餐", from: "17:00", to: "21:00" },
];
const KINDS = ["全部", "小吃", "面馆", "火锅", "烧烤", "清真", "西餐", "咖啡甜品"];
const BUDGETS: { label: string; value: number | null }[] = [
  { label: "不限", value: null },
  { label: "¥50 内", value: 50 },
  { label: "¥100 内", value: 100 },
  { label: "¥200 内", value: 200 },
];
const RADII = [1000, 2000, 3000, 5000];
const LETTERS = "ABCDEFGHIJKLMNOPQRST";
/** 记忆 key 前缀，和后端一致：去过的、不感兴趣的规划和推荐时会避开 */
const KEY_VISITED = "去过:";
const KEY_DISLIKE = "不感兴趣:";
const KEY_SAVED = "收藏:";

const wide = useWide();
const tripId = ref(0);
const detail = ref<TripDetail | null>(null);
const loading = ref(false);
const dayIndex = ref(0);
const meal = ref<Meal>("LUNCH");
const kind = ref("全部");
const budget = ref<number | null>(null);
const radius = ref(2000);
const noSpicy = ref(false);
const local = ref(false);

const result = ref<FoodResult | null>(null);
const searching = ref(false);
const error = ref("");
const askText = ref("");
/** 助手模式：用户问的那句话；为空是筛选模式 */
const asked = ref("");
const activePoi = ref<string | null>(null);
const broken = reactive<Record<string, boolean>>({});
const mapRef = ref<{ focus: (id: string) => void; fitTo: (points: number[][]) => void } | null>(null);
/** 收藏了的店名 → 记忆 id */
const savedIds = reactive<Record<string, number | true>>({});
/** 这次隐藏的店（不喜欢、去过了），可以撤销 */
const hiddenNow = ref<{ name: string; key: string }[]>([]);

const sheet = ref<{ meal: Meal; place: PlacePick; preview: MealPreview | null; error: string } | null>(null);
const previewing = ref<string | null>(null);
const saving = ref(false);
let searchSeq = 0;

const trip = computed(() => detail.value?.trip);
const days = computed(() => detail.value?.version.itinerary?.days || []);
const day = computed(() => days.value[dayIndex.value]);
const booked = computed(() => new Set(detail.value?.version.itinerary?.booked || []));
const mapW = computed(() => (wide.value ? 560 : 600));
const mapH = computed(() => (wide.value ? 420 : 400));
const firstSpot = computed(() => shortName(day.value?.items.find((i) => i.type === "SPOT" && i.lng != null)?.name));

const mealPlan = computed(() => MEALS.map((m) => ({ ...m, item: day.value?.items.find((i) => holds(m.value, i)) })));
const currentMealItem = computed(() => mealPlan.value.find((m) => m.value === meal.value)?.item);
const visible = computed(() => (result.value?.places || []).filter((c) => !hiddenNow.value.some((h) => h.name === c.place.name)));
const mapStops = computed<MapStop[]>(() =>
  (day.value?.items || []).filter((i) => i.lng != null && i.lat != null).map((i) => ({ id: i.id, name: stripMeal(i.name), lng: i.lng!, lat: i.lat! })),
);
/** 地图上只标前 12 家，多了看不清 */
const mapPois = computed(() =>
  visible.value
    .slice(0, 12)
    .filter((c) => c.place.lng != null && c.place.lat != null)
    .map((c) => ({ id: c.place.id, name: c.place.name, lng: c.place.lng!, lat: c.place.lat!, label: letterOf(c) })),
);
const askedChips = computed(() => {
  const q = result.value?.query;
  if (!q) return [];
  return [
    mealLabel(q.meal),
    q.near ? `${q.near}附近` : "",
    q.keyword || "",
    q.maxCost ? `人均 ¥${q.maxCost} 内` : "",
    q.radius ? `${q.radius >= 1000 ? q.radius / 1000 + " 公里" : q.radius + " 米"}内` : "",
    q.noSpicy ? "不吃辣" : "",
    q.local ? "本地特色" : "",
  ].filter(Boolean);
});
const sheetRows = computed(() => {
  const p = sheet.value?.preview;
  if (!p) return [];
  return [
    ...compareTotals(p.preview.beforeTotals, p.preview.afterTotals).filter((r) => r.label !== "结束时间"),
    { label: "吃饭人均", before: `¥${p.foodBefore}`, after: `¥${p.foodAfter}`, delta: p.foodAfter === p.foodBefore ? "不变" : (p.foodAfter < p.foodBefore ? "少 ¥" : "多 ¥") + Math.abs(p.foodAfter - p.foodBefore), tone: p.foodAfter < p.foodBefore ? "good" : p.foodAfter > p.foodBefore ? "bad" : "" },
  ];
});
/** 预算检查：这家的人均和页面上选的人均上限比 */
const budgetCheck = computed(() => {
  const cost = sheet.value?.place.cost;
  const limit = budget.value ?? result.value?.query.maxCost ?? null;
  if (cost == null) return { tone: "bad", text: "高德没有这家的人均，花费没法核对" };
  if (limit == null) return { tone: "good", text: `人均 ¥${cost}（没设人均上限）` };
  return cost <= limit ? { tone: "good", text: `人均 ¥${cost}，在 ¥${limit} 的预算内` } : { tone: "bad", text: `人均 ¥${cost}，超出 ¥${limit} 的预算 ¥${cost - limit}` };
});

onLoad(async (query) => {
  tripId.value = Number(query?.id) || 0;
  dayIndex.value = Math.max(0, (Number(query?.day) || 1) - 1);
  if (query?.meal && MEALS.some((m) => m.value === query.meal)) meal.value = query.meal as Meal;
  loading.value = true;
  try {
    if (!tripId.value) tripId.value = await latestTrip();
    if (!tripId.value) return;
    detail.value = await api.trip(tripId.value);
    if (!detail.value.version.itinerary?.geo) detail.value = await api.tripGeo(tripId.value);
    dayIndex.value = Math.min(dayIndex.value, Math.max(days.value.length - 1, 0));
    loadSaved();
    await search();
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
});

/** 没带行程：用最近一个还没结束、有逐项安排的 */
async function latestTrip() {
  const today = new Date().toISOString().slice(0, 10);
  const trips = await api.trips();
  return (trips.find((t) => !t.endDate || t.endDate >= today) || trips[0])?.id || 0;
}

function holds(m: Meal, i: ItineraryItem) {
  const w = MEALS.find((x) => x.value === m)!;
  const start = (i.start || "").padStart(5, "0");
  return i.type === "FOOD" && !!i.start && start >= w.from && start < w.to;
}

function mealLabel(m: Meal) {
  return MEALS.find((x) => x.value === m)?.label || "";
}

/** 去掉 AI 写在店名前面的"午餐："这类前缀 */
function stripMeal(name?: string | null) {
  return (name || "").replace(/^(早餐|午餐|晚餐|早饭|午饭|晚饭)[：:]\s*/, "");
}

/** 地名去掉括号里的补充，标签里短一点 */
function shortName(name?: string | null) {
  return stripMeal(name).replace(/[（(].*?[）)]/g, "");
}

/** 这家已经是当前这一顿 */
function isCurrent(c: FoodCandidate) {
  const now = stripMeal(currentMealItem.value?.name);
  return !!now && (now === c.place.name || (currentMealItem.value?.poiId != null && currentMealItem.value.poiId === c.place.id));
}

function letterOf(c: FoodCandidate) {
  return LETTERS[visible.value.indexOf(c)] || "";
}

async function search() {
  if (!tripId.value || !day.value) return;
  const seq = ++searchSeq;
  searching.value = true;
  error.value = "";
  activePoi.value = null;
  try {
    const r = asked.value
      ? await api.askFood(tripId.value, dayIndex.value + 1, meal.value, asked.value)
      : await api.tripFood(tripId.value, dayIndex.value + 1, {
          meal: meal.value,
          keyword: kind.value === "全部" ? undefined : kind.value === "咖啡甜品" ? "咖啡" : kind.value,
          maxCost: budget.value ?? undefined,
          radius: radius.value,
          noSpicy: noSpicy.value || undefined,
          local: local.value || undefined,
        });
    if (seq !== searchSeq) return;
    result.value = r;
    // 助手可能换了餐次（比如在晚餐页问午饭），跟着切过去
    if (asked.value) meal.value = r.meal;
    // 地图缩放到这一顿附近：找的中心和前几家候选
    await nextTick();
    mapRef.value?.fitTo([[r.anchorLng, r.anchorLat], ...mapPois.value.slice(0, 8).map((p) => [p.lng, p.lat])]);
  } catch (e) {
    if (seq !== searchSeq) return;
    result.value = null;
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    if (seq === searchSeq) searching.value = false;
  }
}

function pickDay(i: number) {
  if (i === dayIndex.value) return;
  dayIndex.value = i;
  asked.value = "";
  search();
}

function pickMeal(m: Meal) {
  if (m === meal.value && !asked.value) return;
  meal.value = m;
  asked.value = "";
  search();
}

/** 改筛选条件就退出助手模式，重新查 */
function setFilter(change: () => void) {
  change();
  asked.value = "";
  search();
}

function ask() {
  const text = askText.value.trim();
  if (!text || searching.value) return;
  asked.value = text;
  search();
}

function clearAsk() {
  asked.value = "";
  search();
}

/** 点地图上的餐厅字母：高亮并滚到那张卡片 */
function onPickPoi(id: string) {
  activePoi.value = id;
  const i = visible.value.findIndex((c) => c.place.id === id);
  // #ifdef H5
  document.getElementById("fd-" + i)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  // #endif
}

/** 点地图上的行程编号：如果是某一顿饭，切到找那一顿 */
function onPickStop(id: string) {
  const m = mealPlan.value.find((x) => x.item?.id === id);
  if (m) pickMeal(m.value);
}

/** 点卡片：地图以这家为中心放大；手机上滚回地图 */
function onSelectCard(c: FoodCandidate) {
  activePoi.value = c.place.id;
  if (c.place.lng == null) return;
  mapRef.value?.focus(c.place.id);
  if (!wide.value) uni.pageScrollTo({ selector: "#food-map", duration: 200 });
}

function pickOf(c: FoodCandidate): PlacePick {
  const p = c.place;
  return { name: p.name, poiId: p.id, address: p.address, lng: p.lng, lat: p.lat, photo: p.photo, rating: p.rating, cost: p.cost, type: "FOOD" };
}

/** 加入行程第一步：后端找时间段、插进去、按高德重算路线、检查影响，返回预览 */
async function openPreview(c: FoodCandidate) {
  const place = pickOf(c);
  const m = meal.value;
  previewing.value = c.place.name;
  sheet.value = { meal: m, place, preview: null, error: "" };
  try {
    const p = await api.previewMeal(tripId.value, dayIndex.value + 1, m, place);
    if (sheet.value?.place.name === place.name) sheet.value.preview = p;
  } catch (e) {
    if (sheet.value?.place.name === place.name) sheet.value.error = e instanceof Error ? e.message : String(e);
  } finally {
    previewing.value = null;
  }
}

function closeSheet() {
  if (!saving.value) sheet.value = null;
}

async function confirmAdd() {
  const s = sheet.value;
  if (!s?.preview) return;
  saving.value = true;
  const from = detail.value?.version.version;
  try {
    detail.value = await api.saveMeal(tripId.value, dayIndex.value + 1, s.meal, s.place, s.preview.replacesId);
    sheet.value = null;
    const to = detail.value.version.version;
    uni.showModal({
      title: `已加入，存成第 ${to} 版`,
      content: `第 ${dayIndex.value + 1} 天${mealLabel(s.meal)}：${s.place.name}`,
      confirmText: "看前后对比",
      cancelText: "继续找",
      success: (r) => {
        if (r.confirm) uni.navigateTo({ url: `/pages/plan/updated?id=${tripId.value}&from=${from}&to=${to}` });
      },
    });
    search();
  } catch (e) {
    showError(e);
    // 行程刚被改过：重新预览
    if (sheet.value) sheet.value.preview = null;
    saving.value = false;
    openPreviewAgain(s.place);
    return;
  }
  saving.value = false;
}

async function openPreviewAgain(place: PlacePick) {
  try {
    const p = await api.previewMeal(tripId.value, dayIndex.value + 1, sheet.value?.meal || meal.value, place);
    if (sheet.value) sheet.value.preview = p;
  } catch (e) {
    if (sheet.value) sheet.value.error = e instanceof Error ? e.message : String(e);
  }
}

/** 读记忆里收藏过的餐厅 */
async function loadSaved() {
  try {
    const list = await api.memories();
    list.filter((m: MemoryItem) => m.memKey?.startsWith(KEY_SAVED)).forEach((m) => (savedIds[m.memKey.slice(KEY_SAVED.length)] = m.id));
  } catch {
    // 收藏状态显示不出来不影响找餐厅
  }
}

async function toggleSaved(c: FoodCandidate) {
  const name = c.place.name;
  try {
    const id = savedIds[name];
    if (id) {
      if (id === true) await reloadSavedId(name);
      const real = savedIds[name];
      if (typeof real === "number") await api.deleteMemory(real);
      delete savedIds[name];
    } else {
      await api.saveMemory({ category: "PREFERENCE", key: KEY_SAVED + name, content: `收藏了餐厅「${name}」（${trip.value?.destination || ""}），想去吃` });
      savedIds[name] = true;
    }
  } catch (e) {
    showError(e);
  }
}

/** 刚收藏的还不知道记忆 id，取消时查一下 */
async function reloadSavedId(name: string) {
  const m = (await api.memories()).find((x) => x.memKey === KEY_SAVED + name);
  if (m) savedIds[name] = m.id;
}

/** 去过了 / 不喜欢：记进偏好记忆，以后推荐和规划都避开，这次先从列表里拿掉 */
async function hide(c: FoodCandidate, why: "VISITED" | "DISLIKE") {
  const name = c.place.name;
  const key = (why === "VISITED" ? KEY_VISITED : KEY_DISLIKE) + name;
  try {
    await api.saveMemory({ category: "PREFERENCE", key, content: why === "VISITED" ? `去过${name}，不用再推荐` : `对${name}不感兴趣，不要推荐` });
    hiddenNow.value = [...hiddenNow.value, { name, key }];
  } catch (e) {
    showError(e);
  }
}

async function undoHidden() {
  try {
    const keys = new Set(hiddenNow.value.map((h) => h.key));
    const list = await api.memories();
    await Promise.all(list.filter((m) => keys.has(m.memKey)).map((m) => api.deleteMemory(m.id)));
    hiddenNow.value = [];
  } catch (e) {
    showError(e);
  }
}

function toDetail() {
  uni.redirectTo({ url: `/pages/trips/detail?id=${tripId.value}` });
}

function toMap() {
  uni.redirectTo({ url: `/pages/plan/map?id=${tripId.value}&day=${dayIndex.value + 1}` });
}

function toCitySearch() {
  uni.navigateTo({ url: "/pages/search/food" + (trip.value?.destination ? `?city=${encodeURIComponent(trip.value.destination)}` : "") });
}

function goBack() {
  if (getCurrentPages().length > 1) uni.navigateBack();
  else toDetail();
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 0; box-sizing: border-box; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 80px 0; }
.empty .btn { margin: 0; }
.top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.back { cursor: pointer; padding: 4px; }
.title { font-size: 18px; font-weight: 800; flex: 1; min-width: 0; }
.views { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; }
.view-tab { font-size: 13px; padding: 6px 12px; border-radius: 8px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.view-tab.on { background: var(--accent); color: #fff; font-weight: 600; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.panel-title { font-weight: 800; font-size: 15px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.pad { padding: 14px 0; text-align: center; }
.strong { font-weight: 700; }
.link { color: var(--accent); cursor: pointer; margin-left: 6px; }
.err { font-size: 13px; color: var(--price); padding: 10px 12px; border-radius: 10px; background: var(--surface-2); line-height: 1.5; }

.filters { gap: 8px; margin-bottom: 12px; }
.frow { display: flex; align-items: center; gap: 10px; min-width: 0; }
.frow.two { flex-wrap: wrap; gap: 8px 24px; }
.fgroup { display: flex; align-items: center; gap: 10px; min-width: 0; }
.flabel { font-size: 12px; color: var(--muted); width: 40px; flex-shrink: 0; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chips.nowrap { flex-wrap: nowrap; }
.chips-scroll { flex: 1; min-width: 0; white-space: nowrap; }
.chip { flex-shrink: 0; white-space: nowrap; font-size: 13px; padding: 4px 12px; border-radius: 999px; background: var(--surface-2); color: var(--ink); cursor: pointer; border: 1px solid transparent; }
.chip.on { background: var(--accent); color: #fff; font-weight: 600; }
.chip.toggle.on { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
.chip.mini { font-size: 11px; padding: 2px 8px; cursor: default; background: var(--accent-soft); color: var(--accent); }

.cols { display: flex; flex-direction: column; gap: 12px; }
.side-col { display: flex; flex-direction: column; gap: 12px; }
.list-col { display: flex; flex-direction: column; gap: 10px; }
.legend { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; font-size: 12px; }
.lg { display: inline-flex; align-items: center; gap: 6px; }
.lg-dot { display: inline-block; width: 12px; height: 12px; border-radius: 50%; }
.lg-dot.blue { background: #2563eb; }
.lg-dot.orange { background: #e8590c; border-radius: 3px; }

.meal { display: grid; grid-template-columns: 40px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 10px; border-radius: 10px; background: var(--surface-2); cursor: pointer; border: 2px solid transparent; }
.meal.on { border-color: var(--accent); background: var(--accent-soft); }
.meal-k { font-weight: 800; font-size: 13px; }
.meal-name { font-weight: 700; font-size: 14px; }
.meal-act { font-size: 12px; color: var(--accent); white-space: nowrap; }
.badge { font-size: 11px; padding: 0 5px; border-radius: 4px; background: var(--accent-soft); color: var(--accent); margin-left: 6px; }
.badge.ok { background: var(--ok-soft); color: var(--ok); }

.context { background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 12px 14px; display: flex; flex-direction: column; gap: 6px; line-height: 1.5; }
.ai-head { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.clear-ai { margin-left: auto; font-size: 12px; color: var(--accent); cursor: pointer; }
.ai-q { font-size: 13px; color: var(--muted); }
.ai-a { font-size: 13px; }

.card { display: flex; gap: 12px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 10px; cursor: pointer; scroll-margin: 12px; }
.card.on { outline: 2px solid #e8590c; }
.card-img-wrap { position: relative; flex-shrink: 0; }
.card-img { width: 104px; height: 104px; border-radius: 10px; background: var(--surface-2); display: block; }
.card-img.blank { background: linear-gradient(135deg, #fdba74, #f97316); display: flex; align-items: center; justify-content: center; }
.letter { position: absolute; left: 6px; top: 6px; width: 20px; height: 20px; border-radius: 6px; background: #e8590c; color: #fff; font-size: 11px; font-weight: 800; display: flex; align-items: center; justify-content: center; border: 2px solid #fff; }
.card-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.card-name { font-weight: 800; font-size: 15px; }
.card-meta { font-size: 12px; display: flex; flex-wrap: wrap; gap: 4px; font-variant-numeric: tabular-nums; }
.sep { color: var(--muted); }
.rating { color: var(--price); font-weight: 700; }
.card-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.tag { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); color: var(--muted); white-space: nowrap; }
.tag.local { background: #fff4ea; color: #c2560c; }
.tag.good { background: var(--ok-soft); color: var(--ok); }
.tag.far { background: #fff4ea; color: #c2560c; }
.tag.saved { background: #fde8ec; color: #e11d48; }
.card-acts { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-top: 2px; }
.card-acts .add { margin: 0; }
.current { font-size: 12px; font-weight: 700; color: var(--ok); background: var(--ok-soft); padding: 5px 10px; border-radius: 8px; }
.act { display: inline-flex; align-items: center; gap: 3px; font-size: 12px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.act.on { color: #e11d48; }
.hidden-bar { text-align: center; }
.foot { font-size: 12px; color: var(--muted); line-height: 1.6; padding: 4px 2px 12px; }

.ask-bar { position: sticky; bottom: 0; z-index: 5; margin: 8px -16px 0; padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--surface); border-top: 1px solid var(--line); box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.05); }
.ask-inner { display: flex; align-items: center; gap: 8px; background: var(--surface-2); border-radius: 12px; padding: 6px 6px 6px 12px; }
.ask-input { flex: 1; min-width: 0; font-size: 14px; color: var(--ink); }
.ask-inner .btn { margin: 0; flex-shrink: 0; }
.ask-note { font-size: 11px; color: var(--muted); margin-top: 6px; text-align: center; }

.mask { position: fixed; inset: 0; z-index: 50; background: rgba(15, 23, 42, 0.45); display: flex; align-items: flex-end; justify-content: center; }
.sheet { width: 100%; max-width: 560px; max-height: 88vh; overflow-y: auto; background: var(--surface); border-radius: 18px 18px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom, 0px)); box-sizing: border-box; display: flex; flex-direction: column; gap: 12px; }
.sheet-head { display: flex; align-items: center; gap: 8px; }
.sheet-title { font-weight: 800; font-size: 16px; }
.sheet-x { margin-left: auto; cursor: pointer; padding: 4px; }
.preview-tag { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--price); white-space: nowrap; }
.swap { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 14px; }
.swap-old { color: var(--muted); text-decoration: line-through; }
.swap-new { font-weight: 800; }
.slot { display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; font-size: 13px; font-variant-numeric: tabular-nums; }
.cmp { display: flex; flex-direction: column; gap: 6px; padding: 10px; border-radius: 10px; background: var(--surface-2); }
.cmp-row { display: grid; grid-template-columns: 60px auto 12px auto 1fr; gap: 6px; align-items: baseline; font-size: 13px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.cmp-row .cmp-d { text-align: right; }
.cmp-k { color: var(--muted); font-size: 12px; }
.cmp-b { color: var(--muted); }
.cmp-arrow { color: var(--muted); }
.cmp-a { font-weight: 700; }
.cmp-d { font-size: 12px; font-weight: 700; }
.cmp-d.good { color: var(--ok); }
.cmp-d.bad { color: var(--price); }
.checks { display: flex; flex-direction: column; gap: 6px; }
.check { display: flex; align-items: flex-start; gap: 6px; font-size: 13px; line-height: 1.5; }
.check.bad { color: var(--price); }
.acts { display: grid; grid-template-columns: 2fr 1fr; gap: 8px; }
.acts .btn { margin: 0; }

@media (min-width: 1024px) {
  .page { max-width: 1400px; padding: 20px 24px 0; }
  .title { font-size: 22px; }
  .cols { display: grid; grid-template-columns: minmax(0, 1fr) 560px; align-items: start; gap: 16px; }
  .side-col { order: 2; position: sticky; top: 20px; }
  .list-col { order: 1; }
  .ask-bar { margin: 8px -24px 0; padding-left: 24px; padding-right: 24px; }
  .ask-inner { max-width: 900px; margin: 0 auto; }
  .mask { align-items: center; }
  .sheet { border-radius: 18px; }
}
@media (max-width: 480px) {
  .card-img { width: 84px; height: 84px; }
  .card-acts { gap: 10px; }
}
@media (prefers-reduced-motion: reduce) {
  .card, .meal { transition: none; }
}
</style>
