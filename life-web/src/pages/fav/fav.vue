<template>
  <Shell active="fav">
    <view class="page-wrap fav-page">
      <view class="head">
        <view>
          <view class="page-title">我的收藏</view>
          <view class="page-sub">收藏的方案只是先存着看看；要出行请在方案里点「保存为行程」。</view>
        </view>
        <view class="search">
          <Icon name="search" :size="14" color="var(--muted)" />
          <input id="fav-search" v-model="keyword" class="search-input" placeholder="搜索收藏" />
        </view>
      </view>
      <view class="seg">
        <view v-for="t in TABS" :key="t.key" class="seg-item" :class="{ on: kind === t.key }" @click="kind = t.key">{{ t.label }}<text v-if="countOf(t.key)" class="cnt">{{ countOf(t.key) }}</text></view>
      </view>

      <view v-if="!loaded" class="state">加载中…</view>
      <view v-else-if="error" class="state">{{ error }} <text class="link" @click="load">重试</text></view>
      <view v-else-if="!shown.length" class="empty">
        <template v-if="keyword">没有找到"{{ keyword }}"相关的收藏</template>
        <template v-else-if="!list.length">还没有收藏。看到喜欢的方案、酒店、餐厅或景点，点心形图标就能存到这里。</template>
        <template v-else>这一类还没有收藏</template>
      </view>

      <view v-else class="list">
        <view v-for="f in shown" :key="f.id" class="row" @click="open(f)">
          <image v-if="f.photo && !broken[f.id]" class="thumb" :src="f.photo" mode="aspectFill" @error="broken[f.id] = true" />
          <view v-else class="thumb blank"><Icon :name="ICON[f.kind]" :size="20" color="var(--muted)" /></view>
          <view class="row-main">
            <view class="row-title">{{ f.title }}<text class="kind">{{ KIND[f.kind] }}</text></view>
            <view class="row-sub">{{ [f.city, f.subtitle].filter(Boolean).join(" · ") }}</view>
            <view v-if="gone[f.id]" class="gone">{{ gone[f.id] }}</view>
          </view>
          <view class="row-acts" @click.stop>
            <text class="link" @click="open(f)">查看</text>
            <text class="link muted-link" @click="remove(f)">取消收藏</text>
          </view>
        </view>
      </view>
    </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { ApiError, showError } from "../../api/http";
import type { Favorite } from "../../api/types";
import Icon from "../../components/Icon.vue";
import Shell from "../../components/Shell.vue";
import { loadFavorites } from "../../utils/favorites";
import { openUrl } from "../../utils/format";

/**
 * 我的收藏：方案、酒店、美食、景点分类，搜索、查看、取消收藏。方案指向一个还没保存的行程，
 * 打开前先确认它还在；删掉了就提示失效。酒店、餐厅、景点有平台或高德链接就打开链接，没有就去对应的查询页。
 */
const TABS = [
  { key: "", label: "全部" },
  { key: "PLAN", label: "方案" },
  { key: "HOTEL", label: "酒店" },
  { key: "FOOD", label: "美食" },
  { key: "SPOT", label: "景点" },
];
const KIND: Record<Favorite["kind"], string> = { PLAN: "方案", HOTEL: "酒店", FOOD: "美食", SPOT: "景点" };
const ICON: Record<Favorite["kind"], string> = { PLAN: "route", HOTEL: "bed", FOOD: "food", SPOT: "ticket" };

const list = ref<Favorite[]>([]);
const loaded = ref(false);
const error = ref("");
const kind = ref("");
const keyword = ref("");
const broken = reactive<Record<number, boolean>>({});
/** 打开时发现已经失效的收藏，显示原因 */
const gone = reactive<Record<number, string>>({});

const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  return list.value.filter((f) => (!kind.value || f.kind === kind.value)
    && (!k || [f.title, f.subtitle, f.city].some((x) => x?.toLowerCase().includes(k))));
});

onShow(load);

async function load() {
  try {
    list.value = await api.favorites();
    error.value = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "收藏没加载出来";
  } finally {
    loaded.value = true;
  }
}

function countOf(k: string) {
  return k ? list.value.filter((f) => f.kind === k).length : list.value.length;
}

async function open(f: Favorite) {
  if (f.kind === "PLAN") {
    const id = Number(f.refKey.replace("trip:", ""));
    try {
      await api.trip(id);
      uni.navigateTo({ url: `/pages/trips/detail?id=${id}` });
    } catch (e) {
      if (e instanceof ApiError && (e.code === 404 || e.code === 403)) gone[f.id] = "这个方案已经删掉了，可以取消收藏";
      else showError(e);
    }
    return;
  }
  if (f.url) {
    openUrl(f.url);
    return;
  }
  const q = encodeURIComponent(f.title);
  const city = f.city ? `&city=${encodeURIComponent(f.city)}` : "";
  if (f.kind === "HOTEL") uni.navigateTo({ url: `/pages/search/hotel?place=${q}${city}` });
  else if (f.kind === "FOOD") uni.navigateTo({ url: `/pages/search/food?${city.slice(1)}` });
  else uni.navigateTo({ url: `/pages/search/ticket?keyword=${q}${city}` });
}

function remove(f: Favorite) {
  uni.showModal({
    title: "取消收藏",
    content: `不再收藏「${f.title}」？`,
    confirmText: "取消收藏",
    confirmColor: "#C4372B",
    success: async (r) => {
      if (!r.confirm) return;
      try {
        await api.removeFavorite(f.id);
        list.value = list.value.filter((x) => x.id !== f.id);
        loadFavorites(true);
      } catch (e) {
        showError(e);
      }
    },
  });
}
</script>

<style scoped>
.fav-page { max-width: 960px; }
.head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.search { display: flex; align-items: center; gap: 6px; height: 34px; padding: 0 10px; border-radius: 8px; background: var(--surface); border: 1px solid var(--line); width: 240px; box-sizing: border-box; }
.search-input { flex: 1; font-size: 14px; }
.seg { align-self: flex-start; }
.cnt { margin-left: 4px; font-size: 12px; color: var(--muted); font-weight: 400; }
.state, .empty { text-align: center; padding: 48px 16px; font-size: 14px; color: var(--muted); }
.empty { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); }
.list { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.row { display: flex; align-items: center; gap: 14px; padding: 12px 18px; border-bottom: 1px solid var(--line); cursor: pointer; }
.row:last-child { border-bottom: none; }
.row:hover { background: var(--surface-2); }
.thumb { width: 56px; height: 56px; border-radius: 8px; flex-shrink: 0; }
.thumb.blank { background: var(--surface-2); display: flex; align-items: center; justify-content: center; }
.row-main { flex: 1; min-width: 0; }
.row-title { font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kind { margin-left: 8px; font-size: 11px; font-weight: 400; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); color: var(--muted); }
.row-sub { font-size: 13px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gone { font-size: 12px; color: var(--warn); margin-top: 2px; }
.row-acts { display: flex; gap: 16px; }
.link { color: var(--accent); cursor: pointer; font-size: 14px; white-space: nowrap; }
.muted-link { color: var(--muted); }
@media (max-width: 767px) {
  .search { width: 100%; }
  .row { flex-wrap: wrap; padding: 12px 14px; }
  .row-main { flex-basis: calc(100% - 80px); }
  .row-acts { flex-basis: 100%; justify-content: flex-end; }
}
</style>
