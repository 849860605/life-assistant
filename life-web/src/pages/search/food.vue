<template>
  <Shell active="food">
  <view class="page">
    <view class="head">
      <text class="title">{{ nearMe ? "你附近的美食" : `${city} · ${nearName ? nearName + "附近" : ""}美食` }}</text>
      <text class="tag">高德</text>
    </view>
    <scroll-view scroll-x class="cats">
      <view class="cats-inner">
        <text v-for="c in CATS" :key="c" class="cat" :class="{ on: cat === c }" @click="pick(c)">{{ c }}</text>
      </view>
    </scroll-view>
    <view class="filters">
      <view class="filter mine" :class="{ on: nearMe }" @click="useMyLocation"><Icon name="nav" :size="12" :color="nearMe ? '#fff' : 'var(--accent)'" />{{ locating ? "定位中…" : "我附近" }}</view>
      <view class="near">
        <Icon name="pin" :size="14" color="var(--muted)" />
        <input id="food-near" v-model="nearInput" class="near-input" placeholder="附近：输入景点、商圈或酒店" @confirm="applyNear" />
        <text v-if="nearName" class="clear" @click="clearNear">清除</text>
      </view>
      <view class="filter" :class="{ on: sort === 'rating' }" @click="sort = 'rating'">评分优先</view>
      <view class="filter" :class="{ on: sort === 'price' }" @click="sort = 'price'">人均从低到高</view>
      <view class="filter map" @click="dianping"><Icon name="search" :size="12" color="var(--accent)" />查大众点评/携程口碑</view>
    </view>

    <view v-if="reviews.loading" class="loading">正在查大众点评和携程，约 10–30 秒…</view>
    <view v-else-if="reviews.data" class="reviews">
      <RestaurantCard :data="reviews.data" />
    </view>
    <view v-else-if="reviews.error" class="error">{{ reviews.error }}</view>

    <view v-if="loading" class="loading">加载中…</view>
    <view v-else class="list">
      <view v-for="f in list" :key="f.id || f.name" class="item">
        <image v-if="f.photo" class="img" :src="f.photo" mode="aspectFill" />
        <view v-else class="img blank"><Icon name="food" :size="28" color="#fff" /></view>
        <view class="info">
          <view class="name-row">
            <view class="name">{{ f.name }}</view>
            <FavButton :item="{ kind: 'FOOD', refKey: f.id || f.name, title: f.name, subtitle: f.cost ? `人均 ¥${f.cost}` : f.area, city, url: amapUrl(f, city), photo: f.photo }" />
          </view>
          <view class="meta">
            <text v-if="f.rating" class="rating">★ {{ f.rating }}</text>
            <text v-if="f.rating" class="muted"> 高德评分</text>
            <text v-else class="muted">暂无评分</text>
          </view>
          <view class="meta muted">
            <text v-if="f.cost">¥{{ f.cost }}/人</text>
            <text v-if="f.cost && (f.distance != null || f.area)"> · </text>
            <text v-if="f.distance != null">{{ nearMe ? "离你" : "距" + (nearName || "市中心") }} {{ distanceText(f.distance) }}</text>
            <text v-else-if="f.area">{{ f.area }}</text>
          </view>
          <view class="tags"><text v-if="f.category" class="tag-chip">{{ f.category }}</text></view>
          <view class="amap-links">
            <text class="amap" @click="openUrl(amapUrl(f))">高德地图</text>
            <text class="amap" @click="openUrl(amapNavUrl(f))">导航</text>
          </view>
        </view>
        <view v-if="trip" class="side">
          <button class="btn small add" :class="{ added: added[f.name] }" :disabled="added[f.name]" @click="add(f)">{{ added[f.name] ? "已加入" : "加入行程" }}</button>
        </view>
      </view>
      <view v-if="!list.length" class="empty">{{ error || "这个分类暂时没找到餐厅" }}</view>
    </view>
    <view class="foot">餐厅、评分、人均和照片来自高德；口碑和点评链接点上面"查大众点评/携程口碑"。</view>
    <view class="ask" @click="ask">想找别的？<text class="link">跟助手说说想吃什么</text></view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import Icon from "../../components/Icon.vue";
import FavButton from "../../components/FavButton.vue";
import RestaurantCard from "../../components/cards/RestaurantCard.vue";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { Itinerary, Place } from "../../api/types";
import { openUrl } from "../../utils/format";
import { amapNavUrl, amapUrl, currentCity, distanceText } from "../../utils/place";
import { locate, locationAllowed } from "../../utils/location";
import { shortDate } from "../../utils/format";

/** 美食推荐（原型⑦）：高德的城市或某地附近餐厅，可按分类筛选、查点评口碑、加入行程。 */
const CATS = ["全部", "火锅", "小吃", "本地菜", "烧烤", "咖啡", "甜品"];
const cat = ref("全部");
const sort = ref<"rating" | "price">("rating");
const city = ref(currentCity());
const nearInput = ref("");
const nearName = ref("");
const center = ref<{ lng: number; lat: number } | null>(null);
/** 是不是按"我的位置"找 */
const nearMe = ref(false);
const locating = ref(false);
const places = ref<Place[]>([]);
const loading = ref(false);
const error = ref("");
const added = reactive<Record<string, boolean>>({});
const reviews = reactive<{ loading: boolean; data: any; error: string }>({ loading: false, data: null, error: "" });
/** 可以加入的行程：最近一个有逐项安排的 */
const trip = ref<{ id: number; days: Itinerary["days"] } | null>(null);

const list = computed(() => {
  const l = [...places.value];
  if (sort.value === "price") l.sort((a, b) => (a.cost ?? Infinity) - (b.cost ?? Infinity));
  else l.sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0));
  return l;
});

onLoad(async (query) => {
  if (query?.city) city.value = decodeURIComponent(query.city);
  if (query?.lng && query?.lat) {
    center.value = { lng: Number(query.lng), lat: Number(query.lat) };
    nearName.value = query.near ? decodeURIComponent(query.near) : "所选位置";
    nearInput.value = query.near ? decodeURIComponent(query.near) : "";
    load();
  } else if (query?.near) {
    nearInput.value = decodeURIComponent(query.near);
    applyNear();
  } else if (await locationAllowed()) {
    // 以前允许过定位，直接按当前位置找，不会弹窗
    await useMyLocation();
  } else {
    load();
  }
  findTrip();
});

/** 按当前位置找附近的餐厅；第一次会弹浏览器的授权提示，失败就提示原因、保持原来的列表。 */
async function useMyLocation() {
  if (locating.value) return;
  locating.value = true;
  try {
    const loc = await locate(nearMe.value);
    center.value = { lng: loc.lng, lat: loc.lat };
    city.value = loc.city || city.value;
    nearName.value = "";
    nearInput.value = "";
    nearMe.value = true;
    await load();
  } catch (e) {
    showError(e);
    if (!places.value.length) load();
  } finally {
    locating.value = false;
  }
}

/** 按当前城市或中心点、分类关键字查高德。 */
async function load() {
  loading.value = true;
  error.value = "";
  const keyword = cat.value === "全部" ? undefined : cat.value === "本地菜" ? `${city.value}菜` : cat.value;
  try {
    places.value = center.value
      ? await api.places({ kind: "food", lng: center.value.lng, lat: center.value.lat, keyword: keyword || "美食", radius: nearMe.value ? 1500 : 2000, size: 20 })
      : await api.places({ kind: "food", city: city.value, keyword, size: 20 });
  } catch (e) {
    places.value = [];
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function pick(c: string) {
  cat.value = c;
  load();
}

/** 输入的地标先在城市里找到坐标，再搜它周边的餐厅。 */
async function applyNear() {
  const q = nearInput.value.trim();
  if (!q) return clearNear();
  try {
    // 接口按评分排，名字里带输入词的才是要找的那个，都不带再取第一个
    const found = await api.places({ kind: "spot", city: city.value, keyword: q, size: 5 });
    const spot = found.find((p) => p.name.includes(q)) || found[0];
    if (!spot || spot.lng == null || spot.lat == null) {
      uni.showToast({ title: `在${city.value}没找到"${q}"`, icon: "none" });
      return;
    }
    center.value = { lng: spot.lng, lat: spot.lat };
    nearMe.value = false;
    nearName.value = spot.name;
    load();
  } catch (e) {
    showError(e);
  }
}

function clearNear() {
  nearMe.value = false;
  nearInput.value = "";
  nearName.value = "";
  center.value = null;
  load();
}

/** 查大众点评（美团）和携程的合并结果，慢，单独显示在列表上面。 */
async function dianping() {
  if (reviews.loading) return;
  reviews.loading = true;
  reviews.error = "";
  reviews.data = null;
  try {
    const data = await api.searchRestaurants({
      city: city.value,
      near: nearName.value || undefined,
      cuisine: cat.value === "全部" ? undefined : cat.value,
    });
    if (data.restaurants?.length) reviews.data = { ...data, queriedAt: new Date().toISOString() };
    else reviews.error = "两个平台都没返回餐厅：" + Object.entries(data.platforms || {}).map(([k, v]) => `${k}（${v}）`).join("、");
  } catch (e) {
    reviews.error = e instanceof Error ? e.message : String(e);
  } finally {
    reviews.loading = false;
  }
}

/** 找最近一个有逐项安排、还没结束的行程，用来"加入行程"；没有就不显示按钮。 */
async function findTrip() {
  try {
    const today = new Date().toISOString().slice(0, 10);
    const trips = (await api.trips()).filter((t) => !t.endDate || t.endDate >= today).slice(0, 3);
    for (const t of trips) {
      const detail = await api.trip(t.id);
      if (detail.version.itinerary?.days?.length) {
        trip.value = { id: t.id, days: detail.version.itinerary.days };
        return;
      }
    }
  } catch {
    // 没有行程就不显示"加入行程"
  }
}

/** 选第几天后加进行程，后端会放到空着的饭点。 */
function add(f: Place) {
  const t = trip.value;
  if (!t) return;
  const days = t.days.slice(0, 6);
  uni.showActionSheet({
    itemList: days.map((d, i) => `第${i + 1}天 ${shortDate(d.date)}${d.theme ? " · " + d.theme : ""}`),
    success: async ({ tapIndex }) => {
      try {
        await api.addItem(t.id, tapIndex + 1, {
          name: f.name, poiId: f.id, address: f.address, lng: f.lng, lat: f.lat, photo: f.photo,
          rating: f.rating, cost: f.cost, type: "FOOD",
        });
        added[f.name] = true;
        uni.showToast({ title: `已加入第${tapIndex + 1}天`, icon: "none" });
      } catch (e) {
        showError(e);
      }
    },
  });
}

function ask() {
  const where = nearName.value ? `${nearName.value}附近` : city.value;
  uni.navigateTo({ url: "/pages/chat/chat?q=" + encodeURIComponent(`${where}有什么好吃的？`) });
}
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 14px 16px 24px; box-sizing: border-box; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.title { font-size: 18px; font-weight: 700; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.cats { white-space: nowrap; }
.cats-inner { display: inline-flex; gap: 8px; }
.cat { flex-shrink: 0; white-space: nowrap; font-size: 14px; padding: 6px 16px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); cursor: pointer; }
.cat.on { background: var(--accent); border-color: var(--accent); color: #fff; }
.filters { display: flex; gap: 14px; align-items: center; margin: 12px 2px; font-size: 13px; color: var(--muted); flex-wrap: wrap; }
.near { display: flex; align-items: center; gap: 6px; background: var(--surface); border: 1px solid var(--line); border-radius: 999px; padding: 4px 12px; flex: 1; min-width: 200px; }
.near-input { flex: 1; font-size: 13px; color: var(--ink); }
.clear { color: var(--accent); cursor: pointer; }
.filter { display: flex; align-items: center; gap: 2px; cursor: pointer; }
.filter.on { color: var(--accent); font-weight: 600; }
.filter.map { margin-left: auto; color: var(--accent); border: 1px solid var(--accent); padding: 3px 10px; border-radius: 999px; gap: 4px; }
.loading, .error { font-size: 13px; color: var(--muted); text-align: center; padding: 16px 0; }
.error { color: #c2560c; }
.reviews { margin-bottom: 12px; }
.list { display: flex; flex-direction: column; gap: 10px; }
.item { display: flex; gap: 10px; background: var(--surface); border-radius: 14px; padding: 10px; border: 1px solid var(--line); }
.img { width: 96px; height: 96px; border-radius: 10px; flex-shrink: 0; background: var(--surface-2); }
.img.blank { background: linear-gradient(135deg, #fdba74, #f97316); display: flex; align-items: center; justify-content: center; }
.info { flex: 1; min-width: 0; }
.name { font-weight: 700; }
.meta { font-size: 12px; margin-top: 2px; }
.rating { color: var(--price); font-weight: 600; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.tag-chip { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: #fff4ea; color: #c2560c; }
.side { display: flex; flex-direction: column; justify-content: flex-end; }
.add { margin: 0; }
.add.added { background: var(--ok) !important; }
.empty { text-align: center; color: var(--muted); padding: 40px 0; grid-column: 1 / -1; }
.foot { font-size: 12px; color: var(--muted); text-align: center; margin-top: 12px; }
.ask { text-align: center; font-size: 13px; color: var(--muted); margin-top: 8px; cursor: pointer; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
.amap-links { display: flex; gap: 12px; margin-top: 4px; }
.amap { font-size: 12px; color: var(--accent); cursor: pointer; }
.filter.mine { gap: 4px; color: var(--accent); border: 1px solid var(--accent); padding: 3px 10px; border-radius: 999px; font-weight: 600; }
.filter.mine.on { background: var(--accent); color: #fff; }
.name-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
</style>
