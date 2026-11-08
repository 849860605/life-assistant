<template>
  <view class="cs">
    <view class="cs-head">
      <view class="cs-new" @click="emit('new')"><Icon name="chat" :size="17" color="var(--ink)" /><text>新对话</text></view>
      <view class="cs-icon" :class="{ on: searching }" title="搜索对话" aria-label="搜索对话" @click="toggleSearch"><Icon name="search" :size="16" color="var(--ink)" /></view>
      <view v-if="collapsible" class="cs-icon" title="收起" aria-label="收起对话列表" @click="emit('collapse')"><Icon name="back" :size="16" color="var(--muted)" /></view>
    </view>
    <view v-if="searching" class="cs-search">
      <Icon name="search" :size="14" color="var(--muted)" />
      <input id="conv-search" v-model="keyword" class="cs-search-input" placeholder="搜索标题和聊天内容" :focus="searching" />
      <view class="cs-clear" @click="toggleSearch"><Icon name="close" :size="12" color="var(--muted)" /></view>
    </view>

    <view v-if="!searching" class="cs-nav">
      <view class="cs-nav-item" @click="go('/pages/schedule/schedule?tab=trips', true)"><Icon name="trip" :size="17" color="var(--ink)" /><text>我的行程</text></view>
      <view class="cs-nav-item" @click="go('/pages/fav/fav', false)"><Icon name="heart" :size="17" color="var(--ink)" /><text>我的收藏</text></view>
      <view class="cs-nav-item" :class="{ on: moreOpen }" @click="moreOpen = !moreOpen"><Icon name="layers" :size="17" color="var(--ink)" /><text>更多</text><Icon class="cs-caret" :class="{ up: moreOpen }" name="down" :size="12" color="var(--muted)" /></view>
      <view v-if="moreOpen" class="cs-more-grid">
        <view v-for="m in MORE" :key="m.url" class="cs-more-item" @click="go(m.url, false)"><Icon :name="m.icon" :size="15" color="var(--muted)" /><text>{{ m.label }}</text></view>
      </view>
    </view>

    <view class="cs-title">{{ keyword ? "搜索结果" : "最近" }}</view>
    <scroll-view scroll-y class="cs-list">
      <view v-for="c in shown" :key="c.id" class="cs-item" :class="{ on: c.id === currentId }" @click="renamingId !== c.id && (hoverOut(), emit('open', c))" :data-cid="c.id" @mouseenter="hoverIn(c)" @mouseleave="hoverOut">
        <view v-if="renamingId === c.id" class="cs-rename" @click.stop>
          <input :id="`conv-rename-${c.id}`" v-model="renameText" class="input cs-input" maxlength="60" :focus="true" @confirm="saveRename(c)" @blur="saveRename(c)" />
        </view>
        <template v-else>
          <Icon v-if="c.pinnedAt" name="flag" :size="12" color="var(--accent)" />
          <text class="cs-text">{{ oneLine(c.title) }}</text>
          <text class="cs-date">{{ when(c.updatedAt) }}</text>
          <ConvMenu class="cs-menu" :conv="c" @rename="startRename(c)" @pinned="load" @deleted="onDeleted(c)" />
        </template>
      </view>
      <view v-if="hiddenCount > 0" class="cs-more" @click="expanded = true">查看更多（{{ list.length >= 50 ? "50+" : hiddenCount }}）</view>
      <view v-else-if="expanded && !keyword && list.length > FOLD" class="cs-more" @click="expanded = false">收起</view>
      <view v-if="loaded && !list.length" class="cs-empty">{{ keyword ? `没找到含"${keyword}"的对话` : "还没有对话" }}</view>
    </scroll-view>
    <!-- 鼠标停在一条对话上：右边浮出完整标题和更新时间 -->
    <view v-if="peek" class="cs-peek" :style="{ top: peek.top + 'px', left: peek.left + 'px' }">
      <text class="peek-title">{{ peek.title }}</text>
      <view class="peek-row"><Icon name="clock" :size="14" color="var(--muted)" /><text>更新于 {{ fullTime(peek.updatedAt) }}</text></view>
      <view v-if="peek.pinned" class="peek-row"><Icon name="flag" :size="14" color="var(--accent)" /><text>已置顶</text></view>
    </view>

    <view class="cs-foot">
      <!-- 点头像弹出账号菜单：套餐、开通续费、我的、收藏、退出 -->
      <view v-if="menuOpen" class="cs-pop-mask" @click="menuOpen = false" />
      <view v-if="menuOpen" class="cs-pop">
        <view class="pop-card pop-user">
          <UserAvatar :size="40" />
          <text class="pop-name">{{ nickname || "我" }}</text>
        </view>
        <view class="pop-card pop-plan">
          <view class="pop-col">
            <text class="pop-k">套餐</text>
            <text class="pop-v">{{ member?.active ? "会员" : "免费版" }}</text>
            <text v-if="member?.active && member.expiresAt" class="pop-s">{{ shortDay(member.expiresAt) }} 到期</text>
          </view>
          <view class="pop-col">
            <text class="pop-k">今天还能对话</text>
            <text class="pop-v">{{ member ? Math.max(0, member.dailyLimit - member.todayUsed) : "—" }}<text class="pop-unit"> 次</text></text>
          </view>
          <button class="pop-btn" @click="go('/pages/member/member', false)">{{ member?.active ? "续费会员" : "开通会员" }}</button>
        </view>
        <view class="pop-card pop-list">
          <view class="pop-row" @click="go('/pages/me/me', true)"><Icon name="user" :size="16" color="var(--ink)" /><text>我的</text></view>
          <view class="pop-row" @click="go('/pages/fav/fav', false)"><Icon name="heart" :size="16" color="var(--ink)" /><text>我的收藏</text></view>
          <ThemePicker class="pop-theme" />
          <view class="pop-line" />
          <view class="pop-row" @click="logout"><Icon name="back" :size="16" color="var(--ink)" /><text>退出登录</text></view>
        </view>
      </view>
      <view class="cs-me" :class="{ on: menuOpen }" @click="toggleMenu">
        <UserAvatar :size="28" />
        <text class="cs-name">{{ nickname || "我的" }}</text>
        <text v-if="member?.active" class="cs-badge">会员</text>
        <view class="cs-bell" title="今天的提醒" aria-label="今天的提醒" @click.stop="go('/pages/schedule/schedule', true)">
          <Icon name="bell" :size="17" color="var(--ink)" />
          <text v-if="reminders" class="cs-dot">{{ reminders > 9 ? "9+" : reminders }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { api } from "../api";
import { showError, toLogin } from "../api/http";
import type { Conversation, MembershipView } from "../api/types";
import ConvMenu from "./ConvMenu.vue";
import ThemePicker from "./ThemePicker.vue";
import UserAvatar from "./UserAvatar.vue";
import Icon from "./Icon.vue";

/**
 * 对话左边栏（首页和对话页共用，版式参考 WorkBuddy）：新对话、搜索按钮（点开才出搜索框，搜标题和聊天内容）、
 * 我的行程 / 我的收藏 / 更多（展开旅行服务），最近对话（置顶的在前，右边是日期，默认 8 条，其余"查看更多"展开），
 * 每条有"…"菜单（重命名、置顶、分享、导出、删除）。底部是头像和铃铛（今天还没到的提醒数）；
 * 点头像弹出账号菜单：套餐和今天剩余次数、开通/续费、我的、收藏、退出登录。
 */
const FOLD = 8;
const MORE = [
  { label: "机票比价", icon: "plane", url: "/pages/search/flight" },
  { label: "酒店比价", icon: "bed", url: "/pages/search/hotel" },
  { label: "美食", icon: "food", url: "/pages/search/food" },
  { label: "景点门票", icon: "ticket", url: "/pages/search/ticket" },
  { label: "地图", icon: "pin", url: "/pages/plan/map" },
  { label: "天气", icon: "weather", url: "/pages/weather/weather" },
  { label: "热门目的地", icon: "compass", url: "/pages/discover/discover" },
];
const props = withDefaults(defineProps<{ currentId?: string | null; collapsible?: boolean }>(), { currentId: null, collapsible: false });
const emit = defineEmits<{
  (e: "open", c: Conversation): void;
  (e: "new"): void;
  (e: "deleted", c: Conversation): void;
  (e: "collapse"): void;
}>();

const list = ref<Conversation[]>([]);
const loaded = ref(false);
const keyword = ref("");
const nickname = ref("");
const renamingId = ref("");
const renameText = ref("");
const expanded = ref(false);
const menuOpen = ref(false);
const member = ref<MembershipView | null>(null);
const searching = ref(false);
const moreOpen = ref(false);
const reminders = ref(0);
let timer: ReturnType<typeof setTimeout> | null = null;

onMounted(() => {
  load();
  api.me().then((u) => (nickname.value = u.nickname || u.username)).catch(() => {});
  api.membership().then((m) => (member.value = m)).catch(() => {});
  loadReminders();
});
// 搜索停顿 300 毫秒再查
watch(keyword, () => {
  if (timer) clearTimeout(timer);
  timer = setTimeout(load, 300);
});
defineExpose({ load });

/** 搜索时全列；否则折叠到 8 条，当前打开的对话在后面时也展开 */
const shown = computed(() => {
  if (keyword.value || expanded.value) return list.value;
  const head = list.value.slice(0, FOLD);
  const cur = props.currentId && !head.some((c) => c.id === props.currentId) ? list.value.find((c) => c.id === props.currentId) : null;
  return cur ? [...head, cur] : head;
});
const hiddenCount = computed(() => list.value.length - shown.value.length);

/** 标题里可能有换行（比如粘贴的攻略），列表里只显示成一行 */
function oneLine(t?: string | null) {
  return (t || "新对话").replace(/\s+/g, " ").trim() || "新对话";
}

const peek = ref<{ title: string; updatedAt: string; pinned: boolean; top: number; left: number } | null>(null);
let peekTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * 停留 0.4 秒再浮出，划过列表时不闪；手机上没有鼠标不会触发。
 * uni-app 包装过的事件拿不到真实元素，按 data-cid 到页面上找这一行的位置。
 */
function hoverIn(c: Conversation) {
  if (peekTimer) clearTimeout(peekTimer);
  peekTimer = setTimeout(() => {
    if (renamingId.value === c.id || menuOpen.value || typeof document === "undefined") return;
    const row = document.querySelector(`.cs-item[data-cid="${c.id}"]`);
    if (!row) return;
    const rect = row.getBoundingClientRect();
    const side = row.closest(".cs")?.getBoundingClientRect();
    peek.value = {
      title: (c.title || "新对话").trim(),
      updatedAt: c.updatedAt,
      pinned: !!c.pinnedAt,
      top: Math.min(rect.top, window.innerHeight - 160),
      left: (side?.right ?? rect.right) + 8,
    };
  }, 400);
}

function hoverOut() {
  if (peekTimer) clearTimeout(peekTimer);
  peek.value = null;
}

function fullTime(iso: string) {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** 列表右边的日期：今天显示时间，昨天，一周内"N天前"，今年"M月D日"，更早带年份 */
function when(iso: string) {
  const d = new Date(iso);
  const now = new Date();
  const days = Math.floor((new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    - new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()) / 86400000);
  if (days <= 0) return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  if (days === 1) return "昨天";
  if (days < 7) return `${days}天前`;
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`;
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
}

function shortDay(iso: string) {
  const d = new Date(iso);
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
}

/** 今天还没到时间的日程数（全天的也算），显示在铃铛上 */
function loadReminders() {
  const now = new Date();
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
  api.events(today, today)
    .then((list) => (reminders.value = list.filter((e) => e.allDay || Date.parse(e.startAt) > Date.now()).length))
    .catch(() => {});
}

/** 收起搜索时清掉关键字，列表回到最近对话 */
function toggleSearch() {
  searching.value = !searching.value;
  if (!searching.value) keyword.value = "";
}

/** 打开菜单时再取会员信息，保证次数是最新的 */
function toggleMenu() {
  menuOpen.value = !menuOpen.value;
  if (menuOpen.value) api.membership().then((m) => (member.value = m)).catch(() => {});
}

function go(url: string, tab: boolean) {
  menuOpen.value = false;
  moreOpen.value = false;
  if (tab) uni.reLaunch({ url });
  else uni.navigateTo({ url });
}

async function logout() {
  menuOpen.value = false;
  try {
    await api.logout();
  } finally {
    toLogin();
  }
}

async function load() {
  try {
    list.value = await api.conversations(keyword.value.trim());
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
}

function startRename(c: Conversation) {
  renamingId.value = c.id;
  renameText.value = c.title || "";
}

/** 回车或点别处保存；没改就不发请求 */
async function saveRename(c: Conversation) {
  if (renamingId.value !== c.id) return;
  const title = renameText.value.trim();
  renamingId.value = "";
  if (!title || title === c.title) return;
  try {
    c.title = (await api.renameConversation(c.id, title)).title;
  } catch (e) {
    showError(e);
  }
}

async function onDeleted(c: Conversation) {
  emit("deleted", c);
  await load();
}


// 当前会话变了（比如刚发出第一条、标题生成了），刷新一下列表
watch(() => props.currentId, () => load());
</script>

<style scoped>
.cs { width: 260px; flex-shrink: 0; height: 100%; display: flex; flex-direction: column; background: var(--side-bg); border-right: 1px solid var(--line); padding: 12px 10px; box-sizing: border-box; }
.cs-head { display: flex; align-items: center; gap: 4px; }
.cs-new { flex: 1; display: flex; align-items: center; gap: 8px; height: 40px; padding: 0 12px; border-radius: 10px; font-size: 15px; font-weight: 600; cursor: pointer; background: rgba(0, 0, 0, 0.05); }
.cs-new:hover { background: rgba(0, 0, 0, 0.08); }
.cs-icon { width: 34px; height: 34px; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.cs-icon.on { background: rgba(0, 0, 0, 0.05); }
.cs-icon:hover, .cs-nav-item:hover, .cs-item:hover, .cs-me:hover, .cs-bell:hover, .cs-more-item:hover { background: rgba(0, 0, 0, 0.05); }
.cs-nav { display: flex; flex-direction: column; gap: 2px; margin-top: 10px; }
.cs-nav-item { display: flex; align-items: center; gap: 10px; height: 38px; padding: 0 12px; border-radius: 8px; font-size: 14px; cursor: pointer; }
.cs-caret { margin-left: auto; transition: transform 0.15s; }
.cs-caret.up { transform: rotate(180deg); }
.cs-more-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 2px; padding: 2px 0 6px 20px; }
.cs-more-item { display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 6px; border-radius: 8px; font-size: 13px; color: var(--ink); cursor: pointer; white-space: nowrap; }
.cs-more-item text { white-space: nowrap; }
.cs-search { display: flex; align-items: center; gap: 6px; height: 34px; margin: 8px 2px 0; padding: 0 10px; border-radius: 8px; background: var(--surface); border: 1px solid var(--line); }
.cs-search-input { flex: 1; font-size: 13px; }
.cs-clear { display: flex; cursor: pointer; }
.cs-title { font-size: 13px; color: var(--muted); font-weight: 600; padding: 16px 10px 6px; }
.cs-list { flex: 1; height: 0; }
.cs-item { display: flex; align-items: center; gap: 6px; height: 36px; padding: 0 6px 0 10px; border-radius: 8px; font-size: 14px; cursor: pointer; }
.cs-item.on { background: var(--surface); box-shadow: inset 0 0 0 1px var(--line); font-weight: 600; }
.cs-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cs-date { font-size: 12px; color: var(--muted); white-space: nowrap; font-weight: 400; }
.cs-item:hover .cs-date, .cs-item.on .cs-date { display: none; }
@media (hover: none) { .cs-date { display: none; } }
.cs-more { margin: 4px 0 8px 4px; padding: 6px 12px; font-size: 13px; font-weight: 600; color: var(--muted); cursor: pointer; border-radius: 8px; display: inline-block; }
.cs-more:hover { background: rgba(0, 0, 0, 0.05); color: var(--ink); }
.cs-menu { opacity: 0; }
.cs-item:hover .cs-menu, .cs-item.on .cs-menu { opacity: 1; }
@media (hover: none) { .cs-menu { opacity: 1; } }
.cs-rename { flex: 1; }
.cs-input { height: 30px; font-size: 14px; width: 100%; }
.cs-empty { padding: 8px 10px; font-size: 13px; color: var(--muted); }
.cs-peek { position: fixed; z-index: 60; width: 300px; max-width: calc(100vw - 280px); padding: 14px 16px; border-radius: 14px; background: var(--surface); border: 1px solid var(--line); box-shadow: 0 12px 40px rgba(0, 0, 0, 0.14); display: flex; flex-direction: column; gap: 8px; pointer-events: none; box-sizing: border-box; }
.peek-title { font-size: 15px; font-weight: 600; line-height: 1.45; white-space: pre-line; word-break: break-all; display: -webkit-box; -webkit-line-clamp: 6; -webkit-box-orient: vertical; overflow: hidden; }
.peek-row { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--muted); }
.cs-foot { position: relative; border-top: 1px solid var(--line); margin: 8px -10px -12px; padding: 8px 10px 12px; }
.cs-me { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 10px; cursor: pointer; }
.cs-me.on { background: rgba(0, 0, 0, 0.05); }
.cs-name { flex: 1; min-width: 0; font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cs-bell { position: relative; width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.cs-dot { position: absolute; top: 1px; right: 0; min-width: 16px; height: 16px; padding: 0 4px; box-sizing: border-box; border-radius: 8px; background: var(--danger); color: #fff; font-size: 10px; font-weight: 600; line-height: 16px; text-align: center; }
.cs-badge { font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--accent-soft); color: var(--accent); }

/* 账号菜单 */
.cs-pop-mask { position: fixed; inset: 0; z-index: 50; }
.cs-pop { position: absolute; left: 8px; bottom: calc(100% - 4px); width: 340px; z-index: 51; padding: 8px; border-radius: 16px; background: var(--surface-2); border: 1px solid var(--line); box-shadow: 0 12px 40px rgba(0, 0, 0, 0.16); display: flex; flex-direction: column; gap: 8px; box-sizing: border-box; }
.pop-card { background: var(--surface); border-radius: 12px; padding: 14px 16px; }
.pop-user { display: flex; align-items: center; gap: 12px; }
.pop-name { font-size: 16px; font-weight: 600; }
.pop-plan { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.pop-col { display: flex; flex-direction: column; gap: 2px; }
.pop-k { font-size: 12px; color: var(--muted); }
.pop-v { font-size: 17px; font-weight: 600; font-variant-numeric: tabular-nums; }
.pop-unit { font-size: 13px; font-weight: 400; color: var(--muted); }
.pop-s { font-size: 12px; color: var(--muted); }
.pop-btn { grid-column: 1 / -1; margin: 0; height: 34px; line-height: 34px; font-size: 14px; border-radius: 8px; background: var(--ink); color: var(--surface); border: none; }
.pop-btn::after { border: none; }
.pop-list { padding: 6px; display: flex; flex-direction: column; }
.pop-row { display: flex; align-items: center; gap: 10px; height: 38px; padding: 0 10px; border-radius: 8px; font-size: 14px; cursor: pointer; }
.pop-row:hover { background: var(--surface-2); }
.pop-theme { margin: 2px 0; }
.pop-line { height: 1px; background: var(--line); margin: 4px 10px; }
</style>
