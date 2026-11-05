<template>
  <Shell active="">
  <view class="container">
    <view v-if="user" class="card account">
      <view>
        <view class="name">{{ user.nickname || user.username }}</view>
        <view class="muted small">{{ user.username }} · {{ user.timezone }}</view>
      </view>
      <button class="btn ghost small" @click="logout">退出登录</button>
    </view>

    <view class="card links">
      <view class="link-row" @click="go('/pages/member/member')">
        <text>星络会员</text>
        <text class="muted">{{ member ? (member.active ? `有效期至 ${memberDate} ›` : `今天还能聊 ${Math.max(0, member.dailyLimit - member.todayUsed)} 次 · 开通会员 ›`) : "›" }}</text>
      </view>
      <view class="link-row" @click="goSchedule"><text>我的日程和提醒</text><text class="muted">›</text></view>
      <view class="link-row" @click="go('/pages/chat/chat')"><text>对话记录</text><text class="muted">›</text></view>
      <view class="link-row" @click="mail.open = !mail.open">
        <text>{{ user?.email ? "邮箱" : "绑定邮箱" }}</text>
        <text class="muted">{{ mail.open ? "收起" : user?.email ? `${user.email} · 换绑 ›` : "绑了能用邮箱登录、找回密码 ›" }}</text>
      </view>
      <view class="link-row" @click="pw.open = !pw.open"><text>修改密码</text><text class="muted">{{ pw.open ? "收起" : "›" }}</text></view>
    </view>

    <!-- 绑定邮箱：收验证码确认是自己的邮箱，绑好后能用邮箱登录和找回密码 -->
    <view v-if="mail.open" class="card stack">
      <input id="bind-email" v-model="mail.email" class="input" placeholder="邮箱" />
      <view class="row">
        <input id="bind-code" v-model="mail.code" class="input grow" type="number" maxlength="6" placeholder="6 位验证码" />
        <button class="btn ghost small" :disabled="!canSendMail" @click="sendBindCode">{{ mail.sending ? "发送中…" : mail.countdown ? `${mail.countdown} 秒后重发` : "获取验证码" }}</button>
      </view>
      <view v-if="mail.hint" class="muted small">{{ mail.hint }}</view>
      <view class="row">
        <button class="btn small" :disabled="!canBind" @click="bindEmail">{{ mail.saving ? "绑定中…" : "绑定" }}</button>
        <button class="btn ghost small" @click="closeMail">取消</button>
      </view>
    </view>

    <!-- 修改密码：要原密码，改完其他设备上的登录会退出 -->
    <view v-if="pw.open" class="card stack">
      <input id="pw-old" v-model="pw.old" class="input" password placeholder="原密码" />
      <input id="pw-new" v-model="pw.next" class="input" password placeholder="新密码：8 到 64 位，同时有字母和数字" />
      <input id="pw-again" v-model="pw.again" class="input" password placeholder="再输一遍新密码" />
      <view v-if="pwProblem" class="muted small">{{ pwProblem }}</view>
      <view class="row">
        <button class="btn small" :disabled="!!pwProblem || !pw.old || !pw.next || pw.saving" @click="changePassword">{{ pw.saving ? "保存中…" : "改密码" }}</button>
        <button class="btn ghost small" @click="closePassword">取消</button>
      </view>
    </view>

    <!-- 家庭地址：首页今日安排按它算几点出发 -->
    <view class="section-title">常用地址</view>
    <view class="card home-addr">
      <view class="muted small">设了家的地址，首页"今日安排"会按从家出发算路上多久、建议几点出发；旅行中按当晚住的酒店算。星络对话时也会知道。</view>
      <view class="add-memory">
        <text class="addr-label">家</text>
        <input id="home-address" v-model="homeAddress" class="input grow" placeholder="比如：深圳市南山区科技园某某小区" />
        <button class="btn small" :disabled="savingAddress" @click="saveAddress">{{ savingAddress ? "保存中…" : "保存" }}</button>
      </view>
    </view>

    <!-- 记忆 -->
    <view class="section-title">助手记住的事</view>
    <view class="card">
      <view v-if="!memories.length" class="muted small">还没有记忆。对话里说"记住：我不吃辣"，或者在下面添加。</view>
      <view v-for="m in memories" :key="m.id" class="memory">
        <text class="chip">{{ CATEGORY[m.category] }}</text>
        <view class="memory-body">
          <view>{{ m.content }}</view>
          <view class="muted small">
            {{ m.source === "USER_STATED" ? "你说的" : m.source === "AI_INFERRED" ? "助手推断" : "手动添加" }}
            <text v-if="m.sensitive"> · 加密保存，不发给模型</text>
          </view>
        </view>
        <text class="del" @click="removeMemory(m)">删除</text>
      </view>
      <view class="add-memory">
        <picker :range="categoryLabels" :value="memoryForm.categoryIndex"
                @change="memoryForm.categoryIndex = Number($event.detail.value)">
          <view class="input picker">{{ categoryLabels[memoryForm.categoryIndex] }}</view>
        </picker>
        <input id="memory-content" v-model="memoryForm.content" class="input grow" placeholder="比如：海鲜过敏" />
        <button class="btn small" :disabled="!memoryForm.content.trim()" @click="addMemory">添加</button>
      </view>
    </view>

    <!-- Bark 推送 -->
    <view class="section-title">iPhone 推送（Bark）</view>
    <view class="card stack">
      <view class="muted small">在 iPhone 上装 Bark，把 App 首页链接里的那段 Key 填到这里，日程提醒就会推到手机上。</view>
      <view class="row">
        <input id="bark-key" v-model="barkKey" class="input grow" placeholder="Bark 设备 Key" />
        <button class="btn small" :disabled="!barkKey.trim()" @click="saveBark">保存</button>
      </view>
      <button class="btn ghost small self-start" @click="testPush">发一条测试推送</button>
    </view>

    <!-- 每日简报 -->
    <view class="section-title">每日简报</view>
    <view class="card stack">
      <view class="muted small">每天早上推一条：天气、今天的日程、快出发的行程（倒计时、目的地天气、还没订的机票酒店）。内容直接来自数据，不是 AI 写的。</view>
      <view class="row between">
        <text>每天推送</text>
        <switch :checked="brief.enabled" color="#2563EB" @change="toggleBrief" />
      </view>
      <view class="row between">
        <text>推送时间</text>
        <picker mode="time" :value="brief.sendTime" @change="brief.sendTime = $event.detail.value; saveBrief()">
          <view class="input picker">{{ brief.sendTime }}</view>
        </picker>
      </view>
      <view class="row">
        <input id="brief-city" v-model="brief.city" class="input grow" :placeholder="brief.suggestedCity ? `看天气的城市（不填用 ${brief.suggestedCity}）` : '看天气的城市，比如：深圳'" />
        <button class="btn small" @click="saveBrief">保存</button>
      </view>
      <view v-if="!brief.hasChannel" class="small warn-t">还没填 Bark Key，填好上面的推送才能收到</view>
      <view v-if="todayBrief" class="brief">
        <view class="b-title">{{ todayBrief.title }}</view>
        <view v-for="s in todayBrief.sections" :key="s.key" class="b-sec" @click="s.url && go(s.url)">
          <view class="b-head">{{ s.title }}</view>
          <view v-for="l in s.lines" :key="l" class="b-line">{{ l }}</view>
        </view>
        <view v-if="!todayBrief.sections.length" class="muted small">今天没有日程和行程</view>
      </view>
      <button class="btn ghost small self-start" :disabled="!brief.hasChannel || pushing" @click="pushBrief">{{ pushing ? "推送中…" : "现在推一条试试" }}</button>
    </view>

    <!-- 日历订阅 -->
    <view class="section-title">日历订阅</view>
    <view class="card stack">
      <view class="muted small">
        iPhone：设置 → 日历 → 账户 → 添加账户 → 其他 → 添加已订阅的日历，填下面的地址。地址要用部署后的公网域名。
      </view>
      <view v-if="feedUrl" class="feed">
        <text class="feed-url">{{ feedUrl }}</text>
        <button class="btn small" @click="copy(feedUrl)">复制</button>
        <view class="muted small">这个地址只显示这一次，关掉页面就看不到了。</view>
      </view>
      <button class="btn ghost small self-start" @click="createFeed">生成订阅地址</button>
      <view v-for="t in tokens" :key="t.id" class="token">
        <text>{{ t.name }}</text>
        <text class="muted small mono">{{ t.prefix }}…</text>
        <text class="del" @click="revoke(t)">吊销</text>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import Shell from "../../components/Shell.vue";
import { computed, reactive, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useWide } from "../../utils/screen";
import { api } from "../../api";
import { showError, toLogin } from "../../api/http";
import { looksLikeEmail, passwordProblem } from "../../utils/password";
import type { Brief, BriefSettings, MembershipView, MemoryCategory, MemoryItem, TokenView, UserView } from "../../api/types";

const CATEGORY: Record<MemoryCategory, string> = {
  PROFILE: "基本信息",
  PREFERENCE: "偏好",
  RELATION: "家人朋友",
  HEALTH: "健康",
  FINANCE: "财务",
};
const categories = Object.keys(CATEGORY) as MemoryCategory[];
const categoryLabels = categories.map((c) => CATEGORY[c]);

const user = ref<UserView | null>(null);
/** 会员状态，"我的"第一行显示 */
const member = ref<MembershipView | null>(null);
const memberDate = computed(() => {
  const d = member.value?.expiresAt ? new Date(member.value.expiresAt) : null;
  return d ? `${d.getMonth() + 1} 月 ${d.getDate()} 日` : "";
});

/** 修改密码表单 */
const pw = reactive({ open: false, old: "", next: "", again: "", saving: false });

/** 新密码哪里不合规（和后端规则一致），没填时为空 */
const pwProblem = computed(() => {
  if (!pw.next) return "";
  const p = passwordProblem(pw.next);
  if (p) return "新" + p;
  if (pw.again && pw.again !== pw.next) return "两次输入的新密码不一样";
  if (!pw.again) return "再输一遍新密码";
  return "";
});

/** 绑定邮箱表单：倒计时秒数、发送中、发到哪了 */
const mail = reactive({ open: false, email: "", code: "", sending: false, saving: false, countdown: 0, hint: "" });
let mailTimer: ReturnType<typeof setInterval> | null = null;
const canSendMail = computed(() => !mail.sending && mail.countdown === 0 && looksLikeEmail(mail.email));
const canBind = computed(() => !mail.saving && looksLikeEmail(mail.email) && /^\d{6}$/.test(mail.code.trim()));

function closeMail() {
  Object.assign(mail, { open: false, email: "", code: "", hint: "" });
}

/** 给要绑定的邮箱发验证码，成功后倒计时 */
async function sendBindCode() {
  if (!canSendMail.value) return;
  mail.sending = true;
  try {
    const r = await api.sendEmailCode(mail.email.trim(), "BIND");
    mail.hint = `验证码已发到 ${mail.email.trim()}，10 分钟内有效；没收到的话看看垃圾邮件`;
    mail.countdown = r.cooldown || 60;
    if (mailTimer) clearInterval(mailTimer);
    mailTimer = setInterval(() => {
      mail.countdown -= 1;
      if (mail.countdown <= 0 && mailTimer) {
        clearInterval(mailTimer);
        mailTimer = null;
      }
    }, 1000);
  } catch (e) {
    showError(e);
  } finally {
    mail.sending = false;
  }
}

/** 用验证码绑定邮箱，绑好后账号卡片上显示新邮箱 */
async function bindEmail() {
  if (!canBind.value) return;
  mail.saving = true;
  try {
    user.value = await api.bindEmail(mail.email.trim(), mail.code.trim());
    closeMail();
    uni.showToast({ title: "邮箱绑好了，以后可以用邮箱登录", icon: "none", duration: 2500 });
  } catch (e) {
    showError(e);
  } finally {
    mail.saving = false;
  }
}

function closePassword() {
  Object.assign(pw, { open: false, old: "", next: "", again: "" });
}

async function changePassword() {
  if (pwProblem.value || !pw.old || !pw.next) return;
  pw.saving = true;
  try {
    await api.changePassword(pw.old, pw.next);
    closePassword();
    uni.showToast({ title: "密码已改好，其他设备上的登录已退出", icon: "none", duration: 2500 });
  } catch (e) {
    showError(e);
  } finally {
    pw.saving = false;
  }
}
const memories = ref<MemoryItem[]>([]);
const tokens = ref<TokenView[]>([]);
const barkKey = ref("");
const feedUrl = ref("");
const memoryForm = reactive({ categoryIndex: 1, content: "" });
const brief = reactive<BriefSettings>({ enabled: true, sendTime: "07:30", city: "", hasChannel: false });
const todayBrief = ref<Brief | null>(null);
const pushing = ref(false);
const wide = useWide();
const homeAddress = ref("");
const savingAddress = ref(false);

/** 读简报设置和今天的内容；简报出错不影响页面其他部分 */
async function loadBrief() {
  try {
    const [s, b] = await Promise.all([api.briefSettings(), api.brief()]);
    Object.assign(brief, s, { city: s.city || "" });
    todayBrief.value = b;
  } catch (e) {
    showError(e);
  }
}

function toggleBrief(e: any) {
  brief.enabled = e.detail.value;
  saveBrief();
}

async function saveBrief() {
  try {
    const s = await api.saveBriefSettings({ enabled: brief.enabled, sendTime: brief.sendTime, city: brief.city?.trim() || null });
    Object.assign(brief, s, { city: s.city || "" });
    todayBrief.value = await api.brief();
    uni.showToast({ title: "已保存", icon: "success" });
  } catch (e) {
    showError(e);
  }
}

async function pushBrief() {
  pushing.value = true;
  try {
    todayBrief.value = await api.pushBrief();
    uni.showToast({ title: "已推送，看看手机", icon: "none" });
  } catch (e) {
    showError(e);
  } finally {
    pushing.value = false;
  }
}

onShow(async () => {
  try {
    const [me, mems, toks] = await Promise.all([api.me(), api.memories(), api.tokens()]);
    user.value = me;
    memories.value = mems;
    tokens.value = toks.filter((t) => t.scope === "CALENDAR_FEED");
  } catch (e) {
    showError(e);
  }
  loadBrief();
  api.homeAddress().then((r) => (homeAddress.value = r.address || "")).catch(() => {});
  api.membership().then((m) => (member.value = m)).catch(() => {});
});

/** 保存家的地址（高德找不到会提示换个写法），清空表示删掉；记忆列表跟着刷新 */
async function saveAddress() {
  savingAddress.value = true;
  try {
    const r = await api.saveHomeAddress(homeAddress.value.trim());
    homeAddress.value = r.address || "";
    uni.showToast({ title: r.address ? "已保存" : "已删除", icon: "success" });
    memories.value = await api.memories();
  } catch (e) {
    showError(e);
  } finally {
    savingAddress.value = false;
  }
}

/** 日程页现在是底部标签页：手机用 switchTab，电脑上整页切换 */
function goSchedule() {
  if (wide.value) uni.reLaunch({ url: "/pages/schedule/schedule" });
  else uni.switchTab({ url: "/pages/schedule/schedule" });
}

/** 手动添加一条记忆；键用时间戳生成，同类同键才会覆盖，所以不会冲掉别的记忆。 */
async function addMemory() {
  try {
    await api.saveMemory({
      category: categories[memoryForm.categoryIndex],
      key: `manual_${Date.now()}`,
      content: memoryForm.content.trim(),
    });
    memoryForm.content = "";
    memories.value = await api.memories();
  } catch (e) {
    showError(e);
  }
}

function removeMemory(m: MemoryItem) {
  confirm("删除记忆", `删除"${m.content}"？`, async () => {
    await api.deleteMemory(m.id);
    memories.value = await api.memories();
  });
}

async function saveBark() {
  try {
    await api.configureBark(barkKey.value.trim());
    uni.showToast({ title: "已保存", icon: "success" });
    brief.hasChannel = true;
  } catch (e) {
    showError(e);
  }
}

async function testPush() {
  try {
    await api.testNotify();
    uni.showToast({ title: "已发送，看看手机", icon: "none" });
  } catch (e) {
    showError(e);
  }
}

/** 生成日历订阅用的个人令牌，地址只在这次显示。 */
async function createFeed() {
  try {
    const token = await api.createToken("iPhone 日历", "CALENDAR_FEED");
    feedUrl.value = `${location.origin}/api/v1/calendar/feed/${token.plainKey}.ics`;
    tokens.value = (await api.tokens()).filter((t) => t.scope === "CALENDAR_FEED");
  } catch (e) {
    showError(e);
  }
}

/** 吊销日历订阅地址。 */
function revoke(t: TokenView) {
  confirm("吊销订阅地址", "吊销后用这个地址订阅的日历会停止同步。", async () => {
    await api.revokeToken(t.id);
    tokens.value = tokens.value.filter((x) => x.id !== t.id);
  });
}

function copy(text: string) {
  uni.setClipboardData({ data: text });
}

async function logout() {
  try {
    await api.logout();
  } finally {
    toLogin();
  }
}

/** 二次确认后执行，出错弹提示。 */
function confirm(title: string, content: string, action: () => Promise<void>) {
  uni.showModal({
    title,
    content,
    confirmText: "确定",
    confirmColor: "#B4442F",
    success: async (res) => {
      if (!res.confirm) return;
      try {
        await action();
      } catch (e) {
        showError(e);
      }
    },
  });
}

/** 打开日程、对话等二级页面。 */
function go(url: string) {
  uni.navigateTo({ url });
}
</script>

<style scoped>
.account { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.account .btn { margin: 0; }
.name { font-size: 17px; font-weight: 600; }
.small { font-size: 13px; }
.mono { font-family: var(--mono); }
.memory {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}
.memory .chip { margin-top: 2px; }
.memory-body { flex: 1; min-width: 0; word-break: break-word; }
.add-memory { display: flex; flex-wrap: wrap; gap: 8px; padding-top: 12px; align-items: center; }
.add-memory .btn { margin: 0; }
.picker { display: flex; align-items: center; min-width: 96px; }
.grow { flex: 1; min-width: 140px; }
.stack { display: flex; flex-direction: column; gap: 10px; }
.stack .btn { margin: 0; }
.self-start { align-self: flex-start; }
.row { display: flex; gap: 8px; align-items: center; }
.row .btn { margin: 0; }
.between { justify-content: space-between; }
.warn-t { color: var(--price); }
.brief { background: var(--surface-2); border-radius: 10px; padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.b-title { font-weight: 600; font-size: 14px; }
.b-sec { cursor: pointer; }
.b-head { font-size: 12px; color: var(--muted); margin-bottom: 2px; }
.b-line { font-size: 13px; line-height: 1.6; color: var(--ink); }
.feed { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; background: var(--surface-2); padding: 10px; border-radius: 8px; }
.feed .btn { margin: 0; }
.feed-url { flex: 1; min-width: 0; font-family: var(--mono); font-size: 12px; word-break: break-all; }
.token { display: flex; align-items: center; gap: 10px; font-size: 14px; }
.del { margin-left: auto; font-size: 13px; color: var(--muted); cursor: pointer; flex-shrink: 0; }
.del:hover { color: var(--danger); }
.links { padding: 0 16px; margin-top: 12px; }
.addr-label { font-weight: 700; }
.link-row { display: flex; justify-content: space-between; padding: 13px 0; border-bottom: 1px solid var(--line); cursor: pointer; }
.link-row:last-child { border-bottom: none; }
@media (min-width: 1024px) {
  .page, .container { max-width: 1180px; padding: 24px 28px; }
  .list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
}
</style>
