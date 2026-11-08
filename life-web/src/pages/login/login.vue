<template>
  <view class="login">
    <view class="topbar">
      <BrandMark :size="32" />
      <text class="logo-text">星络 Xinglo</text>
    </view>

    <view class="main">
      <!-- 左侧介绍：背景图放在 static/login/hero.jpg，没有就只显示渐变 -->
      <view class="intro">
        <view class="intro-head">
          <view class="title">星络</view>
          <view class="slogan"><text class="ai">AI</text> 帮你规划行程、比价预订、轻松出行</view>
        </view>
        <view class="features">
          <view v-for="f in FEATURES" :key="f.title" class="feature">
            <view class="f-icon"><Icon :name="f.icon" :size="22" color="var(--accent)" /></view>
            <view>
              <view class="f-title">{{ f.title }}</view>
              <view class="f-desc">{{ f.desc }}</view>
            </view>
          </view>
        </view>

        <view class="samples">
          <view class="sample-tag"><Icon name="sparkle" :size="14" color="var(--accent)" />西安 3 日游 · AI 行程示例</view>
          <view class="sample-row">
            <view class="card plan">
              <view class="card-title"><Icon name="calendar" :size="16" color="var(--accent)" />AI 行程推荐 · 西安 3 日游</view>
              <view v-for="d in SAMPLE_DAYS" :key="d.day" class="plan-day">
                <text class="day-chip">{{ d.day }}</text><text>{{ d.spots }}</text>
              </view>
            </view>
            <view class="mini-list">
              <view v-for="m in SAMPLE_TOOLS" :key="m.title" class="card mini">
                <view class="mini-icon" :class="m.tone"><Icon :name="m.icon" :size="18" color="currentColor" /></view>
                <view class="mini-body">
                  <view class="mini-title">{{ m.title }}</view>
                  <view class="mini-desc">{{ m.desc }}</view>
                </view>
              </view>
            </view>
          </view>
        </view>
      </view>

      <!-- 右侧卡片：登录、注册、找回密码三种状态 -->
      <view class="panel">
        <template v-if="view === 'reset'">
          <view class="panel-title">找回密码</view>
          <view class="panel-sub">用绑定的邮箱收验证码，设一个新密码</view>
        </template>
        <template v-else>
          <view class="panel-title">{{ view === "register" ? "注册星络" : "登录星络" }}</view>
          <view class="panel-sub">{{ view === "register" ? "用邮箱注册，验证码 10 分钟内有效" : "登录后可保存行程、继续编辑与查看预订信息" }}</view>
          <view class="tabs">
            <view class="tab" :class="{ on: view === 'login' }" @click="switchView('login')">登录</view>
            <view class="tab" :class="{ on: view === 'register' }" @click="switchView('register')">注册</view>
          </view>
        </template>

        <!-- 登录 -->
        <view v-if="view === 'login'" class="form">
          <view v-if="notice" class="ok-notice">{{ notice }}</view>
          <view class="field">
            <Icon name="user" :size="18" color="var(--muted)" />
            <input id="username" v-model="username" class="field-input" placeholder="用户名或邮箱" />
          </view>
          <view class="field">
            <Icon name="lock" :size="18" color="var(--muted)" />
            <input id="password" v-model="password" class="field-input" password placeholder="请输入密码" @confirm="submit" />
          </view>
        </view>

        <!-- 注册 -->
        <view v-else-if="view === 'register'" class="form">
          <view v-if="options && !options.open" class="notice">暂未开放注册，请联系管理员开通账号。</view>
          <view v-else-if="options && !options.mailReady" class="notice">暂时不能发邮件验证码，请稍后再试或联系管理员。</view>
          <view class="field">
            <Icon name="mail" :size="18" color="var(--muted)" />
            <input id="reg-email" v-model="email" class="field-input" placeholder="邮箱" />
          </view>
          <view class="field">
            <Icon name="shield" :size="18" color="var(--muted)" />
            <input id="reg-code" v-model="code" class="field-input" type="number" maxlength="6" placeholder="6 位验证码" />
            <view class="code-btn" :class="{ on: canSendCode }" @click="sendCode('REGISTER')">{{ codeBtnText }}</view>
          </view>
          <view v-if="codeHint" class="hint left">{{ codeHint }}</view>
          <view class="field">
            <Icon name="user" :size="18" color="var(--muted)" />
            <input id="reg-nickname" v-model="nickname" class="field-input" maxlength="32" placeholder="昵称（选填，星络这样称呼你）" />
          </view>
          <view class="field">
            <Icon name="lock" :size="18" color="var(--muted)" />
            <input id="reg-password" v-model="password" class="field-input" password placeholder="设置密码：8 位以上，字母加数字" @confirm="submit" />
          </view>
          <view v-if="passwordProblem(password)" class="hint left">{{ passwordProblem(password) }}</view>
        </view>

        <!-- 找回密码 -->
        <view v-else class="form">
          <view class="field">
            <Icon name="mail" :size="18" color="var(--muted)" />
            <input id="reset-email" v-model="email" class="field-input" placeholder="绑定的邮箱" />
          </view>
          <view class="field">
            <Icon name="shield" :size="18" color="var(--muted)" />
            <input id="reset-code" v-model="code" class="field-input" type="number" maxlength="6" placeholder="6 位验证码" />
            <view class="code-btn" :class="{ on: canSendCode }" @click="sendCode('RESET')">{{ codeBtnText }}</view>
          </view>
          <view v-if="codeHint" class="hint left">{{ codeHint }}</view>
          <view class="field">
            <Icon name="lock" :size="18" color="var(--muted)" />
            <input id="reset-password" v-model="password" class="field-input" password placeholder="新密码：8 位以上，字母加数字" />
          </view>
          <view class="field">
            <Icon name="lock" :size="18" color="var(--muted)" />
            <input id="reset-again" v-model="again" class="field-input" password placeholder="再输一遍新密码" @confirm="submit" />
          </view>
          <view v-if="resetProblem" class="hint left">{{ resetProblem }}</view>
        </view>

        <view v-if="view !== 'reset'" class="agree" @click="agreed = !agreed">
          <view class="box" :class="{ on: agreed }"><Icon v-if="agreed" name="check" :size="12" color="#fff" /></view>
          <text>我已阅读并同意</text>
          <text class="link" @click.stop="notReady('《用户协议》还没写，正式上线前补上')">《用户协议》</text>
          <text class="link" @click.stop="notReady('《隐私政策》还没写，正式上线前补上')">《隐私政策》</text>
        </view>

        <button class="submit" :disabled="!canSubmit" @click="submit">{{ submitText }}</button>
        <view v-if="error" class="error">{{ error }}</view>

        <view class="links">
          <template v-if="view === 'reset'">
            <text @click="switchView('login')">‹ 返回登录</text>
          </template>
          <template v-else>
            <text @click="switchView('reset')">忘记密码</text>
            <text class="sep">|</text>
            <text v-if="view === 'login'">还没有账号？<text class="link" @click="switchView('register')">注册</text></text>
            <text v-else>已有账号？<text class="link" @click="switchView('login')">去登录</text></text>
          </template>
        </view>

        <view class="trust">
          <view><Icon name="lock" :size="15" color="var(--muted)" />密码加密存储</view>
          <text class="sep">|</text>
          <view><Icon name="shield" :size="15" color="var(--muted)" />邮箱只用于登录和找回密码</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "../../api";
import { BACK_KEY, setToken } from "../../api/http";
import { clearMediaTicket } from "../../utils/media";
import Icon from "../../components/Icon.vue";
import BrandMark from "../../components/BrandMark.vue";
import { looksLikeEmail, passwordProblem } from "../../utils/password";

/** 左侧三条功能介绍 */
const FEATURES = [
  { icon: "calendar", title: "智能生成行程", desc: "基于兴趣与天数，AI 一键定制个性化旅行计划" },
  { icon: "plane", title: "机票酒店比价", desc: "携程、飞猪、美团、途牛多平台比价，按价格排序" },
  { icon: "pin", title: "地图天气美食一站式整合", desc: "行程路线、实时天气、顺路美食与景点门票" },
];
/** 示例行程（只是展示用，不带价格） */
const SAMPLE_DAYS = [
  { day: "Day 1", spots: "城墙 · 钟楼 · 回民街" },
  { day: "Day 2", spots: "大雁塔 · 陕西历史博物馆" },
  { day: "Day 3", spots: "大唐不夜城 · 盛唐文化体验" },
];
const SAMPLE_TOOLS = [
  { icon: "plane", tone: "blue", title: "机票比价", desc: "四个平台同一航班放一起比" },
  { icon: "bed", tone: "violet", title: "酒店比价", desc: "按离行程景点的远近推荐" },
  { icon: "food", tone: "orange", title: "美食推荐", desc: "按当天路线找顺路的店" },
];

/** 卡片现在是登录、注册还是找回密码 */
const view = ref<"login" | "register" | "reset">("login");
const username = ref("");
const password = ref("");
const email = ref("");
const code = ref("");
const nickname = ref("");
const again = ref("");
const agreed = ref(false);
const loading = ref(false);
const error = ref("");
/** 登录卡片上的绿色提示（比如"密码已重置"） */
const notice = ref("");
/** 注册是否开放、能不能发邮件；没查到时按开放处理，提交时后端会再拦 */
const options = ref<{ open: boolean; mailReady: boolean } | null>(null);
/** 验证码按钮：倒计时秒数、发送中、发到哪了 */
const countdown = ref(0);
const sending = ref(false);
const codeHint = ref("");
let timer: ReturnType<typeof setInterval> | null = null;

onLoad((q) => {
  if (q?.view === "register" || q?.view === "reset") view.value = q.view;
});
api.registerOptions().then((o) => (options.value = o)).catch(() => {});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});

/** 换卡片：清掉错误、验证码和密码，邮箱在登录、注册、找回密码之间沿用 */
function switchView(next: "login" | "register" | "reset") {
  if (view.value === next) return;
  if (next === "login" && looksLikeEmail(email.value) && !username.value) username.value = email.value.trim();
  if (next !== "login" && looksLikeEmail(username.value) && !email.value) email.value = username.value.trim();
  view.value = next;
  error.value = "";
  notice.value = "";
  code.value = "";
  codeHint.value = "";
  password.value = "";
  again.value = "";
}

const registerBlocked = computed(() => !!options.value && (!options.value.open || !options.value.mailReady));
const canSendCode = computed(() => !sending.value && countdown.value === 0 && looksLikeEmail(email.value)
  && !(view.value === "register" && registerBlocked.value));
const codeBtnText = computed(() => (sending.value ? "发送中…" : countdown.value ? `${countdown.value} 秒后重发` : "获取验证码"));

/** 找回密码时新密码哪里不对 */
const resetProblem = computed(() => {
  const p = passwordProblem(password.value);
  if (p) return p;
  if (password.value && again.value && again.value !== password.value) return "两次输入的新密码不一样";
  return "";
});

const canSubmit = computed(() => {
  if (loading.value) return false;
  if (view.value === "login") return !!username.value.trim() && !!password.value;
  const base = looksLikeEmail(email.value) && /^\d{6}$/.test(code.value.trim()) && !!password.value && !passwordProblem(password.value);
  if (view.value === "register") return base && !registerBlocked.value;
  return base && again.value === password.value;
});

const submitText = computed(() => {
  if (loading.value) return view.value === "login" ? "登录中…" : view.value === "register" ? "注册中…" : "提交中…";
  return view.value === "login" ? "登录" : view.value === "register" ? "注册并登录" : "重置密码";
});

function notReady(msg: string) {
  uni.showToast({ title: msg, icon: "none", duration: 2500 });
}

/** 要验证码：成功后开始倒计时，告诉用户发到哪了 */
async function sendCode(purpose: "REGISTER" | "RESET") {
  if (!canSendCode.value) {
    if (!looksLikeEmail(email.value)) error.value = "先填一个正确的邮箱";
    return;
  }
  sending.value = true;
  error.value = "";
  try {
    const r = await api.sendEmailCode(email.value.trim(), purpose);
    codeHint.value = `验证码已发到 ${email.value.trim()}，10 分钟内有效；没收到的话看看垃圾邮件`;
    startCountdown(r.cooldown || 60);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "验证码没发出去";
  } finally {
    sending.value = false;
  }
}

function startCountdown(seconds: number) {
  countdown.value = seconds;
  if (timer) clearInterval(timer);
  timer = setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0 && timer) {
      clearInterval(timer);
      timer = null;
    }
  }, 1000);
}

/** 提交当前卡片：登录、注册（成功直接进首页）或重置密码（成功回登录） */
async function submit() {
  if (!canSubmit.value) return;
  if (view.value !== "reset" && !agreed.value) {
    error.value = "请先勾选同意用户协议和隐私政策";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    if (view.value === "login") {
      finishLogin(await api.login(username.value.trim(), password.value));
    } else if (view.value === "register") {
      finishLogin(await api.register(email.value.trim(), code.value.trim(), password.value, nickname.value.trim()));
    } else {
      await api.resetPassword(email.value.trim(), code.value.trim(), password.value);
      const address = email.value.trim();
      switchView("login");
      username.value = address;
      notice.value = "密码已重置，用新密码登录";
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  } finally {
    loading.value = false;
  }
}

/** 登录或注册成功：保存 token，回到登录前的页面（同一个账号时），否则进首页 */
function finishLogin(result: { token: string; user: { id: number } }) {
  setToken(result.token);
  // 换了账号，旧的下载票据不能再用
  clearMediaTicket();
  const sameUser = uni.getStorageSync(LAST_USER_KEY) === String(result.user.id);
  forgetOtherUser(result.user.id);
  const back = uni.getStorageSync(BACK_KEY) as string;
  uni.removeStorageSync(BACK_KEY);
  uni.reLaunch({ url: sameUser && back && back.startsWith("/pages/") ? back : "/pages/chat/chat" });
}

const LAST_USER_KEY = "life_last_user";

/** 同一个浏览器换了账号：清掉上一个人留在本地的方案对比和出发地，免得新账号看到 */
function forgetOtherUser(userId: number) {
  if (uni.getStorageSync(LAST_USER_KEY) === String(userId)) return;
  uni.removeStorageSync("life_last_plans");
  uni.removeStorageSync("life_origin");
  uni.setStorageSync(LAST_USER_KEY, String(userId));
}
</script>

<style scoped>
.login {
  min-height: 100vh;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  background:
    url("/static/login/hero.jpg") left bottom / 62% auto no-repeat,
    linear-gradient(160deg, #eef4ff 0%, #dfeafc 45%, #f3f7ff 100%);
}
.topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px max(24px, env(safe-area-inset-left)) 14px max(24px, env(safe-area-inset-right));
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(8px);
}
.logo-text { font-size: 20px; font-weight: 800; color: #142046; }
.main {
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 460px;
  gap: 48px;
  align-items: center;
  width: 100%;
  max-width: 1360px;
  margin: 0 auto;
  padding: 32px max(24px, env(safe-area-inset-left)) 40px max(24px, env(safe-area-inset-right));
  box-sizing: border-box;
}

/* 左侧介绍 */
.intro { display: flex; flex-direction: column; gap: 32px; min-width: 0; }
.title { font-size: 56px; font-weight: 900; color: #0f1a3d; letter-spacing: 0.02em; line-height: 1.1; }
.slogan { margin-top: 10px; font-size: 24px; font-weight: 700; font-style: italic; color: #142046; }
.slogan .ai { color: var(--accent); font-size: 30px; font-weight: 900; }
.features { display: flex; flex-direction: column; gap: 20px; }
.feature { display: flex; align-items: center; gap: 14px; }
.f-icon { width: 52px; height: 52px; border-radius: 14px; background: rgba(255, 255, 255, 0.85); box-shadow: 0 4px 14px rgba(37, 99, 235, 0.1); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.f-title { font-size: 18px; font-weight: 800; color: #142046; }
.f-desc { margin-top: 3px; font-size: 14px; color: #4b5874; }

.samples { display: flex; flex-direction: column; gap: 12px; }
.sample-tag { align-self: flex-start; display: flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 999px; background: rgba(255, 255, 255, 0.85); font-size: 14px; font-weight: 700; color: #1d3a8a; }
.sample-row { display: flex; gap: 14px; align-items: stretch; flex-wrap: wrap; }
.card { background: rgba(255, 255, 255, 0.88); border-radius: 16px; box-shadow: 0 8px 24px rgba(20, 40, 90, 0.1); backdrop-filter: blur(6px); }
.plan { flex: 1 1 300px; padding: 16px 18px; display: flex; flex-direction: column; gap: 10px; }
.card-title { display: flex; align-items: center; gap: 6px; font-size: 15px; font-weight: 800; color: #142046; }
.plan-day { display: flex; align-items: center; gap: 10px; font-size: 14px; color: #33405c; }
.day-chip { padding: 2px 8px; border-radius: 6px; background: var(--accent-soft); color: var(--accent); font-size: 12px; font-weight: 700; }
.mini-list { flex: 1 1 240px; display: flex; flex-direction: column; gap: 10px; }
.mini { display: flex; align-items: center; gap: 12px; padding: 10px 14px; }
.mini-icon { width: 34px; height: 34px; border-radius: 10px; display: flex; align-items: center; justify-content: center; }
.mini-icon.blue { background: #e6eefd; color: #2563eb; }
.mini-icon.violet { background: #efeafd; color: #6d4fd8; }
.mini-icon.orange { background: #fdf0e3; color: #e8590c; }
.mini-title { font-size: 14px; font-weight: 800; color: #142046; }
.mini-desc { font-size: 12px; color: #5b6782; }

/* 右侧登录卡片 */
.panel {
  background: #fff;
  border-radius: 22px;
  box-shadow: 0 18px 48px rgba(20, 40, 90, 0.14);
  padding: 40px 40px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: #1b2333;
}
.panel-title { text-align: center; font-size: 30px; font-weight: 900; color: #0f1a3d; }
.panel-sub { text-align: center; font-size: 14px; color: #5b6782; margin-top: -6px; }
.tabs { display: grid; grid-template-columns: 1fr 1fr; background: #f1f4f9; border-radius: 12px; padding: 4px; margin-top: 8px; }
.tab { text-align: center; padding: 10px 0; font-size: 15px; font-weight: 600; color: #33405c; border-radius: 9px; cursor: pointer; }
.tab.on { background: #fff; color: var(--accent); font-weight: 800; box-shadow: inset 0 -3px 0 var(--accent); }
.form { display: flex; flex-direction: column; gap: 14px; }
.notice { font-size: 13px; color: #9a5b12; background: #fdf3e3; border-radius: 8px; padding: 8px 12px; }
.field { display: flex; align-items: center; gap: 10px; height: 52px; padding: 0 12px 0 14px; border: 1px solid #dfe4ee; border-radius: 10px; background: #fff; }
.field:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.field.disabled { background: #f7f8fb; }
.field-input { flex: 1; min-width: 0; height: 100%; font-size: 15px; color: #1b2333; background: transparent; border: none; }
.code-btn { padding: 6px 12px; border: 1px solid #c9d6f3; border-radius: 8px; color: #9aa6bf; font-size: 14px; font-weight: 600; white-space: nowrap; }
.code-btn.on { color: var(--accent); border-color: var(--accent); cursor: pointer; }
.ok-notice { font-size: 13px; color: #17703a; background: #e7f6ec; border-radius: 8px; padding: 8px 12px; }
.agree { display: flex; align-items: center; flex-wrap: wrap; gap: 2px; font-size: 13px; color: #4b5874; cursor: pointer; }
.box { width: 16px; height: 16px; border: 1px solid #b9c3d6; border-radius: 4px; margin-right: 6px; display: flex; align-items: center; justify-content: center; }
.box.on { background: var(--accent); border-color: var(--accent); }
.link { color: var(--accent); cursor: pointer; }
.submit { height: 52px; line-height: 52px; border-radius: 12px; background: var(--accent); color: #fff; font-size: 18px; font-weight: 800; border: none; margin: 4px 0 0; }
.submit[disabled] { background: #9db8f2; color: #fff; }
.error { color: var(--danger); font-size: 14px; text-align: center; }
.links { display: flex; justify-content: center; align-items: center; gap: 24px; font-size: 14px; color: #4b5874; }
.links > text { cursor: pointer; }
.sep { color: #d3d9e4; }
.hint { text-align: center; font-size: 13px; color: #5b6782; }
.hint.left { text-align: left; margin-top: -6px; }
.trust { display: flex; justify-content: center; align-items: center; flex-wrap: wrap; gap: 16px; padding: 12px; margin-top: 4px; background: #f6f8fc; border-radius: 12px; font-size: 13px; color: #5b6782; }
.trust > view { display: flex; align-items: center; gap: 6px; }

/* 窄屏：只留登录卡片，介绍收成标题 */
@media (max-width: 960px) {
  .login { background: linear-gradient(160deg, #eef4ff 0%, #dfeafc 45%, #f3f7ff 100%); }
  .main { grid-template-columns: minmax(0, 1fr); gap: 20px; padding-top: 20px; }
  .features, .samples { display: none; }
  .title { font-size: 34px; }
  .slogan { font-size: 17px; }
  .slogan .ai { font-size: 20px; }
  .panel { padding: 28px 20px 20px; }
  .panel-title { font-size: 24px; }
  .trust .sep { display: none; }
}
</style>
