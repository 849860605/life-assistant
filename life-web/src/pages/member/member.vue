<template>
  <Shell active="member">
    <view class="page-wrap member">
      <view class="head">
        <view class="page-title">我的积分</view>
        <view class="page-sub">用多少扣多少，不包月、不自动续费</view>
      </view>

      <!-- 支付结果：只认服务端向支付宝查到的状态 -->
      <view v-if="flow" class="card result" :class="flow.state">
        <template v-if="flow.state === 'checking'">
          <view class="r-title">正在确认支付结果…</view>
          <view class="muted small">向支付宝查询这笔订单，结果以服务端确认为准</view>
        </template>
        <template v-else-if="flow.state === 'paid' && flow.order">
          <view class="r-title ok"><Icon name="checkCircle" :size="20" color="var(--ok)" />购买成功</view>
          <view class="kv">
            <view class="kv-row"><text class="k">实付金额</text><text class="v">¥{{ flow.order.totalAmount }}</text></view>
            <view v-if="flow.entry" class="kv-row"><text class="k">到账积分</text><text class="v">+{{ flow.entry.amount }}</text></view>
            <view class="kv-row"><text class="k">现在可用</text><text class="v">{{ total }} 积分</text></view>
            <view class="kv-row"><text class="k">订单号</text><text class="v mono">{{ flow.order.outTradeNo }}</text></view>
          </view>
          <view class="r-acts">
            <button class="btn" @click="startChat">开始对话</button>
            <button class="btn ghost" @click="flow = null">好的</button>
          </view>
        </template>
        <template v-else-if="flow.state === 'pending'">
          <view class="r-title warn">还没有收到这笔付款</view>
          <view class="muted small">如果刚在支付宝付完，可能还在处理，稍等几秒再查一次。没付的话可以重新买。</view>
          <view class="r-acts">
            <button class="btn" @click="check(flow.no)">重新查询</button>
            <button class="btn ghost" @click="flow = null">稍后再说</button>
          </view>
        </template>
        <template v-else-if="flow.state === 'closed'">
          <view class="r-title warn">订单已关闭</view>
          <view class="muted small">超时未付款或已取消，不会扣款。需要的话重新买即可。</view>
          <view class="r-acts"><button class="btn ghost" @click="flow = null">知道了</button></view>
        </template>
        <template v-else>
          <view class="r-title warn">暂时确认不了支付结果</view>
          <view class="muted small">{{ flow.message || "查询支付宝没有拿到结果" }}。如果已经扣款，确认后积分会自动到账，不会重复扣钱。</view>
          <view class="r-acts">
            <button class="btn" @click="check(flow.no)">重新查询</button>
            <button class="btn ghost" @click="flow = null">稍后再说</button>
          </view>
        </template>
      </view>

      <!-- 余额 -->
      <view class="card balance">
        <view class="bal-main">
          <view>
            <view class="muted small">现在可用</view>
            <view class="bal-num">{{ total ?? "—" }}<text class="bal-unit"> 积分</text></view>
          </view>
          <view v-if="v" class="bal-parts">
            <view class="part"><text class="muted">余额</text><text class="strong" :class="{ minus: v.balance < 0 }">{{ v.balance }}</text></view>
            <view class="part"><text class="muted">今天赠送</text><text class="strong">{{ v.dailyLeft }} / {{ v.daily }}</text></view>
          </view>
        </view>
        <view v-if="v && v.balance < 0" class="notice warn">上次规划行程透支了 {{ -v.balance }} 积分，买积分后先补上</view>
        <view v-if="v?.member" class="notice">你的会员有效期到 {{ dateText(v.memberExpiresAt) }}，到期前用星络不扣积分</view>
        <view class="muted small">每天送 {{ v?.daily ?? 10 }} 积分，当天有效、不累积，扣费时先扣它。</view>
      </view>

      <!-- 积分包 -->
      <view class="card">
        <view class="c-title">买积分</view>
        <view class="packs">
          <view v-for="p in packs" :key="p.code" class="pack" :class="{ on: picked === p.code }" @click="picked = p.code">
            <view class="pack-credits">{{ p.credits.toLocaleString() }}<text class="pack-unit"> 积分</text></view>
            <view class="pack-price">¥{{ money(p.yuan) }}</view>
            <text v-if="p.bonusPercent > 0" class="pack-bonus">多送 {{ p.bonusPercent }}%</text>
          </view>
        </view>
        <button class="btn pay" :disabled="!pickedPack || creating" @click="pay">
          {{ creating ? "正在创建订单…" : pickedPack ? `支付宝支付 ¥${money(pickedPack.yuan)}` : "选一个积分包" }}
        </button>
        <view class="muted small">在支付宝完成付款，付完积分马上到账，长期有效。积分不能提现、不能转给别人；没用过的可以联系我们退款<text v-if="contactEmail">（发邮件到 {{ contactEmail }}，写明账号和订单号）</text>。</view>
      </view>

      <!-- 价格表 -->
      <view class="card">
        <view class="c-title">怎么扣积分</view>
        <view v-for="(name, key) in features" :key="key" class="price-row">
          <text>{{ name }}</text>
          <text class="strong">{{ prices[key] ?? "—" }} 积分</text>
        </view>
        <view class="muted small">规划行程、修改行程、比价，同一轮对话里同一件事只扣一次；同样的比价 10 分钟内再查不扣，5 分钟内再看同一个航班不扣。出错没完成的自动退回。</view>
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

      <!-- 明细 -->
      <view class="card">
        <view class="c-title">积分明细</view>
        <view v-if="!ledger.length" class="muted small">还没有记录</view>
        <view v-for="e in shownLedger" :key="e.id" class="row" :class="{ tap: !!orderOf(e) }" @click="openOrder(e)">
          <view class="row-main">
            <view>{{ entryLabel(e) }}</view>
            <view class="muted small">{{ timeText(e.createdAt) }}<text v-if="e.fromDaily && e.kind === 'SPEND'"> · 今天赠送抵 {{ e.fromDaily }}</text></view>
          </view>
          <text class="amount" :class="{ minus: e.amount < 0 }">{{ e.amount > 0 ? "+" : "" }}{{ e.amount }}</text>
          <Icon v-if="orderOf(e)" name="right" :size="14" color="var(--muted)" />
        </view>
        <view v-if="ledger.length > 8" class="link more" @click="allLedger = !allLedger">{{ allLedger ? "收起" : `查看全部 ${ledger.length} 条` }}</view>
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
import type { CreditEntry, PayOrder } from "../../api/types";
import { creditTotal, credits, refreshCredits } from "../../utils/credits";

/**
 * 积分页：现在可用多少（余额 + 今天赠送）、买积分包、价格表、待支付订单、积分明细和单笔支付详情。
 * 付款：后端按积分包定价下单，返回支付宝表单，提交后跳到收银台；付完从支付宝结果页带着订单号回到这里，
 * 再向服务端确认这一笔（服务端查支付宝），成功后显示服务端返回的金额、到账积分和订单号。
 * 前端跳转参数、"我已付款"和计时都不算支付成功。
 */
type FlowState = "checking" | "paid" | "pending" | "closed" | "unknown";

/** 没带订单号回来时，这段时间内创建的待支付订单主动查一次 */
const RECHECK_MS = 30 * 60 * 1000;
const KIND_TEXT: Record<string, string> = { SIGNUP: "新用户赠送", PURCHASE: "购买积分", RETURN: "没有完成，退回", ADMIN: "星络调整", REFUND: "订单退款，扣回" };

/** 退款联系邮箱（协议里写的那个），没配时不显示 */
const contactEmail = ref<string | null>(null);
api.contact().then((c) => (contactEmail.value = c.email)).catch(() => {});
const orders = ref<PayOrder[]>([]);
const ledger = ref<CreditEntry[]>([]);
const picked = ref("");
const creating = ref(false);
const busy = ref("");
const allLedger = ref(false);
const detail = ref<PayOrder | null>(null);
const flow = ref<{ state: FlowState; no: string; order?: PayOrder; entry?: CreditEntry; message?: string } | null>(null);
/** 从支付宝结果页回来时带的订单号 */
let returnedNo = "";

const v = computed(() => credits.data?.view || null);
const total = creditTotal;
const features = computed(() => credits.data?.features || {});
const prices = computed(() => v.value?.plan.prices || {});
const packs = computed(() => v.value?.plan.packs || []);
const pickedPack = computed(() => packs.value.find((p) => p.code === picked.value));
/** 一天内还没付的订单 */
const pending = computed(() => orders.value.filter((o) => o.status === "WAIT_PAY" && o.createdAt && Date.now() - Date.parse(o.createdAt) < 24 * 3600 * 1000));
const shownLedger = computed(() => (allLedger.value ? ledger.value : ledger.value.slice(0, 8)));

onLoad((q) => {
  if (q?.order) returnedNo = decodeURIComponent(String(q.order));
});

onShow(async () => {
  await load();
  // 默认选中间那个（多送的里最便宜的）
  if (!picked.value && packs.value.length) picked.value = (packs.value[1] || packs.value[0]).code;
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
    const [, list, ls] = await Promise.all([refreshCredits(), api.alipayOrders(), api.creditLedger(100)]);
    orders.value = list;
    ledger.value = ls;
  } catch (e) {
    showError(e);
  }
}

/** 下单并跳到支付宝收银台：后端返回的是 HTML 表单，渲染到页面后提交，不能当链接跳。 */
async function pay() {
  if (!pickedPack.value) return;
  creating.value = true;
  try {
    const created = await api.createCreditOrder(pickedPack.value.code);
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
      flow.value = { state: "paid", no, order: r.order, entry: ledger.value.find((e) => e.kind === "PURCHASE" && e.ref === no) };
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

function entryLabel(e: CreditEntry): string {
  if (e.kind === "SPEND") return features.value[e.feature || ""]?.replace(/（.*）/, "") || "使用";
  if (e.kind === "RETURN") return `${features.value[e.feature || ""]?.replace(/（.*）/, "") || ""}没有完成，退回`;
  return KIND_TEXT[e.kind] || e.kind;
}

function orderOf(e: CreditEntry): PayOrder | undefined {
  return e.kind === "PURCHASE" && e.ref ? orders.value.find((o) => o.outTradeNo === e.ref) : undefined;
}

function openOrder(e: CreditEntry) {
  const o = orderOf(e);
  if (o) detail.value = o;
}

function statusText(o: PayOrder): string {
  if (o.status === "PAID") return Number(o.refundedAmount || 0) > 0 ? "已付款（有退款）" : "已付款";
  if (o.status === "WAIT_PAY") return "待支付";
  return "已关闭";
}

function startChat() {
  uni.reLaunch({ url: "/pages/chat/chat" });
}

/** 金额：整数不带小数，其他两位 */
function money(y: string | number): string {
  const n = Number(y);
  return Number.isInteger(n) ? String(n) : n.toFixed(2);
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
.bal-main { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.bal-num { font-size: 40px; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -0.02em; line-height: 1.1; }
.bal-unit { font-size: 15px; font-weight: 400; color: var(--muted); }
.bal-parts { display: flex; gap: 24px; }
.part { display: flex; flex-direction: column; gap: 2px; font-size: 13px; font-variant-numeric: tabular-nums; }
.minus { color: var(--danger); }
.packs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.pack { position: relative; display: flex; flex-direction: column; gap: 4px; padding: 16px 14px; border-radius: 12px; border: 1.5px solid var(--line); cursor: pointer; transition: border-color 0.15s; }
.pack:hover { border-color: var(--accent); }
.pack.on { border-color: var(--accent); background: var(--accent-soft); }
.pack-credits { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }
.pack-unit { font-size: 13px; font-weight: 400; color: var(--muted); }
.pack-price { font-size: 15px; color: var(--ink); }
.pack-bonus { position: absolute; top: -9px; right: 10px; font-size: 11px; padding: 1px 7px; border-radius: 999px; background: var(--accent); color: #fff; font-weight: 600; }
.btn.pay { margin: 0; height: 44px; }
.price-row { display: flex; justify-content: space-between; gap: 12px; font-size: 14px; font-variant-numeric: tabular-nums; }
.row { display: flex; align-items: center; gap: 12px; padding-top: 10px; border-top: 1px solid var(--line); font-size: 14px; }
.c-title + .row { border-top: none; padding-top: 0; }
.row.tap { cursor: pointer; }
.row-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.amount { font-weight: 600; font-variant-numeric: tabular-nums; color: var(--ok); }
.amount.minus { color: var(--muted); }
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
  .packs { gap: 8px; }
  .pack { padding: 14px 10px; }
  .pack-credits { font-size: 18px; }
  .mask { align-items: flex-end; padding: 0; }
  .sheet { max-width: none; border-radius: 14px 14px 0 0; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
}
</style>
