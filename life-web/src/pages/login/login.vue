<template>
  <view class="login">
    <view class="topbar">
      <BrandMark :size="34" />
      <text class="logo-text">星络</text>
      <text class="logo-sep" />
      <text class="logo-slogan">你的 AI 旅行管家</text>
    </view>

    <view class="main">
      <view class="login-card">
        <!-- 左侧品牌：一句话说清星络做什么，配旅行插画；手机上缩成卡片顶部的横幅 -->
        <view class="hero">
          <view class="hero-title">分散的旅行灵感，<br />在这里成为行程。</view>
          <view class="hero-pic"><image class="hero-img" src="/static/login/hero.jpg" mode="widthFix" /></view>
          <view class="hero-sub">从路线规划到酒店比价，安排好每一程。</view>
        </view>

        <!-- 右侧表单：登录、注册、找回密码三种状态，注册和找回密码从底部入口进 -->
        <view class="panel">
          <view class="panel-title">{{ TITLES[view].title }}</view>
          <view class="panel-sub">{{ TITLES[view].sub }}</view>

          <!-- 登录 -->
          <view v-if="view === 'login'" class="form">
            <view v-if="notice" class="ok-notice">{{ notice }}</view>
            <view class="label">账号</view>
            <view class="field">
              <Icon name="user" :size="18" color="#5b6782" />
              <input id="username" v-model="username" class="field-input" placeholder="用户名或邮箱" />
            </view>
            <view class="label">密码</view>
            <view class="field">
              <Icon name="lock" :size="18" color="#5b6782" />
              <input id="password" v-model="password" class="field-input" :password="!showPwd" placeholder="请输入密码" @confirm="submit" />
              <view class="eye" :class="{ on: showPwd }" @click="showPwd = !showPwd">{{ showPwd ? "隐藏" : "显示" }}</view>
            </view>
            <view class="forgot"><text class="link" @click="switchView('reset')">忘记密码？</text></view>
          </view>

          <!-- 注册 -->
          <view v-else-if="view === 'register'" class="form">
            <view v-if="options && !options.open" class="notice">暂未开放注册，请联系管理员开通账号。</view>
            <view v-else-if="options && !options.mailReady" class="notice">暂时不能发邮件验证码，请稍后再试或联系管理员。</view>
            <view class="label">邮箱</view>
            <view class="field">
              <Icon name="mail" :size="18" color="#5b6782" />
              <input id="reg-email" v-model="email" class="field-input" placeholder="用来登录和找回密码" />
            </view>
            <view class="label">验证码</view>
            <view class="field">
              <Icon name="shield" :size="18" color="#5b6782" />
              <input id="reg-code" v-model="code" class="field-input" type="number" maxlength="6" placeholder="6 位验证码" />
              <view class="code-btn" :class="{ on: canSendCode }" @click="sendCode('REGISTER')">{{ codeBtnText }}</view>
            </view>
            <view v-if="codeHint" class="hint">{{ codeHint }}</view>
            <view class="label">昵称<text class="optional">（选填，星络这样称呼你）</text></view>
            <view class="field">
              <Icon name="user" :size="18" color="#5b6782" />
              <input id="reg-nickname" v-model="nickname" class="field-input" maxlength="32" placeholder="比如：小星" />
            </view>
            <view class="label">密码</view>
            <view class="field">
              <Icon name="lock" :size="18" color="#5b6782" />
              <input id="reg-password" v-model="password" class="field-input" :password="!showPwd" placeholder="8 位以上，字母加数字" @confirm="submit" />
              <view class="eye" :class="{ on: showPwd }" @click="showPwd = !showPwd">{{ showPwd ? "隐藏" : "显示" }}</view>
            </view>
            <view v-if="passwordProblem(password)" class="hint">{{ passwordProblem(password) }}</view>
          </view>

          <!-- 找回密码 -->
          <view v-else class="form">
            <view class="label">邮箱</view>
            <view class="field">
              <Icon name="mail" :size="18" color="#5b6782" />
              <input id="reset-email" v-model="email" class="field-input" placeholder="绑定的邮箱" />
            </view>
            <view class="label">验证码</view>
            <view class="field">
              <Icon name="shield" :size="18" color="#5b6782" />
              <input id="reset-code" v-model="code" class="field-input" type="number" maxlength="6" placeholder="6 位验证码" />
              <view class="code-btn" :class="{ on: canSendCode }" @click="sendCode('RESET')">{{ codeBtnText }}</view>
            </view>
            <view v-if="codeHint" class="hint">{{ codeHint }}</view>
            <view class="label">新密码</view>
            <view class="field">
              <Icon name="lock" :size="18" color="#5b6782" />
              <input id="reset-password" v-model="password" class="field-input" :password="!showPwd" placeholder="8 位以上，字母加数字" />
              <view class="eye" :class="{ on: showPwd }" @click="showPwd = !showPwd">{{ showPwd ? "隐藏" : "显示" }}</view>
            </view>
            <view class="field">
              <Icon name="lock" :size="18" color="#5b6782" />
              <input id="reset-again" v-model="again" class="field-input" :password="!showPwd" placeholder="再输一遍新密码" @confirm="submit" />
            </view>
            <view v-if="resetProblem" class="hint">{{ resetProblem }}</view>
          </view>

          <view v-if="view !== 'reset'" class="agree" @click="agreed = !agreed">
            <view class="box" :class="{ on: agreed }"><Icon v-if="agreed" name="check" :size="12" color="#fff" /></view>
            <text>我已阅读并同意</text>
            <text class="link" @click.stop="notReady('《用户协议》还没写，正式上线前补上')">《用户协议》</text>
            <text>和</text>
            <text class="link" @click.stop="notReady('《隐私政策》还没写，正式上线前补上')">《隐私政策》</text>
          </view>

          <button class="submit" :disabled="!canSubmit" @click="submit">{{ submitText }}</button>
          <view v-if="error" class="error">{{ error }}</view>

          <view class="switch">
            <text v-if="view === 'login'">还没有账号？<text class="link" @click="switchView('register')">立即注册</text></text>
            <text v-else-if="view === 'register'">已有账号？<text class="link" @click="switchView('login')">去登录</text></text>
            <text v-else class="link" @click="switchView('login')">‹ 返回登录</text>
          </view>
        </view>
      </view>
    </view>

    <view class="footer">星络 · 你的 AI 旅行管家</view>
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

/** 三种状态各自的标题和副标题 */
const TITLES = {
  login: { title: "欢迎回到星络", sub: "登录，继续规划你的下一段旅程。" },
  register: { title: "注册星络", sub: "用邮箱注册，验证码 10 分钟内有效。" },
  reset: { title: "找回密码", sub: "用绑定的邮箱收验证码，设一个新密码。" },
} as const;

/** 卡片现在是登录、注册还是找回密码 */
const view = ref<"login" | "register" | "reset">("login");
const username = ref("");
const password = ref("");
const email = ref("");
const code = ref("");
const nickname = ref("");
const again = ref("");
const agreed = ref(false);
/** 密码框是否显示明文 */
const showPwd = ref(false);
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
  showPwd.value = false;
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
/* 版式：顶栏品牌 + 居中一张大卡片（左旅行插画、右表单），底部一行品牌字；手机上插画缩成卡片顶部横幅 */
.login {
  min-height: 100vh;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  background: #f7f9fc;
  color: #1b2333;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px max(32px, env(safe-area-inset-left)) 8px max(32px, env(safe-area-inset-right));
}
.logo-text { font-size: 24px; font-weight: 800; color: #0f1a3d; letter-spacing: 0.04em; }
.logo-sep { width: 1px; height: 18px; background: #c9d1df; margin: 0 6px; }
.logo-slogan { font-size: 16px; color: #4b5874; }
.main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px max(24px, env(safe-area-inset-left)) 24px max(24px, env(safe-area-inset-right));
}
.login-card {
  width: 100%;
  max-width: 1180px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.86fr);
  background: #fff;
  border: 1px solid #e3e9f3;
  border-radius: 20px;
  box-shadow: 0 16px 48px rgba(20, 40, 90, 0.08);
  overflow: hidden;
}

/* 左侧品牌 */
.hero {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 560px;
  background: linear-gradient(180deg, #e3effd 0%, #e8f3fe 60%, #e3f1fe 100%);
}
.hero-title {
  padding: 56px 56px 0;
  font-size: 40px;
  line-height: 1.4;
  font-weight: 900;
  color: #0f1a3d;
  letter-spacing: 0.02em;
}
.hero-pic { margin-top: auto; }
.hero-img { display: block; width: 100%; }
.hero-sub { padding: 6px 56px 32px; font-size: 16px; color: #33405c; }

/* 右侧表单 */
.panel {
  padding: 56px 56px 40px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.panel-title { font-size: 34px; font-weight: 900; color: #0f1a3d; letter-spacing: 0.02em; }
.panel-sub { font-size: 16px; color: #5b6782; margin: -4px 0 12px; }
.form { display: flex; flex-direction: column; gap: 10px; }
.label { margin-top: 6px; font-size: 15px; font-weight: 700; color: #1b2333; }
.optional { font-weight: 400; color: #7a859c; font-size: 13px; }
.notice { font-size: 13px; color: #9a5b12; background: #fdf3e3; border-radius: 8px; padding: 8px 12px; }
.ok-notice { font-size: 13px; color: #17703a; background: #e7f6ec; border-radius: 8px; padding: 8px 12px; }
.field { display: flex; align-items: center; gap: 10px; height: 50px; padding: 0 14px; border: 1px solid #d6dce8; border-radius: 10px; background: #fff; }
.field:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.field-input { flex: 1; min-width: 0; height: 100%; font-size: 15px; color: #1b2333; background: transparent; border: none; }
.eye { font-size: 13px; color: #7a859c; cursor: pointer; padding: 4px 2px; white-space: nowrap; }
.eye.on { color: var(--accent); }
.code-btn { padding: 6px 12px; border: 1px solid #c9d6f3; border-radius: 8px; color: #9aa6bf; font-size: 14px; font-weight: 600; white-space: nowrap; }
.code-btn.on { color: var(--accent); border-color: var(--accent); cursor: pointer; }
.forgot { display: flex; justify-content: flex-end; font-size: 14px; }
.hint { font-size: 13px; color: #5b6782; }
.agree { display: flex; align-items: center; flex-wrap: wrap; gap: 2px; margin-top: 6px; font-size: 14px; color: #4b5874; cursor: pointer; }
.box { width: 18px; height: 18px; border: 1px solid #b9c3d6; border-radius: 4px; margin-right: 8px; display: flex; align-items: center; justify-content: center; }
.box.on { background: var(--accent); border-color: var(--accent); }
.link { color: var(--accent); cursor: pointer; }
.submit { height: 52px; line-height: 52px; border-radius: 10px; background: var(--accent); color: #fff; font-size: 18px; font-weight: 800; border: none; margin: 8px 0 0; letter-spacing: 0.1em; }
.submit[disabled] { background: #9db8f2; color: #fff; }
.error { color: var(--danger); font-size: 14px; text-align: center; }
.switch { text-align: center; font-size: 15px; color: #33405c; margin-top: 4px; }
.footer { padding: 8px 0 24px; text-align: center; font-size: 13px; color: #7a859c; letter-spacing: 0.04em; }

/* 平板：两栏变窄 */
@media (max-width: 1100px) {
  .hero-title { padding: 44px 40px 0; font-size: 32px; }
  .hero-sub { padding: 6px 40px 28px; }
  .panel { padding: 44px 40px 32px; }
}
/* 手机：插画缩成卡片顶部的横幅，表单在下面 */
@media (max-width: 760px) {
  .topbar { padding: 14px max(16px, env(safe-area-inset-left)) 4px max(16px, env(safe-area-inset-right)); }
  .logo-text { font-size: 20px; }
  .logo-slogan { font-size: 14px; }
  .main { align-items: flex-start; padding: 12px max(12px, env(safe-area-inset-left)) 12px max(12px, env(safe-area-inset-right)); }
  .login-card { grid-template-columns: minmax(0, 1fr); border-radius: 16px; }
  .hero { min-height: 0; }
  .hero-title { padding: 18px 20px 4px; font-size: 21px; line-height: 1.45; }
  /* 只露出插画下半部分（山和城楼），上面的天空裁掉 */
  .hero-pic { position: relative; height: 132px; overflow: hidden; }
  .hero-img { position: absolute; left: 0; right: 0; bottom: 0; }
  .hero-sub { display: none; }
  .panel { padding: 24px 20px 24px; gap: 12px; }
  .panel-title { font-size: 26px; }
  .panel-sub { font-size: 14px; margin-bottom: 4px; }
}
</style>
