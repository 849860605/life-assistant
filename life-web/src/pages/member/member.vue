<template>
  <Shell active="member">
    <view class="page-wrap member">
      <view class="head">
        <view class="page-title">星络会员</view>
        <view class="page-sub">
          <template v-if="!info">加载中…</template>
          <template v-else-if="info.active">会员有效期至 {{ dateText(info.expiresAt) }}</template>
          <template v-else-if="info.expiresAt">会员已于 {{ dateText(info.expiresAt) }} 到期</template>
          <template v-else>还没有开通会员</template>
        </view>
      </view>

      <!-- 支付结果：只认服务端向支付宝查到的状态 -->
      <view v-if="flow" class="card result" :class="flow.state">
        <template v-if="flow.state === 'checking'">
          <view class="r-title">正在确认支付结果…</view>
          <view class="muted small">向支付宝查询这笔订单，结果以服务端确认为准</view>
        </template>
        <template v-else-if="flow.state === 'paid' && flow.order">
          <view class="r-title ok"><Icon name="checkCircle" :size="20" color="var(--ok)" />{{ flow.log && isRenew(flow.log) ? "续费成功" : "开通成功" }}</view>
          <view class="kv">
            <view class="kv-row"><text class="k">实付金额</text><text class="v">¥{{ flow.order.totalAmount }}</text></view>
            <view v-if="flow.log" class="kv-row"><text class="k">会员时长</text><text class="v">+{{ flow.log.days }} 天</text></view>
            <view class="kv-row"><text class="k">新的到期日</text><text class="v">{{ dateText(flow.log?.expiresAfter || info?.expiresAt) }}</text></view>
            <view class="kv-row"><text class="k">订单号</text><text class="v mono">{{ flow.order.outTradeNo }}</text></view>
          </view>
          <view class="r-acts">
            <button class="btn" @click="startChat">开始对话</button>
            <button class="btn ghost" @click="flow = null">查看会员记录</button>
          </view>
        </template>
        <template v-else-if="flow.state === 'pending'">
          <view class="r-title warn">还没有收到这笔付款</view>
          <view class="muted small">如果刚在支付宝付完，可能还在处理，稍等几秒再查一次。没付的话可以重新开通。</view>
          <view class="r-acts">
            <button class="btn" @click="check(flow.no)">重新查询</button>
            <button class="btn ghost" @click="flow = null">稍后再说</button>
          </view>
        </template>
        <template v-else-if="flow.state === 'closed'">
          <view class="r-title warn">订单已关闭</view>
          <view class="muted small">超时未付款或已取消，不会扣款。需要的话重新开通即可。</view>
          <view class="r-acts"><button class="btn ghost" @click="flow = null">知道了</button></view>
        </template>
        <template v-else>
          <view class="r-title warn">暂时确认不了支付结果</view>
          <view class="muted small">{{ flow.message || "查询支付宝没有拿到结果" }}。如果已经扣款，确认后会员会自动开通，不会重复扣钱。</view>
          <view class="r-acts">
            <button class="btn" @click="check(flow.no)">重新查询</button>
            <button class="btn ghost" @click="flow = null">稍后再说</button>
          </view>
        </template>
      </view>

      <view class="card plan">
        <view v-if="info" class="usage">
          <view class="usage-head">
            <text>今天已对话 <text class="strong">{{ info.todayUsed }}</text> / {{ info.dailyLimit }} 次</text>
            <text class="muted small">{{ info.active ? "会员额度" : "免费额度" }}，每天 0 点重置</text>
          </view>
          <view class="bar"><view class="bar-in" :class="{ full: info.todayUsed >= info.dailyLimit }" :style="{ width: usagePct + '%' }" /></view>
        </view>
        <view class="buy">
          <view class="price-box">
            <text class="price-num">¥{{ info?.price || "—" }}</text>
            <text class="muted"> / {{ info?.days || 30 }} 天</text>
          </view>
          <button class="btn pay" :disabled="!info || !info.payReady || creating" @click="pay">
            {{ creating ? "正在创建订单…" : info?.active ? "支付宝续费" : "支付宝开通" }}
          </button>
        </view>
        <view v-if="info && !info.payReady" class="notice warn">收款还没开通，暂时不能付款</view>
        <view v-else class="muted small">在支付宝完成付款。到期前续费，从原到期日往后顺延 {{ info?.days || 30 }} 天；已过期的从付款当天算起。不会自动扣费。</view>
      </view>

      <view class="card">
        <view class="c-title">会员权益</view>
        <view v-for="b in benefits" :key="b.title" class="benefit">
          <Icon :name="b.icon" :size="18" color="var(--muted)" />
          <view>
            <view class="b-title">{{ b.title }}</view>
            <view class="muted small">{{ b.desc }}</view>
          </view>
        </view>
      </view>

      <view v-if="pending.length" class="card">
        <view class="c-title">待支付</view>
        <view v-for="o in pending" :key="o.outTradeNo" class="row">
          <view class="row-main">
            <view>{{ o.subject }} · ¥{{ o.totalAmount }}</view>
            <view class="muted small">{{ timeText(o.createdAt) }} 创建</view>
          </view>
          <text class="link" @click="check(o.outTradeNo)">已付款？查一下</text>
          <text class="link muted-link" @click="cancel(o)">{{ busy === o.outTradeNo ? "取消中…" : "取消" }}</text>
        </view>
      </view>

      <view class="card">
        <view class="c-title">会员记录</view>
        <view v-if="!logs.length" class="muted small">还没有记录</view>
        <view v-for="(l, i) in shownLogs" :key="i" class="row" :class="{ tap: !!orderOf(l) }" @click="openOrder(l)">
          <view class="row-main">
            <view>{{ logLabel(l) }}</view>
            <view class="muted small">{{ timeText(l.createdAt) }} · 到期 {{ dateText(l.expiresAfter) }}</view>
          </view>
          <text class="days" :class="{ minus: l.days < 0 }">{{ l.days > 0 ? "+" : "" }}{{ l.days }} 天</text>
          <Icon v-if="orderOf(l)" name="right" :size="14" color="var(--muted)" />
        </view>
        <view v-if="logs.length > 5" class="link more" @click="allLogs = !allLogs">{{ allLogs ? "收起" : `查看全部 ${logs.length} 条` }}</view>
      </view>
    </view>

    <!-- 单笔支付详情 -->
    <view v-if="detail" class="mask" @click.self="detail = null">
      <view class="sheet" role="dialog" aria-label="支付详情">
        <view class="c-title">支付详情</view>
        <view class="kv">
          <view class="kv-row"><text class="k">商品</text><text class="v">{{ detail.subject }}</text></view>
          <view class="kv-row"><text class="k">金额</text><text class="v">¥{{ detail.totalAmount }}</text></view>
          <view class="kv-row"><text class="k">状态</text><text class="v">{{ statusText(detail) }}</text></view>
          <view class="kv-row"><text class="k">下单时间</text><text class="v">{{ timeText(detail.createdAt) }}</text></view>
          <view v-if="detail.paidAt" class="kv-row"><text class="k">付款时间</text><text class="v">{{ timeText(detail.paidAt) }}</text></view>
          <view v-if="Number(detail.refundedAmount || 0) > 0" class="kv-row"><text class="k">已退款</text><text class="v">¥{{ detail.refundedAmount }}</text></view>
          <view class="kv-row"><text class="k">订单号</text><text class="v mono">{{ detail.outTradeNo }}</text></view>
          <view v-if="detail.tradeNo" class="kv-row"><text class="k">支付宝交易号</text><text class="v mono">{{ detail.tradeNo }}</text></view>
        </view>
        <button class="btn ghost" @click="detail = null">关闭</button>
      </view>
    </view>
  </Shell>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import Icon from "../../components/Icon.vue";
import Shell from "../../components/Shell.vue";
import { api } from "../../api";
import { showError } from "../../api/http";
import type { MembershipLog, MembershipView, PayOrder } from "../../api/types";

/**
 * 会员页：会员状态、今天的对话次数、价格和开通/续费、权益、待支付订单、会员记录和单笔支付详情。
 * 付款：后端按商品定价下单，返回支付宝表单，提交后跳到收银台；付完从支付宝结果页带着订单号回到这里，
 * 再向服务端确认这一笔（服务端查支付宝），成功后显示服务端返回的金额、时长、新到期日和订单号。
 * 前端跳转参数、"我已付款"和计时都不算支付成功。
 */
type FlowState = "checking" | "paid" | "pending" | "closed" | "unknown";

/** 没带订单号回来时，这段时间内创建的待支付订单主动查一次 */
const RECHECK_MS = 30 * 60 * 1000;

const info = ref<MembershipView | null>(null);
const orders = ref<PayOrder[]>([]);
const logs = ref<MembershipLog[]>([]);
const creating = ref(false);
const busy = ref("");
const allLogs = ref(false);
const detail = ref<PayOrder | null>(null);
const flow = ref<{ state: FlowState; no: string; order?: PayOrder; log?: MembershipLog; message?: string } | null>(null);
/** 从支付宝结果页回来时带的订单号 */
let returnedNo = "";

const benefits = computed(() => [
  { icon: "chat", title: `每天和星络对话 ${info.value?.memberDaily ?? 200} 次`, desc: `免费用户每天 ${info.value?.freeDaily ?? 20} 次` },
  { icon: "bed", title: "酒店机票比价", desc: "在已接入的平台之间比较报价，下单在平台完成" },
  { icon: "calendar", title: "出行当天的安排和提醒", desc: "每天的安排、几点出发，一键导航和打车入口" },
  { icon: "sparkle", title: "新功能抢先体验", desc: "新能力先给会员用" },
]);
const usagePct = computed(() => (info.value ? Math.min(100, Math.round((info.value.todayUsed / Math.max(1, info.value.dailyLimit)) * 100)) : 0));
/** 一天内还没付的订单 */
const pending = computed(() => orders.value.filter((o) => o.status === "WAIT_PAY" && o.createdAt && Date.now() - Date.parse(o.createdAt) < 24 * 3600 * 1000));
const shownLogs = computed(() => (allLogs.value ? logs.value : logs.value.slice(0, 5)));

onLoad((q) => {
  if (q?.order) returnedNo = decodeURIComponent(String(q.order));
});

onShow(async () => {
  await load();
  if (returnedNo) {
    const no = returnedNo;
    returnedNo = "";
    await check(no);
    return;
  }
  const recent = orders.value.find((o) => o.status === "WAIT_PAY" && o.createdAt && Date.now() - Date.parse(o.createdAt) < RECHECK_MS);
  if (recent) await check(recent.outTradeNo, true);
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
  creating.value = true;
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
    creating.value = false;
    await load();
  } catch (e) {
    creating.value = false;
    showError(e);
  }
}

/**
 * 向服务端确认一笔订单（服务端查支付宝并同步状态）。silent 时只有付成功才显示结果，
 * 其他情况不打扰（回到页面时的自动检查）。
 */
async function check(no: string, silent = false) {
  if (!silent) flow.value = { state: "checking", no };
  try {
    const r = await api.queryAlipayOrder(no);
    if (r.state === "PAID") {
      await load();
      flow.value = { state: "paid", no, order: r.order, log: logs.value.find((l) => l.outTradeNo === no) };
    } else if (!silent) {
      flow.value = { state: r.state === "PENDING_PAYMENT" ? "pending" : r.state === "CLOSED" ? "closed" : "unknown", no, message: r.message || undefined };
      if (r.state === "CLOSED") await load();
    }
  } catch (e) {
    if (!silent) flow.value = { state: "unknown", no, message: e instanceof Error ? e.message : "网络异常" };
  }
}

/** 取消一笔待支付订单（关单后支付宝那边也付不了） */
async function cancel(o: PayOrder) {
  busy.value = o.outTradeNo;
  try {
    await api.closeAlipayOrder(o.outTradeNo);
    uni.showToast({ title: "已取消", icon: "none" });
    await load();
  } catch (e) {
    showError(e);
  } finally {
    busy.value = "";
  }
}

function isRenew(l: MembershipLog): boolean {
  return !!l.expiresBefore && Date.parse(l.expiresBefore) > Date.parse(l.createdAt);
}

function logLabel(l: MembershipLog): string {
  if (l.source === "PAY") return isRenew(l) ? "付费续费" : "付费开通";
  return l.days > 0 ? (isRenew(l) ? "赠送时长" : "星络赠送") : "时长调整";
}

function orderOf(l: MembershipLog): PayOrder | undefined {
  return l.outTradeNo ? orders.value.find((o) => o.outTradeNo === l.outTradeNo) : undefined;
}

function openOrder(l: MembershipLog) {
  const o = orderOf(l);
  if (o) detail.value = o;
}

function statusText(o: PayOrder): string {
  if (o.status === "PAID") return Number(o.refundedAmount || 0) > 0 ? "已付款（有退款）" : "已付款";
  if (o.status === "WAIT_PAY") return "待支付";
  return "已关闭";
}

function startChat() {
  uni.reLaunch({ url: "/pages/home/home" });
}

function dateText(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日`;
}

function timeText(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
</script>

<style scoped>
.member { max-width: 720px; }
.head { display: flex; flex-direction: column; gap: 4px; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.c-title { font-size: 15px; font-weight: 600; }
.usage { display: flex; flex-direction: column; gap: 8px; }
.usage-head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; flex-wrap: wrap; font-size: 14px; }
.bar { height: 6px; border-radius: 3px; background: var(--surface-2); overflow: hidden; }
.bar-in { height: 100%; background: var(--accent); border-radius: 3px; }
.bar-in.full { background: var(--warn); }
.buy { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding-top: 4px; }
.price-num { font-size: 32px; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -0.01em; }
.btn.pay { margin: 0; min-width: 180px; height: 44px; }
.benefit { display: flex; align-items: flex-start; gap: 12px; }
.b-title { font-size: 14px; font-weight: 600; }
.row { display: flex; align-items: center; gap: 12px; padding-top: 10px; border-top: 1px solid var(--line); font-size: 14px; }
.c-title + .row { border-top: none; padding-top: 0; }
.row.tap { cursor: pointer; }
.row-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.days { font-weight: 600; font-variant-numeric: tabular-nums; color: var(--ok); }
.days.minus { color: var(--muted); }
.link { font-size: 13px; color: var(--accent); cursor: pointer; white-space: nowrap; }
.muted-link { color: var(--muted); }
.more { align-self: flex-start; }
.result { gap: 10px; }
.result.paid { border-color: var(--ok); }
.r-title { display: flex; align-items: center; gap: 8px; font-size: 17px; font-weight: 600; }
.r-title.ok { color: var(--ok); }
.r-title.warn { color: var(--warn); }
.r-acts { display: flex; gap: 10px; flex-wrap: wrap; }
.r-acts .btn { margin: 0; }
.kv { display: flex; flex-direction: column; gap: 6px; font-size: 14px; }
.kv-row { display: flex; justify-content: space-between; gap: 16px; }
.k { color: var(--muted); white-space: nowrap; }
.v { text-align: right; word-break: break-all; }
.mono { font-family: var(--mono); font-size: 13px; }
.strong { font-weight: 600; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.mask { position: fixed; inset: 0; z-index: 100; background: rgba(0, 0, 0, 0.3); display: flex; align-items: center; justify-content: center; padding: 16px; }
.sheet { width: 100%; max-width: 440px; background: var(--surface); border-radius: 14px; padding: 20px; display: flex; flex-direction: column; gap: 14px; box-sizing: border-box; }
.sheet .btn { margin: 0; }
@media (max-width: 1023px) {
  .card { padding: 14px 16px; }
  .btn.pay { width: 100%; }
  .mask { align-items: flex-end; padding: 0; }
  .sheet { max-width: none; border-radius: 14px 14px 0 0; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
}
</style>
