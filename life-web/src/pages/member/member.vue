<template>
  <Shell :show-recent="false">
  <view class="page">
    <view class="hero">
      <view class="h-brand"><BrandMark :size="36" /><text class="h-name">星络会员</text></view>
      <view class="h-status">
        <template v-if="!info">加载中…</template>
        <template v-else-if="info.active">会员有效期至 {{ dateText(info.expiresAt) }}</template>
        <template v-else-if="info.expiresAt">会员已于 {{ dateText(info.expiresAt) }} 到期</template>
        <template v-else>还没有开通会员</template>
      </view>
      <view v-if="info" class="h-usage">今天已和星络对话 {{ info.todayUsed }} / {{ info.dailyLimit }} 次</view>
      <view v-if="info" class="h-price"><text class="yen">¥</text>{{ info.price }}<text class="per"> / {{ info.days }} 天</text></view>
      <button class="btn pay" :disabled="!info || !info.payReady || paying" @click="pay">
        {{ paying ? "正在跳转支付宝…" : info?.active ? `续费 ¥${info.price}` : `支付宝付款 ¥${info?.price || ""}` }}
      </button>
      <view v-if="info && !info.payReady" class="h-note">收款还没开通，暂时不能付款</view>
      <view v-else class="h-note">在支付宝完成付款；到期前续费，从原到期日往后顺延 {{ info?.days || 30 }} 天</view>
    </view>

    <view class="card">
      <view class="c-title">会员权益</view>
      <view v-for="b in benefits" :key="b.title" class="benefit">
        <Icon :name="b.icon" :size="18" color="var(--accent)" />
        <view>
          <view class="b-title">{{ b.title }}</view>
          <view class="muted small">{{ b.desc }}</view>
        </view>
      </view>
    </view>

    <view v-if="pending.length" class="card">
      <view class="c-title">待支付</view>
      <view v-for="o in pending" :key="o.outTradeNo" class="order">
        <view class="o-main">
          <view>{{ o.subject }}</view>
          <view class="muted small">{{ timeText(o.createdAt) }} · ¥{{ o.totalAmount }}</view>
        </view>
        <text class="link" @click="query(o)">{{ busy === o.outTradeNo ? "查询中…" : "已付款？查一下" }}</text>
      </view>
    </view>

    <view v-if="logs.length" class="card">
      <view class="c-title">会员记录</view>
      <view v-for="(l, i) in logs" :key="i" class="order">
        <view class="o-main">
          <view>{{ l.source === "PAY" ? "付费开通" : l.days > 0 ? "星络赠送" : "会员调整" }}</view>
          <view class="muted small">{{ timeText(l.createdAt) }} · 到期 {{ dateText(l.expiresAfter) }}</view>
        </view>
        <text class="o-amount" :class="{ minus: l.days < 0 }">{{ l.days > 0 ? "+" : "" }}{{ l.days }} 天</text>
      </view>
    </view>
  </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import BrandMark from "../../components/BrandMark.vue";
import Icon from "../../components/Icon.vue";
import Shell from "../../components/Shell.vue";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { MembershipLog, MembershipView, PayOrder } from "../../api/types";

/**
 * 会员页：会员状态、今天的对话次数、价格、权益、待支付订单和会员记录。付款时后端按商品定价下单，返回支付宝表单，提交后跳到收银台；
 * 付完支付宝回到结果页，再点回这里。回来时最近一笔待支付订单主动查一次，没收到异步通知也能开通。
 */
/** 会员权益：第一条是实际生效的对话次数，数字取后台设置 */
const benefits = computed(() => [
  { icon: "chat", title: `每天和星络对话 ${info.value?.memberDaily ?? 200} 次`, desc: `免费用户每天 ${info.value?.freeDaily ?? 20} 次，规划行程、改行程、比价都够用` },
  { icon: "bed", title: "酒店机票全平台比价", desc: "携程、飞猪、美团等平台一起比，挑最便宜的" },
  { icon: "calendar", title: "出行当天的贴身安排", desc: "每日安排、几点出发、一键导航和打车" },
  { icon: "sparkle", title: "新功能抢先用", desc: "新能力先给会员体验" },
]);
/** 待支付订单在这段时间内的，回到页面时主动查一次 */
const RECHECK_MS = 30 * 60 * 1000;

const info = ref<MembershipView | null>(null);
const orders = ref<PayOrder[]>([]);
const logs = ref<MembershipLog[]>([]);
/** 一天内还没付的订单，付完没自动开通时可以手动查一下 */
const pending = computed(() => orders.value.filter((o) => o.status === "WAIT_PAY" && o.createdAt && Date.now() - new Date(o.createdAt).getTime() < 24 * 3600 * 1000));
const paying = ref(false);
const busy = ref("");

onShow(async () => {
  await load();
  const recent = orders.value.find((o) => o.status === "WAIT_PAY" && o.createdAt && Date.now() - new Date(o.createdAt).getTime() < RECHECK_MS);
  if (recent) await query(recent, true);
});

async function load() {
  try {
    const [m, list, ls] = await Promise.all([api.membership(), api.alipayOrders(), api.membershipLogs()]);
    info.value = m;
    orders.value = list;
    logs.value = ls;
  } catch (e) {
    showError(e);
  }
}

/** 下单并跳到支付宝收银台：后端返回的是 HTML 表单，渲染到页面后提交，不能当链接跳。 */
async function pay() {
  paying.value = true;
  try {
    const created = await api.createMemberOrder();
    // #ifdef H5
    const container = document.createElement("div");
    container.innerHTML = created.formHtml;
    container.style.display = "none";
    document.body.appendChild(container);
    const form = container.querySelector("form");
    if (form) {
      form.submit();
      return;
    }
    // #endif
    uni.showToast({ title: "请在浏览器里打开星络完成付款", icon: "none" });
    await load();
  } catch (e) {
    showError(e);
  } finally {
    paying.value = false;
  }
}

/** 向支付宝查一次这笔订单；付成功了刷新会员状态。silent 时查不到不提示。 */
async function query(o: PayOrder, silent = false) {
  busy.value = o.outTradeNo;
  try {
    const r = await api.queryAlipayOrder(o.outTradeNo);
    if (r.state === "PAID") {
      uni.showToast({ title: "会员已开通", icon: "success" });
      await load();
    } else if (!silent) {
      uni.showToast({ title: r.message || (r.state === "PENDING_PAYMENT" ? "还没有付款" : "暂时查不到结果，稍后再试"), icon: "none" });
    }
  } catch (e) {
    if (!silent) showError(e);
  } finally {
    busy.value = "";
  }
}

function dateText(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日`;
}

function timeText(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
</script>

<style scoped>
.page { max-width: 640px; margin: 0 auto; padding: 16px; display: flex; flex-direction: column; gap: 14px; box-sizing: border-box; }
.hero { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 24px 18px 20px; border-radius: 18px;
  background: linear-gradient(160deg, var(--accent-soft), var(--surface)); border: 1px solid var(--line); text-align: center; }
.h-brand { display: flex; align-items: center; gap: 8px; }
.h-name { font-size: 20px; font-weight: 800; }
.h-status { font-size: 14px; color: var(--muted); }
.h-usage { font-size: 12px; color: var(--muted); background: var(--surface); border: 1px solid var(--line); border-radius: 999px; padding: 3px 10px; }
.h-price { font-size: 36px; font-weight: 800; color: var(--ink); font-variant-numeric: tabular-nums; }
.h-price .yen { font-size: 20px; }
.h-price .per { font-size: 14px; font-weight: 400; color: var(--muted); }
.btn.pay { width: 100%; max-width: 360px; margin: 4px 0 0; }
.h-note { font-size: 12px; color: var(--muted); }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: 14px 16px; display: flex; flex-direction: column; gap: 12px; }
.c-title { font-size: 15px; font-weight: 700; }
.benefit { display: flex; align-items: flex-start; gap: 10px; }
.b-title { font-size: 14px; font-weight: 600; }
.order { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.order:last-child { padding-bottom: 0; border-bottom: none; }
.o-main { font-size: 14px; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.o-amount { font-weight: 700; font-variant-numeric: tabular-nums; color: var(--ok); }
.o-amount.minus { color: var(--muted); }
.link { font-size: 12px; color: var(--accent); cursor: pointer; }
.small { font-size: 12px; }
.muted { color: var(--muted); }
</style>
