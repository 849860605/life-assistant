<template>
  <Shell active="chat" :show-recent="false" fill>
  <view class="chat" :class="{ wide, split: showPanel }">
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
        <BrandMark :size="34" />
        <view class="toolbar-title">
          <view class="bot-name">星络 <text class="beta">Beta</text></view>
          <view class="bot-sub">{{ currentId ? currentTitle : "旅行 · 日程 · 出行 · 生活" }}</view>
        </view>
        <button v-if="wide && hasPanel && panelClosed" class="btn ghost small" @click="panelClosed = false">{{ latestTrip ? "显示行程" : "显示方案" }}</button>
        <button class="btn ghost small tb-btn" @click="toggleList"><Icon name="clock" :size="14" color="var(--accent)" /><text>历史对话</text></button>
        <button class="btn ghost small tb-btn" @click="startNew"><Icon name="plus" :size="14" color="var(--accent)" /><text>新对话</text></button>
      </view>

      <scroll-view scroll-y class="messages" :scroll-top="scrollTop" :scroll-with-animation="false">
        <view class="thread">
          <view v-if="!messages.length" class="welcome">
            <view class="greet">
              <BrandMark :size="28" />
              <view class="greet-bubble">
                <view>你好！我是星络。</view>
                <view>我可以帮你规划行程、推荐景点美食、查询机票酒店并比价，也能记日程、提醒天气。</view>
                <view class="greet-tip">说说你想做什么，或从下面的示例开始：</view>
                <view class="suggestions">
                  <view v-for="s in suggestions" :key="s.text" class="suggestion" @click="send(s.text)">
                    <DuoIcon :name="s.icon" :size="18" /><text class="suggestion-text">{{ s.text }}</text><Icon name="right" :size="14" color="var(--accent)" />
                  </view>
                </view>
              </view>
            </view>
          </view>

          <view v-for="m in messages" :key="m.key" class="msg" :class="m.role">
            <template v-if="m.role === 'user'">
              <view class="u-col">
                <view class="bubble">
                  <text v-if="m.text">{{ m.text }}</text>
                  <view v-if="m.files?.length" class="b-files">
                    <view v-for="f in m.files" :key="f.id" class="b-file" @click="openFile(f.id)"><Icon :name="fileIcon(f.name)" :size="13" color="currentColor" /><text>{{ f.name }}</text></view>
                  </view>
                </view>
                <view v-if="m.addState" class="add-note">
                  <template v-if="m.addState === 'waiting'">
                    <text>等星络看到…</text>
                    <text class="link" @click="restartNow">不等了，马上重来</text>
                  </template>
                  <text v-else class="seen"><Icon name="check" :size="11" color="var(--ok)" /> 星络已看到</text>
                </view>
              </view>
              <view class="me-avatar">{{ myInitial }}</view>
            </template>
            <view v-else class="answer">
              <view class="answer-head">
                <BrandMark :size="24" />
                <text v-if="m.skillId" class="chip" :class="m.skillId">{{ m.skillName || skillName(m.skillId) }}</text>
              </view>
              <view v-if="steps(m).length" class="progress">
                <view v-for="(st, si) in steps(m)" :key="si" class="pstep" :class="st.state">
                  <view class="pdot"><Icon :name="st.state === 'done' ? 'check' : 'refresh'" :size="12" color="#fff" /></view>
                  <view>
                    <view class="ptitle">{{ st.title }}</view>
                    <view v-if="st.detail" class="pdetail">{{ st.detail }}</view>
                  </view>
                </view>
              </view>
              <template v-for="seg in segments(m)" :key="seg.key">
                <view v-if="seg.card" class="answer-card">
                  <ChatCard :card="seg.card" :active="m === messages[messages.length - 1] && !sending" :conversation-id="currentId" @submit="send" />
                </view>
                <view v-else class="answer-text"><MarkdownView :text="seg.text || ''" /></view>
              </template>
              <view v-if="m.status === 'waiting'" class="status answer-text">
                {{ m.skillId ? "正在查资料、计算…" : "正在理解你的问题…" }}
              </view>
              <view v-if="m.status === 'streaming'" class="caret" />
              <view v-if="m.status === 'stopped'" class="status">已停止</view>
              <view v-if="m.status === 'error'" class="error">
                {{ m.error }}<text v-if="m.quota" class="quota-link" @click="openMember">开通会员 ›</text>
              </view>
              <view v-if="m.text && (m.status === 'done' || m.status === 'stopped')" class="answer-actions">
                <view class="act-btn" :class="{ ok: copiedKey === m.key }" @click="copyAnswer(m)">
                  <Icon :name="copiedKey === m.key ? 'check' : 'copy'" :size="14" color="currentColor" />
                  <text>{{ copiedKey === m.key ? "已复制" : "复制" }}</text>
                </view>
              </view>
              <view v-if="m.suggestions?.length && m === messages[messages.length - 1] && !sending && m.status === 'done'" class="chips">
                <text v-for="(c, i) in m.suggestions" :key="c" class="chip-btn" :class="{ first: i === 0 }" @click="send(c, [], true)">{{ c }}</text>
              </view>
            </view>
          </view>
        </view>
      </scroll-view>

      <view class="composer">
        <view class="composer-box">
          <view v-if="pending.length" class="att-list">
            <view v-for="(a, i) in pending" :key="a.key" class="att" :class="a.state">
              <Icon :name="fileIcon(a.name)" :size="14" color="var(--muted)" />
              <view class="att-body">
                <text class="att-name">{{ a.name }}</text>
                <text class="att-state">{{ a.state === "uploading" ? "识别中，图片和扫描件要十几秒…" : a.state === "error" ? a.error : a.view?.error ? "没识别出来：" + a.view.error : `已识别 ${a.view?.chars} 字` }}</text>
              </view>
              <view class="att-x" @click="pending.splice(i, 1)"><Icon name="close" :size="12" color="var(--muted)" /></view>
            </view>
          </view>
          <textarea
            id="chat-input"
            v-model="input"
            class="composer-input"
            :auto-height="true"
            :maxlength="4000"
            :show-confirm-bar="false"
            confirm-type="send"
            :placeholder="sending ? '可以随时补充，星络下一步就会看到' : '你可以继续补充需求，例如：更换航班、增加景点…'"
            @confirm="sendInput"
          />
          <view class="composer-tools">
            <view class="tool" @click="pickFiles"><Icon name="plus" :size="14" color="var(--muted)" /><text>上传文件</text></view>
            <view class="tool" @click="notYet('深度搜索')"><Icon name="search" :size="14" color="var(--muted)" /><text>深度搜索</text></view>
            <ModelPicker class="model" :wide="wide" />
            <view v-if="sending && !input.trim()" class="send stop-btn" title="停止" @click="stop"><Icon name="stop" :size="14" color="#fff" fill /></view>
            <view v-else class="send" :class="{ off: !canSend }" @click="sendInput"><Icon name="send" :size="18" color="#fff" /></view>
          </view>
        </view>
      </view>
    </view>

    <PlanPanel v-if="showPanel" class="side-panel" :trip="latestTrip" :plans="latestPlans" @send="send" @close="panelClosed = true" />
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "../../api";
import { ApiError, getToken, showError } from "../../api/http";
import { streamMessage } from "../../api/sse";
import type { Card, ChatMessage, Conversation, Progress } from "../../api/types";
import MarkdownView from "../../components/MarkdownView.vue";
import ChatCard from "../../components/cards/ChatCard.vue";
import Icon from "../../components/Icon.vue";
import BrandMark from "../../components/BrandMark.vue";
import DuoIcon from "../../components/DuoIcon.vue";
import PlanPanel from "../../components/PlanPanel.vue";
import ModelPicker from "../../components/ModelPicker.vue";
import Shell from "../../components/Shell.vue";
import { useWide } from "../../utils/screen";
import { savePlans } from "../../utils/plans";
import { currentCity } from "../../utils/place";
import { markdownToPlain, relativeTime, SKILL_NAMES } from "../../utils/format";
import { ensureMediaTicket } from "../../utils/media";
import { fileIcon, fileUrl, splitAttachments, UPLOAD_EXTS, type Attached } from "../../utils/attach";
import type { UploadView } from "../../api/types";

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
  /** 今天的对话次数用完了：错误下面给个开通会员的入口 */
  quota?: boolean;
  /** 用户消息带的附件 */
  files?: Attached[];
  /** 回答下面的快捷回复，只在最新一条回答下显示 */
  suggestions?: string[];
  /** 工具执行中的实时进度（比价查到第几家），按 id 覆盖；回答结束后不再显示 */
  progress?: Progress[];
  /** 回答过程中补充的话：waiting 等星络看到，seen 已看到；addId 是发给后端的编号 */
  addState?: "waiting" | "seen";
  addId?: string;
}

/** 输入框上方待发送的附件：上传完就已经识别好了 */
interface PendingFile {
  key: number;
  name: string;
  state: "uploading" | "ok" | "error";
  view?: UploadView;
  error?: string;
}
const pending = ref<PendingFile[]>([]);
const MAX_FILES = 5;
/** 有字，或者有识别完的附件、没有还在上传的，才能发 */
const canSend = computed(() => !pending.value.some((a) => a.state === "uploading")
  && (!!input.value.trim() || pending.value.some((a) => a.state === "ok")));

/** 选文件并逐个上传识别；失败的留在列表里显示原因，可以删掉。 */
function pickFiles() {
  const room = MAX_FILES - pending.value.length;
  if (room <= 0) {
    uni.showToast({ title: `一次最多带 ${MAX_FILES} 个文件`, icon: "none" });
    return;
  }
  uni.chooseFile({
    count: room,
    extension: UPLOAD_EXTS,
    success: (res) => {
      const files = (Array.isArray(res.tempFiles) ? res.tempFiles : [res.tempFiles]) as any[];
      for (const f of files.slice(0, room)) {
        const item = reactive<PendingFile>({ key: ++seq, name: f.name || "文件", state: "uploading" });
        pending.value.push(item);
        api.uploadFile({ path: f.path, name: f.name, file: f instanceof File ? f : undefined })
          .then((view) => {
            item.view = view;
            item.state = "ok";
          })
          .catch((e) => {
            item.state = "error";
            item.error = e instanceof Error ? e.message : String(e);
          });
      }
    },
  });
}

/** 输入框发送：带上识别好的附件；只发了文件没打字时补一句。 */
function sendInput() {
  if (sending.value) {
    interject();
    return;
  }
  if (!canSend.value) return;
  const ok = pending.value.filter((a) => a.state === "ok" && a.view);
  const text = input.value.trim() || (ok.length > 1 ? "帮我看看这几个文件" : "帮我看看这个文件");
  const files = ok.map((a) => ({ id: a.view!.id, name: a.view!.name }));
  pending.value = [];
  send(text, files);
}

/** 在新标签页打开原文件 */
async function openFile(id: string) {
  await ensureMediaTicket();
  window.open(fileUrl(id), "_blank");
}

/** 示例问题，城市用顶栏选的当前城市；icon 是前面的双色图标 */
const city = currentCity();
const suggestions = [
  { text: `帮我规划${city} 4 天 3 晚行程`, icon: "calendar" },
  { text: `带父母去${city}，有哪些适合的景点？`, icon: "mountain" },
  { text: `${city}有哪些必吃的美食？`, icon: "food" },
  { text: `${city}这周天气怎么样？`, icon: "weather" },
  { text: `帮我找去${city}的机票`, icon: "plane" },
];

const wide = useWide();
const myInitial = ref("我");
api.me().then((u) => (myInitial.value = (u.nickname || u.username).slice(0, 1).toUpperCase())).catch(() => {});

/** 当前会话里最后一张行程卡，电脑上的右侧面板显示它；之后在对话里局部改过的，版本号用最新改动卡上的，面板会重新加载 */
const latestTrip = computed(() => {
  let changed: { tripId: number; version: number } | null = null;
  for (let i = messages.value.length - 1; i >= 0; i--) {
    for (const c of [...messages.value[i].cards].reverse()) {
      if (c.kind === "trip_change" && !changed) changed = c.data;
      if (c.kind === "trip") return changed && changed.tripId === c.data.tripId ? { ...c.data, version: changed.version } : c.data;
    }
  }
  return null;
});

/** 当前会话里最后一张方案卡（三套方案），右侧面板在还没有行程时显示它 */
const latestPlans = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const card = [...messages.value[i].cards].reverse().find((c) => c.kind === "plan_options");
    if (card) return card.data;
  }
  return null;
});

/**
 * 回答顶部的进度：路由完成算"理解需求"，每张卡片对应一步；还没结束时，工具报的实时进度（比价查到第几家）
 * 排在后面，没有实时进度时最后一步显示"处理中"。实时进度只在回答过程中显示，结束后由卡片那一步代替。
 */
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
    else if (c.kind === "trip_change") out.push({ title: "修改行程", detail: (d.changes || [])[0] || d.title, state: "done" });
    else if (c.kind === "plan_options") out.push({ title: "生成三套方案", detail: (d.options || []).map((o: any) => o.title).join("、"), state: "done" });
    else if (c.kind === "requirement") out.push({ title: "整理需求", detail: "请在卡片上确认", state: "done" });
  }
  for (const p of m.progress || []) if (p.id.startsWith("interject:")) out.push({ title: "收到补充，按补充调整", detail: p.detail, state: "done" });
  if (m.status === "waiting" || m.status === "streaming") {
    const live = (m.progress || []).filter((p) => !p.done);
    for (const p of live) out.push({ title: p.title, detail: p.detail, state: "doing" });
    if (!live.length) out.push({ title: m.cards.length ? "整理结果中…" : "正在查询…", state: "doing" });
  }
  return out.length > 1 || m.status !== "done" ? out : [];
}

/** 刚复制的是哪条回答，按钮显示"已复制"两秒 */
const copiedKey = ref<number | null>(null);

/** 复制一条回答：转成纯文本（去掉加粗、井号这些 Markdown 记号），粘到微信、备忘录里也干净。 */
function copyAnswer(m: UiMessage) {
  uni.setClipboardData({
    data: markdownToPlain(m.text),
    success: () => {
      copiedKey.value = m.key;
      setTimeout(() => {
        if (copiedKey.value === m.key) copiedKey.value = null;
      }, 2000);
    },
    fail: () => uni.showToast({ title: "复制失败，可以选中文字手动复制", icon: "none" }),
  });
}

function notYet(what: string) {
  uni.showToast({ title: `${what}还没接入`, icon: "none" });
}



const conversations = ref<Conversation[]>([]);
const currentId = ref<string | null>(null);
const messages = ref<UiMessage[]>([]);

/** 电脑上的右侧面板：会话里有行程或方案才显示，平时对话居中占满；用户收起后，来了新的行程或方案再自动展开 */
const panelClosed = ref(false);
const hasPanel = computed(() => !!(latestTrip.value || latestPlans.value?.options?.length));
const showPanel = computed(() => wide.value && hasPanel.value && !panelClosed.value);
watch([latestTrip, latestPlans], () => (panelClosed.value = false));

// 记下最近的方案，方案对比页从本地读
watch(latestPlans, (plans) => {
  if (plans) savePlans(plans, currentId.value);
});
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
onLoad(async (query) => {
  if (query?.id) {
    await open({ id: query.id } as Conversation);
  }
  if (query?.q) {
    send(decodeURIComponent(query.q));
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

/** 历史消息 metadata 里存的快捷回复 */
function suggestionsOf(m: ChatMessage): string[] {
  if (!m.metadata) return [];
  try {
    const meta = typeof m.metadata === "string" ? JSON.parse(m.metadata) : m.metadata;
    return Array.isArray(meta?.suggestions) ? meta.suggestions : [];
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
        ...(m.role === "USER" ? splitAttachments(m.content) : { text: m.content }),
        cards: cardsOf(m),
        suggestions: m.role === "ASSISTANT" ? suggestionsOf(m) : undefined,
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

/** 发一条消息并流式显示回答，第一条消息时先建会话；chip 表示点的是快捷回复（后端沿用当前技能、记下点了哪个）。 */
async function send(raw: string, files: Attached[] = [], chip = false) {
  const text = raw.trim();
  if (!text || sending.value) return;
  input.value = "";
  sending.value = true;
  messages.value.push({ key: ++seq, role: "user", text, cards: [], files });
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
      } else if (event.type === "progress" && event.progress?.id.startsWith("interject:")) {
        const added = messages.value.find((x) => x.addId === event.progress!.id.slice("interject:".length));
        if (added) added.addState = "seen";
        (answer.progress || (answer.progress = [])).push(event.progress);
      } else if (event.type === "progress" && event.progress) {
        const list = answer.progress || (answer.progress = []);
        const i = list.findIndex((p) => p.id === event.progress!.id);
        if (i >= 0) list[i] = event.progress;
        else list.push(event.progress);
      } else if (event.type === "suggestions") {
        answer.suggestions = event.suggestions || [];
        scrollToBottom();
      } else if (event.type === "error") {
        answer.status = "error";
        answer.error = event.message || "这次回答出错了";
      } else if (event.type === "done" && answer.status !== "error") {
        answer.status = "done";
      }
    }, controller.signal, { files: files.map((f) => f.id), chip });
    if (answer.status === "waiting" || answer.status === "streaming") {
      answer.status = "done";
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      answer.status = "stopped";
    } else {
      answer.status = "error";
      answer.error = e instanceof Error ? e.message : String(e);
      answer.quota = e instanceof ApiError && e.code === 402;
    }
  } finally {
    sending.value = false;
    controller = null;
    scrollToBottom();
    loadConversations();
    // 星络这一轮没来得及看到的补充：从原位置拿掉，合成一条正常发出去
    const left = messages.value.filter((x) => x.addState === "waiting");
    for (const x of messages.value) if (x.addState === "seen") x.addState = undefined;
    if (left.length) {
      messages.value = messages.value.filter((x) => x.addState !== "waiting");
      send(left.map((x) => x.text).join("\n"));
    }
  }
}

/** 回答过程中补充：插在这条回答前面显示，交给后端，星络下一次工具调用结束后看到。 */
async function interject() {
  const text = input.value.trim();
  if (!text || !sending.value) return;
  input.value = "";
  const addId = String(++seq);
  const at = messages.value.length - 1;
  messages.value.splice(at, 0, { key: ++seq, role: "user", text, cards: [], addState: "waiting", addId });
  scrollToBottom();
  try {
    // 会话还没建好时先留着，这轮结束后正常发送
    if (currentId.value) await api.interject(currentId.value, addId, text);
  } catch (e) {
    // 没交上去的同样留着，回答结束后正常发送
  }
}

/** 不等了：停掉当前回答，补充的话在回答结束时自动合成一条重新发 */
function restartNow() {
  stop();
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

/** 对话次数用完时去会员页 */
function openMember() {
  uni.navigateTo({ url: "/pages/member/member" });
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
.tb-btn { display: inline-flex; align-items: center; gap: 4px; }
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
.suggestion-text { flex: 1; min-width: 0; }

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
.status { color: var(--muted); font-size: 14px; }
.error {
  margin-top: 6px;
  color: var(--danger);
  background: var(--danger-soft);
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 14px;
}
.quota-link { margin-left: 8px; color: var(--accent); font-weight: 600; cursor: pointer; }
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
  overflow-y: auto;
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

/* 电脑：平时对话居中占满顶栏下面的空间；有行程或方案时左边对话、右边面板 */
.chat.wide { height: auto; flex: 1; min-height: 0; }
.chat.wide .main { background: var(--surface); }
.chat.wide.split .main { flex: 0 0 min(560px, 42%); border-right: 1px solid var(--line); }
.chat.wide.split .thread { padding-left: 16px; padding-right: 16px; }
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
.bot-name { font-size: 16px; font-weight: 800; color: var(--ink); }
.beta { font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 6px; background: var(--accent-soft); color: var(--accent); margin-left: 4px; }
.bot-sub { font-size: 12px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.greet-tip { margin-top: 6px; color: var(--muted); }
.me-avatar { width: 30px; height: 30px; border-radius: 50%; background: #f59e0b; color: #fff; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.msg.user { gap: 8px; align-items: flex-start; }
.composer-box { max-width: 760px; margin: 0 auto; border: 1px solid var(--line); border-radius: 14px; padding: 10px 12px 8px; background: var(--surface); }
.composer-box .composer-input { border: none; background: transparent; padding: 2px 0; width: 100%; }
.answer-actions { display: flex; gap: 6px; margin-top: -2px; }
.act-btn { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--muted); padding: 4px 8px; border-radius: 8px; cursor: pointer; }
.act-btn:hover { background: var(--surface-2); color: var(--ink); }
.act-btn.ok { color: var(--ok); }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip-btn { font-size: 13px; padding: 6px 12px; border-radius: 999px; border: 1px solid var(--line); background: var(--surface); color: var(--ink); cursor: pointer; }
.chip-btn.first { border-color: var(--accent); color: var(--accent); background: var(--accent-soft); }
.chip-btn:active { opacity: 0.7; }
.att-list { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.att { display: flex; align-items: center; gap: 8px; max-width: 100%; padding: 6px 8px 6px 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-2); }
.att.error { border-color: var(--price); }
.att-body { display: flex; flex-direction: column; min-width: 0; }
.att-name { font-size: 13px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px; }
.att-state { font-size: 11px; color: var(--muted); }
.att.error .att-state { color: var(--price); }
.att-x { padding: 4px; cursor: pointer; }
.u-col { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; max-width: 85%; min-width: 0; }
.u-col .bubble { max-width: 100%; }
.add-note { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 4px 10px; font-size: 12px; color: var(--muted); }
.add-note .link { cursor: pointer; }
.add-note .seen { display: inline-flex; align-items: center; gap: 3px; }
.b-files { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.b-file { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; padding: 3px 8px; border-radius: 8px; background: rgba(255, 255, 255, 0.18); cursor: pointer; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.composer-tools { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.tool { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--muted); padding: 4px 8px; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; }
.model { margin-left: auto; }
.composer-tools .send { margin-left: auto; }
.composer-tools .model + .send { margin-left: 0; }
.send { width: 38px; height: 38px; border-radius: 50%; background: var(--accent); display: flex; align-items: center; justify-content: center; cursor: pointer; }
.send.off { opacity: 0.45; cursor: default; }
.stop-btn { background: var(--danger); }
@media (prefers-reduced-motion: reduce) { .aside { transition: none; } }
</style>
