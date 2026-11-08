<template>
  <view v-if="!inline" class="ss-mask" @click="emit('close')" />
  <view class="ss" :class="{ inline }">
    <view class="ss-hero" :class="{ bare: !photo || broken }">
      <image v-if="photo && !broken" class="ss-photo" :src="photo" mode="aspectFill" @error="broken = true" />
      <view class="ss-close" @click="emit('close')"><Icon name="close" :size="18" :color="photo && !broken ? '#fff' : 'var(--ink)'" /></view>
    </view>

    <view class="ss-body">
      <view class="ss-name">{{ item.name }}</view>
      <view class="ss-tags">
        <text v-if="spot?.level" class="tag level">{{ spot.level }}A 景区</text>
        <text v-if="spot?.rank" class="tag rank">{{ spot.rank }}</text>
        <text v-if="item.rating" class="tag star">★ {{ item.rating }}</text>
        <text v-if="spot?.free" class="tag free">免费景点</text>
        <text v-if="booked" class="tag ok">已预约</text>
        <text v-if="done" class="tag ok">已去过</text>
      </view>

      <view class="sec">
        <view v-if="loading.detail" class="intro skeleton"><view /><view /><view class="short" /></view>
        <template v-else-if="intro">
          <view class="intro">{{ intro }}</view>
          <view v-if="aiWritten" class="fine">简介由 AI 根据公开资料整理，仅供参考</view>
        </template>
      </view>

      <view class="sec">
        <view class="sec-title">这次的安排</view>
        <view class="plan">
          <text v-if="item.start" class="plan-time">{{ item.start }}<text v-if="item.end && item.end !== item.start"> - {{ item.end }}</text></text>
          <text v-if="stay" class="muted">· {{ stay }}</text>
        </view>
        <view v-if="item.note" class="plan-note">{{ item.note }}</view>
      </view>

      <view class="sec">
        <view class="sec-title">开放时间和预约<text v-if="rules?.reservation === 'NEED'" class="chip warn">需要预约</text><text v-else-if="rules?.reservation === 'NO_NEED'" class="chip ok">无需预约</text></view>
        <view v-if="loading.rules" class="muted small">正在查开放时间和预约规则，大约十几秒…</view>
        <view v-else-if="ruleRows.length" class="rows">
          <view v-for="r in ruleRows" :key="r[0]" class="row"><text class="row-k">{{ r[0] }}</text><text class="row-v">{{ r[1] }}</text></view>
          <view class="fine">来自{{ rules?.source }}，出发前以景区公告为准</view>
        </view>
        <view v-else class="muted small">{{ rulesError || "没查到，出发前看一下景区官方公众号" }}</view>
      </view>

      <view class="sec">
        <view class="sec-title">门票</view>
        <view v-if="loading.tickets" class="muted small">正在查门票…</view>
        <view v-else-if="ticket && !spot?.free" class="ticket">
          <view class="ticket-main">
            <text class="ticket-price">{{ ticketPrice }}</text>
            <text class="muted small ticket-title">{{ ticket.title }}</text>
          </view>
          <text v-if="ticket.url" class="go" @click="openUrl(ticket.url)">去预订 ›</text>
        </view>
        <view v-else-if="spot?.free" class="ticket-free">免费<text class="muted small">（部分时段或场馆可能要提前实名预约）</text></view>
        <view v-else class="muted small">没查到单独的门票，可能免费或现场购买</view>
      </view>

      <view class="sec">
        <view class="sec-title">位置</view>
        <view v-if="item.address || spot?.address" class="addr">{{ item.address || spot?.address }}</view>
        <view class="links">
          <text class="link" @click="openUrl(amapUrl(item, city))"><Icon name="pin" :size="13" color="var(--accent)" /> 高德地图</text>
          <text class="link" @click="openUrl(amapNavUrl(item, city))"><Icon name="nav" :size="13" color="var(--accent)" /> 导航</text>
          <text v-if="pageUrl" class="link" @click="openUrl(pageUrl)"><Icon name="ticket" :size="13" color="var(--accent)" /> 飞猪景点页</text>
        </view>
      </view>
    </view>

    <view v-if="editable" class="ss-acts">
      <view class="a keep" :class="{ on: item.locked }" @click="emit('keep')"><Icon name="heart" :size="14" :fill="!!item.locked" color="var(--ok)" />{{ item.locked ? "已保留" : "喜欢，保留" }}</view>
      <view class="a" @click="emit('book')"><Icon name="checkCircle" :size="14" color="var(--ok)" />{{ booked ? "取消已预约" : "标记已预约" }}</view>
      <view class="a" @click="emit('done')"><Icon name="check" :size="14" color="var(--ok)" />{{ done ? "取消已去" : "标记已去" }}</view>
      <view class="a" @click="emit('replace', 'VISITED')"><Icon name="refresh" :size="14" color="var(--accent)" />以前去过，换一个</view>
      <view class="a ghost" @click="emit('replace', 'DISLIKE')"><Icon name="close" :size="14" color="var(--muted)" />不感兴趣</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { api } from "../api";
import type { ItineraryItem, SpotRules, TicketProduct, TicketSpot } from "../api/types";
import { openUrl } from "../utils/format";
import { amapNavUrl, amapUrl } from "../utils/place";
import Icon from "./Icon.vue";

/**
 * 景点详情（行程页点景点照片或名字打开）：简介（AI 根据飞猪和网上资料整理，存下来大家共用）、这次的安排、
 * 开放时间和预约（携程问道）、门票（飞猪）、位置；底部是保留、已预约、已去、换一个、不感兴趣。
 * inline 时（电脑）放在行程页中间栏、紧挨着时间线，往下滚时停在屏幕上，开着时点别的景点直接切换，按 Esc 关；
 * 手机上从底部弹出。看详情不扣积分。
 */
const props = defineProps<{ item: ItineraryItem; city: string; editable: boolean; booked: boolean; done: boolean; inline?: boolean }>();
const emit = defineEmits<{ close: []; keep: []; book: []; done: []; replace: [reason: "VISITED" | "DISLIKE"] }>();

const broken = ref(false);
const spot = ref<TicketSpot | null>(null);
const intro = ref<string | null>(null);
const aiWritten = ref(false);
const rules = ref<SpotRules | null>(null);
const rulesError = ref("");
const products = ref<TicketProduct[]>([]);
const loading = reactive({ detail: true, rules: true, tickets: true });

const photo = computed(() => props.item.photo || spot.value?.photo || "");
const pageUrl = computed(() => props.item.url || spot.value?.url || "");
/** 建议玩多久 */
const stay = computed(() => {
  const { start, end } = props.item;
  if (!start || !end) return "";
  const toMin = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
  const m = toMin(end) - toMin(start);
  if (m <= 0) return "";
  return m < 60 ? `玩约 ${m} 分钟` : `玩约 ${Number.isInteger(m / 60) ? m / 60 : (m / 60).toFixed(1)} 小时`;
});
/** 携程给的条目里挑开放时间、预约这类，最多 5 条 */
const ruleRows = computed(() => Object.entries(rules.value?.items || {}).slice(0, 5));
/** 单纯门票里最便宜的成人票；只有一日游、讲解、套餐的不算门票 */
const ticket = computed(() => {
  const plain = products.value.filter((p) => !p.bundle && (p.adult ?? p.price) != null);
  return plain.sort((a, b) => (a.adult ?? a.price ?? 0) - (b.adult ?? b.price ?? 0))[0] || null;
});
const ticketPrice = computed(() => {
  const t = ticket.value;
  if (!t) return "";
  if (t.adult != null) return `成人 ¥${t.adult}`;
  return t.price != null ? `¥${t.price} 起` : "看详情";
});

/** 电脑上按 Esc 关 */
function onKey(e: KeyboardEvent) {
  if (e.key === "Escape") emit("close");
}
onMounted(() => typeof window !== "undefined" && window.addEventListener("keydown", onKey));
onUnmounted(() => typeof window !== "undefined" && window.removeEventListener("keydown", onKey));

/** 打开时三样一起查，各自显示；换了景点时丢掉上一个景点晚到的结果 */
watch(
  () => props.item.id + props.item.name,
  () => {
    const name = props.item.name;
    const same = () => props.item.name === name;
    broken.value = false;
    spot.value = null;
    intro.value = null;
    rules.value = null;
    rulesError.value = "";
    products.value = [];
    Object.assign(loading, { detail: true, rules: true, tickets: true });
    api.spotDetail(props.city, name)
      .then((d) => { if (same()) { spot.value = d.spot; intro.value = d.intro || d.spot?.description || null; aiWritten.value = !!d.intro && d.aiWritten; } })
      .catch(() => {})
      .finally(() => { if (same()) loading.detail = false; });
    api.spotRules(props.city, name)
      .then((r) => { if (same()) rules.value = r; })
      .catch((e) => { if (same()) rulesError.value = e?.message || ""; })
      .finally(() => { if (same()) loading.rules = false; });
    api.spotTickets(props.city, name)
      .then((p) => { if (same()) products.value = p; })
      .catch(() => {})
      .finally(() => { if (same()) loading.tickets = false; });
  },
  { immediate: true },
);
</script>

<style scoped>
.ss-mask { position: fixed; inset: 0; z-index: 100; background: rgba(15, 23, 42, 0.4); }
.ss {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 101; max-height: 88vh; display: flex; flex-direction: column;
  background: var(--surface); border-radius: 18px 18px 0 0; overflow: hidden; box-shadow: 0 -12px 40px rgba(0, 0, 0, 0.2);
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
/* 电脑：放在中间栏，往下滚时停在顶部导航（64px）下面 */
.ss.inline { position: sticky; top: 80px; left: auto; right: auto; bottom: auto; z-index: 1; max-height: calc(100vh - 96px); border: 1px solid var(--line); border-radius: 16px; box-shadow: none; padding-bottom: 0; }
.ss-hero { position: relative; flex-shrink: 0; }
.ss-photo { display: block; width: 100%; height: 190px; }
.ss.inline .ss-photo { height: 220px; }
.ss-hero.bare { height: 52px; }
.ss-hero.bare .ss-close { background: var(--surface-2); }
.ticket-free { font-size: 16px; font-weight: 700; color: var(--ok); }
.ticket-free text { font-size: 13px; font-weight: 400; color: var(--muted); }
.ss-close { position: absolute; top: 12px; right: 12px; width: 32px; height: 32px; border-radius: 50%; background: rgba(0, 0, 0, 0.45); display: flex; align-items: center; justify-content: center; cursor: pointer; }
/* 中间这段自己滚动，底部按钮固定；不用 scroll-view，它在弹层里撑不住高度，内容会溢出压到按钮上 */
.ss-body { flex: 1; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; overscroll-behavior: contain; padding: 16px 18px 8px; box-sizing: border-box; }
.ss-name { font-size: 22px; font-weight: 800; line-height: 1.3; }
.ss-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.tag { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--surface-2); color: var(--muted); }
.tag.level { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.tag.rank { background: var(--danger-soft); color: var(--danger); }
.tag.star { color: var(--price); font-weight: 600; }
.tag.free, .tag.ok { background: var(--ok-soft); color: var(--ok); }
.sec { padding: 14px 0; border-bottom: 1px solid var(--line); }
.sec:last-child { border-bottom: none; }
.sec-title { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 700; color: var(--muted); margin-bottom: 8px; }
.intro { font-size: 15px; line-height: 1.75; }
.skeleton view { height: 14px; border-radius: 6px; background: var(--surface-2); margin: 6px 0 10px; }
.skeleton .short { width: 60%; }
.fine { font-size: 12px; color: var(--muted); margin-top: 6px; }
.plan { display: flex; gap: 6px; align-items: baseline; }
.plan-time { font-weight: 700; font-variant-numeric: tabular-nums; }
.plan-note { margin-top: 4px; line-height: 1.6; }
.chip { font-size: 11px; font-weight: 600; padding: 1px 7px; border-radius: 6px; }
.chip.warn { background: var(--warn-soft); color: var(--warn); }
.chip.ok { background: var(--ok-soft); color: var(--ok); }
.rows { display: flex; flex-direction: column; gap: 6px; }
.row { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 8px; font-size: 14px; line-height: 1.55; }
.row-k { color: var(--muted); }
.ticket { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.ticket-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ticket-price { font-size: 17px; font-weight: 700; color: var(--price); }
.ticket-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.go { flex-shrink: 0; color: var(--accent); font-weight: 600; cursor: pointer; }
.addr { font-size: 14px; line-height: 1.5; }
.links { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 8px; }
.link { display: inline-flex; align-items: center; gap: 4px; color: var(--accent); font-size: 14px; cursor: pointer; }
.ss-acts { flex-shrink: 0; display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 18px 14px; border-top: 1px solid var(--line); background: var(--surface); }
.a { display: inline-flex; align-items: center; gap: 5px; padding: 7px 12px; border-radius: 10px; border: 1px solid var(--line); font-size: 13px; cursor: pointer; }
.a.keep { background: var(--ok-soft); border-color: transparent; color: var(--ok); }
.a.keep.on { font-weight: 600; }
.a.ghost { color: var(--muted); }
</style>
