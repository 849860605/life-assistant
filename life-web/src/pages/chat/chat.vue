<template>
  <Shell active="chat" :show-recent="false">
  <view class="chat" :class="{ wide }">
    <!-- 会话列表：抽屉，点"历史对话"打开 -->
    <view class="aside" :class="{ open: showList }">
      <view class="aside-head">
        <text class="aside-title">会话</text>
        <button class="btn small" @click="startNew">新对话</button>
      </view>
      <scroll-view scroll-y class="aside-list">
        <view v-if="!conversations.length" class="empty">还没有会话</view>
        <view
          v-for="c in conversations"
          :key="c.id"
          class="conv"
          :class="{ active: c.id === currentId }"
          @click="open(c)"
        >
          <view class="conv-title">{{ c.title || "新对话" }}</view>
          <view class="conv-meta">
            <text v-if="c.skillId" class="chip" :class="c.skillId">{{ skillName(c.skillId) }}</text>
            <text class="muted">{{ relativeTime(c.updatedAt) }}</text>
            <text class="conv-del" @click.stop="remove(c)">删除</text>
          </view>
        </view>
      </scroll-view>
    </view>
    <view v-if="showList" class="mask" @click="showList = false" />

    <view class="main">
      <view class="toolbar">
        <view class="bot-logo"><Icon name="chat" :size="18" color="#fff" /></view>
        <view class="toolbar-title">
          <view class="bot-name">AI 旅行助手 <text class="beta">Beta</text></view>
          <view class="bot-sub">{{ currentId ? currentTitle : "帮你规划更好的旅行" }}</view>
        </view>
        <button class="btn ghost small" @click="toggleList">历史对话</button>
        <button class="btn ghost small" @click="startNew">新对话</button>
      </view>

      <scroll-view scroll-y class="messages" :scroll-top="scrollTop" :scroll-with-animation="false">
        <view class="thread">
          <view v-if="!messages.length" class="welcome">
            <view class="greet">
              <view class="avatar"><Icon name="chat" :size="16" color="#fff" /></view>
              <view class="greet-bubble">
                <view>你好！我是你的 AI 旅行助手。</view>
                <view>我可以帮你规划行程、推荐景点美食、查询机票酒店、比价预订等。</view>
                <view class="greet-tip">请告诉我你的旅行需求，或从下面的示例开始：</view>
                <view class="suggestions">
                  <view v-for="s in suggestions" :key="s" class="suggestion" @click="send(s)">
                    <text>{{ s }}</text><Icon name="right" :size="14" color="var(--accent)" />
                  </view>
                </view>
              </view>
            </view>
          </view>

          <view v-for="m in messages" :key="m.key" class="msg" :class="m.role">
            <template v-if="m.role === 'user'">
              <view class="bubble">{{ m.text }}</view>
              <view class="me-avatar">{{ myInitial }}</view>
            </template>
            <view v-else class="answer">
              <view class="answer-head">
                <view class="avatar"><Icon name="chat" :size="14" color="#fff" /></view>
                <text v-if="m.skillId" class="chip" :class="m.skillId">{{ m.skillName || skillName(m.skillId) }}</text>
              </view>
              <view v-if="steps(m).length" class="progress">
                <view v-for="st in steps(m)" :key="st.title" class="pstep" :class="st.state">
                  <view class="pdot"><Icon :name="st.state === 'done' ? 'check' : 'refresh'" :size="12" color="#fff" /></view>
                  <view>
                    <view class="ptitle">{{ st.title }}</view>
                    <view v-if="st.detail" class="pdetail">{{ st.detail }}</view>
                  </view>
                </view>
              </view>
              <template v-for="seg in segments(m)" :key="seg.key">
                <view v-if="seg.card" class="answer-card">
                  <ChatCard :card="seg.card" :active="m === messages[messages.length - 1] && !sending" @submit="send" />
                </view>
                <view v-else class="answer-text"><MarkdownView :text="seg.text || ''" /></view>
              </template>
              <view v-if="m.status === 'waiting'" class="status answer-text">
                {{ m.skillId ? "正在查资料、计算…" : "正在理解你的问题…" }}
              </view>
              <view v-if="m.status === 'streaming'" class="caret" />
              <view v-if="m.status === 'stopped'" class="status">已停止</view>
              <view v-if="m.status === 'error'" class="error">{{ m.error }}</view>
            </view>
          </view>
        </view>
      </scroll-view>

      <view class="composer">
        <view class="composer-box">
          <textarea
            id="chat-input"
            v-model="input"
            class="composer-input"
            :auto-height="true"
            :maxlength="4000"
            :show-confirm-bar="false"
            confirm-type="send"
            placeholder="你可以继续补充需求，例如：更换航班、增加景点…"
            @confirm="send(input)"
          />
          <view class="composer-tools">
            <view class="tool" @click="notYet('上传图片')"><Icon name="layers" :size="14" color="var(--muted)" /><text>上传图片</text></view>
            <view class="tool" @click="notYet('深度搜索')"><Icon name="search" :size="14" color="var(--muted)" /><text>深度搜索</text></view>
            <view class="model"><Icon name="sparkle" :size="13" color="var(--accent)" /><text>DeepSeek</text></view>
            <button v-if="sending" class="btn danger small stop-btn" @click="stop">停止</button>
            <view v-else class="send" :class="{ off: !input.trim() }" @click="send(input)"><Icon name="send" :size="18" color="#fff" /></view>
          </view>
        </view>
      </view>
    </view>

    <PlanPanel v-if="wide" class="side-panel" :trip="latestTrip" />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { getToken, showError } from "../../api/http";
import { streamMessage } from "../../api/sse";
import type { Card, ChatMessage, Conversation } from "../../api/types";
import MarkdownView from "../../components/MarkdownView.vue";
import ChatCard from "../../components/cards/ChatCard.vue";
import Icon from "../../components/Icon.vue";
import PlanPanel from "../../components/PlanPanel.vue";
import Shell from "../../components/Shell.vue";
import { useWide } from "../../utils/screen";
import { relativeTime, SKILL_NAMES } from "../../utils/format";

/** 页面上的一条消息；assistant 消息的 status 跟着流式事件变化。 */
interface UiMessage {
  key: number;
  role: "user" | "assistant";
  text: string;
  /** 工具发来的卡片，offset 是它在 text 里的位置 */
  cards: Card[];
  skillId?: string;
  skillName?: string;
  status?: "waiting" | "streaming" | "done" | "stopped" | "error";
  error?: string;
}

const suggestions = [
  "帮我规划西安 4 天 3 晚行程",
  "带父母去西安，有哪些适合的景点？",
  "西安有哪些必吃的美食？",
  "西安这周天气怎么样？",
  "帮我找从深圳到西安的机票",
];

const wide = useWide();
const myInitial = ref("我");
api.me().then((u) => (myInitial.value = (u.nickname || u.username).slice(0, 1).toUpperCase())).catch(() => {});

/** 当前会话里最后一张行程卡，电脑上的右侧面板显示它 */
const latestTrip = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const card = [...messages.value[i].cards].reverse().find((c) => c.kind === "trip");
    if (card) return card.data;
  }
  return null;
});

/** 回答顶部的进度：路由完成算"理解需求"，每张卡片对应一步，还没结束时最后一步显示"处理中"。 */
function steps(m: UiMessage): { title: string; detail?: string; state: "done" | "doing" }[] {
  if (m.role !== "assistant" || (!m.cards.length && m.status === "done")) return [];
  const out: { title: string; detail?: string; state: "done" | "doing" }[] = [];
  if (m.skillId) out.push({ title: "理解你的需求", detail: m.skillName || skillName(m.skillId), state: "done" });
  for (const c of m.cards) {
    const d = c.data || {};
    if (c.kind === "flights") out.push({ title: "查询航班信息", detail: `${d.origin} → ${d.destination}，找到 ${d.flights?.length ?? 0} 个航班`, state: "done" });
    else if (c.kind === "hotels") out.push({ title: "查询酒店信息", detail: `比价 ${d.results?.length ?? 0} 家酒店`, state: "done" });
    else if (c.kind === "restaurants") out.push({ title: "查询美食", detail: `找到 ${d.restaurants?.length ?? 0} 家餐厅`, state: "done" });
    else if (c.kind === "trip") out.push({ title: "生成行程方案", detail: `${d.title}（${d.days?.length ?? 0} 天）`, state: "done" });
    else if (c.kind === "requirement") out.push({ title: "整理需求", detail: "请在卡片上确认", state: "done" });
  }
  if (m.status === "waiting" || m.status === "streaming") {
    out.push({ title: m.cards.length ? "整理结果中…" : "正在查询…", state: "doing" });
  }
  return out.length > 1 || m.status !== "done" ? out : [];
}

function notYet(what: string) {
  uni.showToast({ title: `${what}还没接入`, icon: "none" });
}



const conversations = ref<Conversation[]>([]);
const currentId = ref<string | null>(null);
const messages = ref<UiMessage[]>([]);
const input = ref("");
const sending = ref(false);
const showList = ref(false);
const scrollTop = ref(0);
let controller: AbortController | null = null;
let seq = 0;

const currentTitle = computed(() => {
  const c = conversations.value.find((x) => x.id === currentId.value);
  return c?.title || "新对话";
});

onShow(loadConversations);

/** 从首页等处跳过来：q 是要直接发的问题，id 是要打开的会话。 */
onLoad((query) => {
  if (query?.q) {
    send(decodeURIComponent(query.q));
  } else if (query?.id) {
    open({ id: query.id } as Conversation);
  }
});

/** 刷新左侧会话列表，失败只提示不打断；没登录不请求（App 会跳登录页）。 */
async function loadConversations() {
  if (!getToken()) return;
  try {
    conversations.value = await api.conversations();
  } catch (e) {
    showError(e);
  }
}

/** 按卡片位置把回答切成"文字、卡片、文字…"几段，卡片插在它出现时已输出的文字后面。 */
function segments(m: UiMessage): { key: string; text?: string; card?: Card }[] {
  const out: { key: string; text?: string; card?: Card }[] = [];
  let pos = 0;
  const cards = [...m.cards].sort((a, b) => (a.offset ?? 0) - (b.offset ?? 0));
  cards.forEach((card, i) => {
    const at = Math.min(card.offset ?? m.text.length, m.text.length);
    if (at > pos && m.text.slice(pos, at).trim()) out.push({ key: `t${i}`, text: m.text.slice(pos, at) });
    pos = Math.max(pos, at);
    out.push({ key: `c${i}`, card });
  });
  if (m.text.slice(pos).trim()) out.push({ key: "tail", text: m.text.slice(pos) });
  return out;
}

/** 历史消息的 metadata 里取卡片。 */
function cardsOf(m: ChatMessage): Card[] {
  if (!m.metadata) return [];
  try {
    const meta = typeof m.metadata === "string" ? JSON.parse(m.metadata) : m.metadata;
    return Array.isArray(meta?.cards) ? meta.cards : [];
  } catch {
    return [];
  }
}

function skillName(id: string) {
  return SKILL_NAMES[id] || id;
}

/** 窄屏下打开或收起会话抽屉。 */
function toggleList() {
  showList.value = !showList.value;
}

/** 回到空白的新对话；真正建会话放到发第一条消息时。 */
function startNew() {
  if (sending.value) return;
  currentId.value = null;
  messages.value = [];
  showList.value = false;
}

/** 打开一个历史会话，只显示用户和助手的消息。 */
async function open(c: Conversation) {
  if (sending.value) return;
  showList.value = false;
  currentId.value = c.id;
  try {
    const history = await api.messages(c.id);
    messages.value = history
      .filter((m) => m.role === "USER" || m.role === "ASSISTANT")
      .sort((a, b) => a.id - b.id)
      .map((m) => ({
        key: ++seq,
        role: m.role === "USER" ? "user" : "assistant",
        text: m.content,
        cards: cardsOf(m),
        skillId: m.role === "ASSISTANT" && c.skillId ? c.skillId : undefined,
        status: "done",
      }));
    scrollToBottom();
  } catch (e) {
    showError(e);
  }
}

/** 删除会话前确认，删的是当前会话就回到新对话。 */
function remove(c: Conversation) {
  uni.showModal({
    title: "删除会话",
    content: `删除"${c.title || "新对话"}"？聊天记录会一起删掉。`,
    confirmText: "删除",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await api.deleteConversation(c.id);
        if (c.id === currentId.value) startNew();
        await loadConversations();
      } catch (e) {
        showError(e);
      }
    },
  });
}

/** 发一条消息并流式显示回答；第一条消息时先建会话。 */
async function send(raw: string) {
  const text = raw.trim();
  if (!text || sending.value) return;
  input.value = "";
  sending.value = true;
  messages.value.push({ key: ++seq, role: "user", text, cards: [] });
  messages.value.push({ key: ++seq, role: "assistant", text: "", cards: [], status: "waiting" });
  // 取响应式代理，后面直接改它，界面才会更新
  const answer = messages.value[messages.value.length - 1];
  scrollToBottom();

  controller = new AbortController();
  try {
    if (!currentId.value) {
      currentId.value = (await api.createConversation()).id;
    }
    await streamMessage(currentId.value, text, (event) => {
      if (event.type === "route") {
        answer.skillId = event.skillId;
        answer.skillName = event.skillName;
      } else if (event.type === "delta") {
        answer.text += event.text || "";
        answer.status = "streaming";
        scrollToBottom();
      } else if (event.type === "card" && event.card) {
        answer.cards.push(event.card);
        answer.status = "streaming";
        scrollToBottom();
      } else if (event.type === "error") {
        answer.status = "error";
        answer.error = event.message || "这次回答出错了";
      } else if (event.type === "done" && answer.status !== "error") {
        answer.status = "done";
      }
    }, controller.signal);
    if (answer.status === "waiting" || answer.status === "streaming") {
      answer.status = "done";
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      answer.status = "stopped";
    } else {
      answer.status = "error";
      answer.error = e instanceof Error ? e.message : String(e);
    }
  } finally {
    sending.value = false;
    controller = null;
    scrollToBottom();
    loadConversations();
  }
}

/** 停止接收当前回答。后端已经生成的部分仍会存进记录。 */
function stop() {
  controller?.abort();
}

/** 滚到底：scroll-top 的值变了才会生效，所以在两个大数之间切换。 */
function scrollToBottom() {
  nextTick(() => {
    scrollTop.value = scrollTop.value === 1e7 ? 1e7 + 1 : 1e7;
  });
}
</script>

<style scoped>
.chat {
  display: flex;
  height: calc(100vh - var(--window-top) - var(--window-bottom));
  overflow: hidden;
}

/* 会话列表 */
.aside {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-right: 1px solid var(--line);
}
.aside-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
}
.aside-head .btn { margin: 0; }
.aside-title { font-weight: 600; }
.aside-list { flex: 1; height: 0; }
.conv {
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
}
.conv.active { background: var(--accent-soft); }
.conv-title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
}
.conv-del { margin-left: auto; color: var(--muted); padding: 2px 4px; }
.conv-del:hover { color: var(--danger); }
.mask { display: none; }

/* 主区域 */
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
}
.toolbar .btn { margin: 0; }
.toolbar-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.messages { flex: 1; height: 0; }
.thread {
  max-width: 760px;
  margin: 0 auto;
  padding: 16px max(16px, env(safe-area-inset-right)) 24px max(16px, env(safe-area-inset-left));
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.welcome { padding: 8px 0; display: flex; flex-direction: column; gap: 14px; }
.greet { display: flex; gap: 8px; align-items: flex-start; }
.greet-bubble {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 4px 16px 16px 16px;
  padding: 10px 14px;
  font-size: 14px;
}
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.suggestions { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.suggestion {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 14px;
  cursor: pointer;
}
.suggestion:hover { border-color: var(--accent); }

.msg.user { display: flex; justify-content: flex-end; }
.bubble {
  max-width: 85%;
  background: var(--accent);
  color: var(--accent-ink);
  padding: 9px 14px;
  border-radius: 16px 16px 4px 16px;
  white-space: pre-wrap;
  word-break: break-word;
}
.answer { display: flex; flex-direction: column; gap: 10px; }
.answer-text {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 4px 16px 16px 16px;
  padding: 12px 16px;
}
.answer-head { display: flex; align-items: center; gap: 8px; margin-bottom: -2px; }
.answer-head .avatar { width: 24px; height: 24px; }
.status { color: var(--muted); font-size: 14px; }
.error {
  margin-top: 6px;
  color: var(--danger);
  background: var(--danger-soft);
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 14px;
}
.caret {
  display: inline-block;
  width: 8px;
  height: 16px;
  background: var(--accent);
  vertical-align: text-bottom;
  animation: blink 1s steps(2) infinite;
}
@keyframes blink { 50% { opacity: 0; } }
@media (prefers-reduced-motion: reduce) { .caret { animation: none; } }

/* 输入区 */
.composer {
  border-top: 1px solid var(--line);
  background: var(--surface);
  padding: 10px max(16px, env(safe-area-inset-right)) 10px max(16px, env(safe-area-inset-left));
}
.composer-inner {
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
}
.composer-input {
  flex: 1;
  min-height: 24px;
  max-height: 160px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--bg);
  color: var(--ink);
  font-size: 15px;
  line-height: 1.5;
  width: auto;
}
.composer .btn { margin: 0; flex-shrink: 0; }

/* 窄屏：会话列表变抽屉，快捷入口每行 3 个 */
/* 会话列表：抽屉 */
.aside {
  position: fixed;
  z-index: 40;
  top: 0;
  bottom: 0;
  left: 0;
  width: min(300px, 82vw);
  transform: translateX(-100%);
  transition: transform 0.2s ease;
}
.aside.open { transform: translateX(0); }
.mask { display: block; position: fixed; z-index: 39; inset: 0; background: rgba(0, 0, 0, 0.3); }

/* 电脑：左边对话、右边方案面板 */
.chat.wide { height: calc(100vh - 59px); }
.chat.wide .main { flex: 0 0 460px; border-right: 1px solid var(--line); background: var(--surface); }
.chat.wide .thread { padding-left: 16px; padding-right: 16px; }
.side-panel { flex: 1; min-width: 0; height: 100%; }

/* 进度 */
.progress { display: flex; flex-direction: column; gap: 8px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 12px 14px; }
.pstep { display: flex; gap: 10px; align-items: flex-start; }
.pdot { width: 20px; height: 20px; border-radius: 50%; background: var(--ok); display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 1px; }
.pstep.doing .pdot { background: var(--accent); animation: spin 1.2s linear infinite; }
.pstep.doing .ptitle { color: var(--accent); }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .pstep.doing .pdot { animation: none; } }
.ptitle { font-size: 14px; font-weight: 600; }
.pdetail { font-size: 12px; color: var(--muted); }

/* 头部和输入框 */
.bot-logo { width: 34px; height: 34px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.bot-name { font-size: 16px; font-weight: 800; color: var(--ink); }
.beta { font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 6px; background: var(--accent-soft); color: var(--accent); margin-left: 4px; }
.bot-sub { font-size: 12px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.greet-tip { margin-top: 6px; color: var(--muted); }
.me-avatar { width: 30px; height: 30px; border-radius: 50%; background: #f59e0b; color: #fff; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.msg.user { gap: 8px; align-items: flex-start; }
.composer-box { max-width: 760px; margin: 0 auto; border: 1px solid var(--line); border-radius: 14px; padding: 10px 12px 8px; background: var(--surface); }
.composer-box .composer-input { border: none; background: transparent; padding: 2px 0; width: 100%; }
.composer-tools { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.tool { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--muted); padding: 4px 8px; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; }
.model { display: flex; align-items: center; gap: 4px; font-size: 12px; padding: 4px 8px; border-radius: 8px; background: var(--surface-2); margin-left: auto; }
.send { width: 38px; height: 38px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; cursor: pointer; }
.send.off { opacity: 0.45; cursor: default; }
.stop-btn { margin: 0; }
@media (prefers-reduced-motion: reduce) { .aside { transition: none; } }
</style>
