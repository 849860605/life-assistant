<template>
  <view class="chat">
    <!-- 会话列表：宽屏常驻左侧，窄屏是抽屉 -->
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
        <button class="btn ghost small list-toggle" @click="toggleList">会话</button>
        <text class="toolbar-title">{{ currentTitle }}</text>
        <button class="btn ghost small toolbar-new" @click="startNew">新对话</button>
      </view>

      <scroll-view scroll-y class="messages" :scroll-top="scrollTop" :scroll-with-animation="false">
        <view class="thread">
          <view v-if="!messages.length" class="welcome">
            <view class="welcome-title">想做点什么？</view>
            <view class="welcome-sub">直接说需要，我会交给合适的技能处理。</view>
            <view class="suggestions">
              <view v-for="s in suggestions" :key="s" class="suggestion" @click="send(s)">{{ s }}</view>
            </view>
          </view>

          <view v-for="m in messages" :key="m.key" class="msg" :class="m.role">
            <view v-if="m.role === 'user'" class="bubble">{{ m.text }}</view>
            <view v-else class="answer">
              <view v-if="m.skillId" class="answer-head">
                <text class="chip" :class="m.skillId">{{ m.skillName || skillName(m.skillId) }}</text>
              </view>
              <MarkdownView v-if="m.text" :text="m.text" />
              <view v-if="m.status === 'waiting'" class="status">
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
        <view class="composer-inner">
          <textarea
            id="chat-input"
            v-model="input"
            class="composer-input"
            :auto-height="true"
            :maxlength="4000"
            :show-confirm-bar="false"
            confirm-type="send"
            placeholder="比如：周六想去爬泰山看日出"
            @confirm="send(input)"
          />
          <button v-if="sending" class="btn danger" @click="stop">停止</button>
          <button v-else class="btn" :disabled="!input.trim()" @click="send(input)">发送</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { getToken, showError } from "../../api/http";
import { streamMessage } from "../../api/sse";
import type { Conversation } from "../../api/types";
import MarkdownView from "../../components/MarkdownView.vue";
import { relativeTime, SKILL_NAMES } from "../../utils/format";

/** 页面上的一条消息；assistant 消息的 status 跟着流式事件变化。 */
interface UiMessage {
  key: number;
  role: "user" | "assistant";
  text: string;
  skillId?: string;
  skillName?: string;
  status?: "waiting" | "streaming" | "done" | "stopped" | "error";
  error?: string;
}

const suggestions = [
  "这周六想去爬泰山看日出，从济南出发，帮我规划一下",
  "下周末带爸妈去杭州玩两天，预算三千",
  "明天早上 8 点提醒我交电费",
  "记住：我不吃辣，左膝有旧伤",
];

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

/** 刷新左侧会话列表，失败只提示不打断；没登录不请求（App 会跳登录页）。 */
async function loadConversations() {
  if (!getToken()) return;
  try {
    conversations.value = await api.conversations();
  } catch (e) {
    showError(e);
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
  messages.value.push({ key: ++seq, role: "user", text });
  messages.value.push({ key: ++seq, role: "assistant", text: "", status: "waiting" });
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
  padding: 8px 16px;
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
.list-toggle, .toolbar-new { display: none; }

.messages { flex: 1; height: 0; }
.thread {
  max-width: 760px;
  margin: 0 auto;
  padding: 16px max(16px, env(safe-area-inset-right)) 24px max(16px, env(safe-area-inset-left));
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.welcome { padding: 32px 0 8px; }
.welcome-title { font-size: 22px; font-weight: 600; }
.welcome-sub { color: var(--muted); margin: 4px 0 18px; font-size: 14px; }
.suggestions { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.suggestion {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 12px 14px;
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
.answer {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 4px 16px 16px 16px;
  padding: 12px 16px;
}
.answer-head { margin-bottom: 6px; }
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

/* 窄屏：会话列表变抽屉 */
@media (max-width: 900px) {
  .aside {
    position: fixed;
    z-index: 20;
    top: var(--window-top);
    bottom: var(--window-bottom);
    left: 0;
    width: min(300px, 82vw);
    transform: translateX(-100%);
    transition: transform 0.2s ease;
  }
  .aside.open { transform: translateX(0); }
  .mask {
    display: block;
    position: fixed;
    z-index: 19;
    inset: 0;
    background: rgba(0, 0, 0, 0.3);
  }
  .list-toggle, .toolbar-new { display: inline-flex; }
}
@media (prefers-reduced-motion: reduce) { .aside { transition: none; } }
</style>
