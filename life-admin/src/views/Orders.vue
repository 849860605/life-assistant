<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">会员与账单</h1>
        <div class="page-sub">用户在 App 里买会员的订单和每一笔会员变动；付款成功自动加 {{ setting?.days || 30 }} 天。给某个人开会员在"用户管理"的用户详情里操作</div>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="stats">
      <div class="card stat"><div class="muted small">本月实收</div><div class="stat-v num">¥{{ fmtMoney(stats?.monthIncome) }}</div></div>
      <div class="card stat"><div class="muted small">本月付费订单</div><div class="stat-v num">{{ stats?.monthPaidOrders ?? "—" }}</div></div>
      <div class="card stat"><div class="muted small">有效会员</div><div class="stat-v num">{{ stats?.activeMembers ?? "—" }}</div></div>
      <div class="card stat"><div class="muted small">累计实收</div><div class="stat-v num">¥{{ fmtMoney(stats?.totalIncome) }}</div></div>
    </div>

    <div class="cards">
      <div class="card stack">
        <div class="card-title">会员价格</div>
        <div class="row">
          <el-input-number v-model="price" :min="0.01" :max="10000" :precision="2" :step="1" :disabled="!isSuper()" />
          <span class="muted">元 / {{ setting?.days || 30 }} 天</span>
          <el-button v-if="isSuper()" type="primary" :disabled="!priceChanged" :loading="saving === 'price'" @click="savePrice">保存</el-button>
        </div>
        <div class="muted small">
          支付宝收款：<el-tag size="small" :type="setting?.payReady ? 'success' : 'warning'">{{ setting?.payReady ? "已配置" : "没配置，App 上不能付款" }}</el-tag>
        </div>
      </div>

      <div class="card stack">
        <div class="card-title">每天能和星络对话多少次</div>
        <div class="row">
          <span class="lbl">免费用户</span>
          <el-input-number v-model="freeDaily" :min="1" :max="100000" :disabled="!isSuper()" />
          <span class="muted">次</span>
        </div>
        <div class="row">
          <span class="lbl">会员</span>
          <el-input-number v-model="memberDaily" :min="1" :max="100000" :disabled="!isSuper()" />
          <span class="muted">次</span>
          <el-button v-if="isSuper()" type="primary" :disabled="!quotaChanged" :loading="saving === 'quota'" @click="saveQuota">保存</el-button>
        </div>
        <div class="muted small">按北京时间每天零点清零；用完后 App 会提示去开通会员<span v-if="!isSuper()">。只有超级管理员能改</span></div>
      </div>

      <div class="card stack">
        <div class="card-title">航班实时动态免费试查</div>
        <div class="row">
          <span class="lbl">免费用户</span>
          <el-input-number v-model="flightTrial" :min="0" :max="100" :disabled="!isSuper()" />
          <span class="muted">次（总共，不按天清零）</span>
          <el-button v-if="isSuper()" type="primary" :disabled="!setting || setting.flightTrial === flightTrial" :loading="saving === 'trial'" @click="saveTrial">保存</el-button>
        </div>
        <div class="muted small">会员不限次数；同一航班 5 分钟内重复查看不扣次数。查一次飞常准按次计费（约 ¥0.5），填 0 表示不给免费用户试用</div>
      </div>
    </div>

    <div class="card">
      <el-tabs v-model="tab">
        <el-tab-pane label="订单" name="orders">
          <el-table v-loading="loading" :data="rows" empty-text="还没有订单">
            <el-table-column label="下单时间" width="150"><template #default="{ row }"><span class="num">{{ fmtTime(row.order.createdAt) }}</span></template></el-table-column>
            <el-table-column label="用户" width="150">
              <template #default="{ row }">{{ row.nickname || row.username || "—" }}<span class="muted small"> #{{ row.userId }}</span></template>
            </el-table-column>
            <el-table-column label="商品" min-width="150"><template #default="{ row }">{{ row.order.subject }}</template></el-table-column>
            <el-table-column label="金额" width="90"><template #default="{ row }"><span class="num">¥{{ row.order.totalAmount }}</span></template></el-table-column>
            <el-table-column label="状态" min-width="200">
              <template #default="{ row }">
                <el-tag size="small" :type="tone(row.order.status)">{{ STATUS[row.order.status as PayOrder["status"]] }}</el-tag>
                <span v-if="row.order.refundedAmount !== '0.00'" class="muted small"> 已退 ¥{{ row.order.refundedAmount }}</span>
                <div v-for="r in row.order.refunds" :key="r.outRequestNo" class="refund small">
                  退款 ¥{{ r.refundAmount }} · {{ REFUND[r.status as PayRefund["status"]] }}<span v-if="r.message" class="muted"> · {{ r.message }}</span>
                  <template v-if="r.status === 'PENDING'">
                    <el-button link type="primary" size="small" @click="queryRefund(r)">查询</el-button>
                    <el-button v-if="isSuper()" link type="primary" size="small" @click="retryRefund(r)">原样重试</el-button>
                  </template>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" :fixed="narrow ? false : 'right'">
              <template #default="{ row }">
                <el-button v-if="row.order.status !== 'CLOSED'" link type="primary" @click="query(row.order)">查单</el-button>
                <el-button v-if="isSuper() && row.order.status === 'PAID'" link type="danger" @click="refund(row.order)">退款</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="会员变动" name="logs">
          <el-table v-loading="loading" :data="logs" empty-text="还没有会员变动">
            <el-table-column label="时间" width="150"><template #default="{ row }"><span class="num">{{ fmtTime(row.log.createdAt) }}</span></template></el-table-column>
            <el-table-column label="用户" width="150">
              <template #default="{ row }">{{ row.nickname || row.username || "—" }}<span class="muted small"> #{{ row.log.userId }}</span></template>
            </el-table-column>
            <el-table-column label="来源" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="row.log.source === 'PAY' ? 'success' : 'info'">{{ row.log.source === "PAY" ? "付费开通" : "后台调整" }}</el-tag>
                <span v-if="row.log.adminName" class="muted small"> {{ row.log.adminName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="天数" width="80" align="right">
              <template #default="{ row }"><span class="num" :class="{ plus: row.log.days > 0 }">{{ row.log.days > 0 ? "+" : "" }}{{ row.log.days }}</span></template>
            </el-table-column>
            <el-table-column label="到期变化" min-width="230">
              <template #default="{ row }"><span class="num small">{{ row.log.expiresBefore ? fmtDate(row.log.expiresBefore) : "未开通" }} → {{ fmtDate(row.log.expiresAfter) }}</span></template>
            </el-table-column>
            <el-table-column label="说明" min-width="160">
              <template #default="{ row }">{{ row.log.note || (row.log.outTradeNo ? `订单 ${row.log.outTradeNo}` : "—") }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, type BillingStats, type MemberLogRow, type MemberSetting, type OrderRow, type PayOrder, type PayRefund } from "../api";
import { isSuper } from "../session";
import { fmtDate, fmtTime, narrow } from "../utils";

/** 会员与账单：本月收入等概况、会员价格、每天对话次数、订单（查单、退款）和会员变动记录 */
const STATUS: Record<PayOrder["status"], string> = { WAIT_PAY: "待支付", PAID: "已支付", CLOSED: "已关闭" };
const REFUND: Record<PayRefund["status"], string> = { PENDING: "结果待确认", SUCCESS: "已退款", FAILED: "失败" };
const STATE: Record<string, string> = { PAID: "已支付", PENDING_PAYMENT: "还没付款", CLOSED: "已关闭", UNKNOWN: "暂时查不到结果" };

const tab = ref("orders");
const rows = ref<OrderRow[]>([]);
const logs = ref<MemberLogRow[]>([]);
const stats = ref<BillingStats | null>(null);
const loading = ref(false);
const setting = ref<MemberSetting | null>(null);
const price = ref(9.9);
const freeDaily = ref(20);
const memberDaily = ref(200);
const flightTrial = ref(2);
const saving = ref<"" | "price" | "quota" | "trial">("");
const priceChanged = computed(() => setting.value != null && Number(setting.value.price) !== price.value);
const quotaChanged = computed(() => setting.value != null
  && (setting.value.freeDaily !== freeDaily.value || setting.value.memberDaily !== memberDaily.value));

onMounted(load);

async function load() {
  loading.value = true;
  try {
    const [list, ls, s, st] = await Promise.all([api.payOrders(), api.memberLogs(), api.memberSetting(), api.billingStats()]);
    rows.value = list;
    logs.value = ls;
    stats.value = st;
    applySetting(s);
  } finally {
    loading.value = false;
  }
}

function applySetting(s: MemberSetting) {
  setting.value = s;
  price.value = Number(s.price);
  freeDaily.value = s.freeDaily;
  memberDaily.value = s.memberDaily;
  flightTrial.value = s.flightTrial;
}

async function savePrice() {
  saving.value = "price";
  try {
    applySetting(await api.setMemberPrice(price.value.toFixed(2)));
    ElMessage.success(`已改成 ¥${setting.value!.price}，之后的新订单按新价格`);
  } finally {
    saving.value = "";
  }
}

async function saveQuota() {
  saving.value = "quota";
  try {
    applySetting(await api.setChatQuota(freeDaily.value, memberDaily.value));
    ElMessage.success("已保存，马上生效");
  } finally {
    saving.value = "";
  }
}

async function saveTrial() {
  saving.value = "trial";
  try {
    applySetting(await api.setFlightTrial(flightTrial.value));
    ElMessage.success("已保存，马上生效");
  } finally {
    saving.value = "";
  }
}

async function query(o: PayOrder) {
  const r = await api.queryPayOrder(o.outTradeNo);
  ElMessage({ type: r.state === "PAID" ? "success" : "info", message: r.message || STATE[r.state] || r.state });
  await load();
}

async function refund(o: PayOrder) {
  const left = (Number(o.totalAmount) - Number(o.refundedAmount)).toFixed(2);
  const { value } = await ElMessageBox.prompt(`订单 ¥${o.totalAmount}，最多还能退 ¥${left}。退款会原路退回用户支付宝；会员时间不会自动扣，需要的话到用户详情里扣。`, "退款", {
    inputValue: left,
    inputPattern: /^\d+(\.\d{1,2})?$/,
    inputErrorMessage: "金额最多两位小数",
    confirmButtonText: "退款",
    type: "warning",
  });
  const r = await api.refundPayOrder(o.outTradeNo, value, "后台退款");
  ElMessage({ type: r.status === "SUCCESS" ? "success" : r.status === "FAILED" ? "error" : "info", message: `退款${REFUND[r.status]}${r.message ? "：" + r.message : ""}` });
  await load();
}

async function queryRefund(r: PayRefund) {
  await api.queryRefund(r.outRequestNo);
  await load();
}

async function retryRefund(r: PayRefund) {
  await api.retryRefund(r.outRequestNo);
  await load();
}

function tone(s: string): "success" | "info" | "warning" {
  return s === "PAID" ? "success" : s === "WAIT_PAY" ? "warning" : "info";
}

function fmtMoney(v?: number | null): string {
  return v == null ? "—" : Number(v).toFixed(2);
}
</script>

<style scoped>
.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; margin-bottom: 16px; }
.stat-v { font-size: 22px; font-weight: 700; margin-top: 4px; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 16px; margin-bottom: 16px; }
.stack { display: flex; flex-direction: column; gap: 12px; }
.card-title { font-weight: 600; }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.lbl { width: 60px; }
.refund { margin-top: 4px; }
.small { font-size: 12px; }
.plus { color: var(--el-color-success); }
</style>
