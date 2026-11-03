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

      <!-- 右侧登录卡片 -->
      <view class="panel">
        <view class="panel-title">登录星络</view>
        <view class="panel-sub">登录后可保存行程、继续编辑与查看预订信息</view>

        <view class="tabs">
          <view class="tab" :class="{ on: mode === 'code' }" @click="mode = 'code'">验证码登录</view>
          <view class="tab" :class="{ on: mode === 'password' }" @click="mode = 'password'">密码登录</view>
        </view>

        <view v-if="mode === 'code'" class="form">
          <view class="notice">短信验证码登录暂未开通，请先用「密码登录」。</view>
          <view class="field disabled">
            <Icon name="phone" :size="18" color="var(--muted)" />
            <input id="phone" class="field-input" disabled placeholder="请输入手机号" />
          </view>
          <view class="field disabled">
            <Icon name="shield" :size="18" color="var(--muted)" />
            <input id="code" class="field-input" disabled placeholder="请输入验证码" />
            <view class="code-btn">获取验证码</view>
          </view>
        </view>

        <view v-else class="form">
          <view class="field">
            <Icon name="user" :size="18" color="var(--muted)" />
            <input id="username" v-model="username" class="field-input" placeholder="请输入账号" />
          </view>
          <view class="field">
            <Icon name="lock" :size="18" color="var(--muted)" />
            <input id="password" v-model="password" class="field-input" password placeholder="请输入密码" @confirm="submit" />
          </view>
        </view>

        <view class="agree" @click="agreed = !agreed">
          <view class="box" :class="{ on: agreed }"><Icon v-if="agreed" name="check" :size="12" color="#fff" /></view>
          <text>我已阅读并同意</text>
          <text class="link" @click.stop="notReady('《用户协议》还没写，正式上线前补上')">《用户协议》</text>
          <text class="link" @click.stop="notReady('《隐私政策》还没写，正式上线前补上')">《隐私政策》</text>
        </view>

        <button class="submit" :disabled="!canSubmit" @click="submit">
          {{ loading ? "登录中…" : mode === "code" ? "登录 / 注册" : "登录" }}
        </button>
        <view v-if="error" class="error">{{ error }}</view>

        <view class="wechat" @click="notReady('微信快捷登录暂未开通')">
          <Icon name="wechat" :size="20" color="#07c160" />
          <text>微信快捷登录</text>
          <text class="soon">暂未开通</text>
        </view>

        <view class="links">
          <text @click="notReady('找回密码暂未开通，自用版密码在项目 .env 里（LIFE_PASSWORD）')">忘记密码</text>
          <text class="sep">|</text>
          <text>遇到问题？<text class="link" @click="notReady('客服暂未开通')">联系客服</text></text>
        </view>
        <view v-if="mode === 'code'" class="hint">未注册的手机号验证后将自动创建账号（开通后生效）</view>

        <view class="trust">
          <view><Icon name="lock" :size="15" color="var(--muted)" />密码加密存储</view>
          <text class="sep">|</text>
          <view><Icon name="shield" :size="15" color="var(--muted)" />仅用于账号登录与行程同步</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { api } from "../../api";
import { setToken } from "../../api/http";
import { clearMediaTicket } from "../../utils/media";
import Icon from "../../components/Icon.vue";
import BrandMark from "../../components/BrandMark.vue";

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

const mode = ref<"code" | "password">("password");
const username = ref("");
const password = ref("");
const agreed = ref(false);
const loading = ref(false);
const error = ref("");

const canSubmit = computed(() => mode.value === "password" && !loading.value && !!username.value && !!password.value);

function notReady(msg: string) {
  uni.showToast({ title: msg, icon: "none", duration: 2500 });
}

/** 登录成功后保存 token，进入首页。 */
async function submit() {
  if (!canSubmit.value) return;
  if (!agreed.value) {
    error.value = "请先勾选同意用户协议和隐私政策";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const result = await api.login(username.value.trim(), password.value);
    setToken(result.token);
    // 换了账号，旧的下载票据不能再用
    clearMediaTicket();
    forgetOtherUser(result.user.id);
    uni.switchTab({ url: "/pages/home/home" });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
  } finally {
    loading.value = false;
  }
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
.agree { display: flex; align-items: center; flex-wrap: wrap; gap: 2px; font-size: 13px; color: #4b5874; cursor: pointer; }
.box { width: 16px; height: 16px; border: 1px solid #b9c3d6; border-radius: 4px; margin-right: 6px; display: flex; align-items: center; justify-content: center; }
.box.on { background: var(--accent); border-color: var(--accent); }
.link { color: var(--accent); cursor: pointer; }
.submit { height: 52px; line-height: 52px; border-radius: 12px; background: var(--accent); color: #fff; font-size: 18px; font-weight: 800; border: none; margin: 4px 0 0; }
.submit[disabled] { background: #9db8f2; color: #fff; }
.error { color: var(--danger); font-size: 14px; text-align: center; }
.wechat { height: 50px; border: 1px solid #dfe4ee; border-radius: 12px; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 16px; font-weight: 600; color: #8a94a8; background: #fafbfd; cursor: pointer; }
.soon { font-size: 12px; font-weight: 600; color: #9a5b12; background: #fdf3e3; padding: 1px 8px; border-radius: 999px; }
.links { display: flex; justify-content: center; align-items: center; gap: 24px; font-size: 14px; color: #4b5874; }
.links > text:first-child { cursor: pointer; }
.sep { color: #d3d9e4; }
.hint { text-align: center; font-size: 13px; color: #5b6782; }
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
