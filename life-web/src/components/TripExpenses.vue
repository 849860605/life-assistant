<template>
  <view class="ledger">
    <view class="stats">
      <view class="stat">
        <view class="stat-label">总预算</view>
        <view v-if="trip.budget != null" class="stat-num">{{ money(trip.budget, budgetCur) }}</view>
        <view v-else class="stat-empty">未设置 <text class="link" @click="emit('edit-info')">设置</text></view>
      </view>
      <view class="stat">
        <view class="stat-label">已记录支出</view>
        <view v-if="!expenses.length" class="stat-empty">{{ loaded ? "还没有记录" : "加载中…" }}</view>
        <view v-else class="stat-num">{{ money(spentIn(budgetCur), budgetCur) }}</view>
      </view>
      <view class="stat">
        <view class="stat-label">预算余额</view>
        <view v-if="trip.budget == null || !expenses.length" class="stat-empty">—</view>
        <view v-else class="stat-num" :class="{ over: remaining < 0 }">{{ remaining < 0 ? "超出 " : "" }}{{ money(Math.abs(remaining), budgetCur) }}</view>
      </view>
    </view>
    <view v-if="otherCurrencies.length" class="notice warn">
      另有 {{ otherCurrencies.map((c) => money(spentIn(c), c)).join("、") }} 没算进余额：不同币种之间没有统一汇率，分开统计。
    </view>
    <view v-if="estimate.perPerson || estimate.stayPerRoom" class="muted small">
      行程里的计划估算：<text v-if="estimate.perPerson">景点餐饮约 ¥{{ estimate.perPerson }}/人</text><text v-if="estimate.perPerson && estimate.stayPerRoom">，</text><text v-if="estimate.stayPerRoom">住宿约 ¥{{ estimate.stayPerRoom }}/间</text>。只是参考，不算实际支出。
    </view>

    <view v-if="breakdown.length" class="card">
      <view class="c-title">支出构成<text class="muted small">（{{ budgetCur }}）</text></view>
      <view v-for="b in breakdown" :key="b.cat" class="bar-row">
        <text class="bar-label">{{ CATS[b.cat] }}</text>
        <view class="bar"><view class="bar-in" :style="{ width: b.pct + '%' }" /></view>
        <text class="bar-num">{{ money(b.sum, budgetCur) }}</text>
      </view>
    </view>

    <view class="card">
      <view class="c-head">
        <text class="c-title">费用明细</text>
        <button class="btn small" @click="openForm()">记一笔</button>
      </view>
      <view v-if="loaded && !expenses.length" class="empty-box">
        还没记录实际支出。没记录不代表花了 0 元，花了钱随手记一笔，或者在「预订信息」里按订单记账。
      </view>
      <view v-for="e in expenses" :key="e.id" class="row">
        <view class="row-main">
          <view class="row-title">{{ e.title }}<text v-if="e.bookingId" class="tag">来自预订</text></view>
          <view class="muted small">{{ CATS[e.category] }}{{ e.spentOn ? ` · ${e.spentOn}` : "" }}{{ e.note ? ` · ${e.note}` : "" }}</view>
        </view>
        <text class="row-amt">{{ money(e.amount, e.currency) }}</text>
        <text class="link" @click="openForm(e)">编辑</text>
        <text class="link danger" @click="remove(e)">删除</text>
      </view>
    </view>

    <view v-if="form" class="mask" @click.self="form = null">
      <view class="sheet" role="dialog" :aria-label="form.id ? '编辑支出' : '记一笔支出'">
        <view class="c-title">{{ form.id ? "编辑支出" : "记一笔支出" }}</view>
        <view class="field">
          <text class="label">分类</text>
          <view class="chips">
            <text v-for="(label, k) in CATS" :key="k" class="chip-opt" :class="{ on: form.category === k }" @click="form.category = k">{{ label }}</text>
          </view>
        </view>
        <view class="field">
          <text class="label">名称</text>
          <input id="exp-title" v-model="form.title" class="input" maxlength="120" placeholder="比如：酒店两晚、晚饭火锅" />
        </view>
        <view class="field-row">
          <view class="field grow">
            <text class="label">金额</text>
            <input id="exp-amount" v-model="form.amount" class="input" type="digit" placeholder="0.00" />
          </view>
          <view class="field">
            <text class="label">币种</text>
            <picker :range="CURRENCIES" :value="CURRENCIES.indexOf(form.currency)" @change="form.currency = CURRENCIES[Number($event.detail.value)]">
              <view class="input picker">{{ form.currency }}</view>
            </picker>
          </view>
        </view>
        <view class="field">
          <text class="label">日期（可不填）</text>
          <picker mode="date" :value="form.spentOn || today" @change="form.spentOn = $event.detail.value">
            <view class="input picker">{{ form.spentOn || "选择日期" }}<text v-if="form.spentOn" class="link clear" @click.stop="form.spentOn = ''">清除</text></view>
          </picker>
        </view>
        <view v-if="linkable.length" class="field">
          <text class="label">关联预订（可不选，同一条预订只能记一笔）</text>
          <picker :range="linkLabels" :value="linkIndex" @change="pickLink(Number($event.detail.value))">
            <view class="input picker">{{ linkLabels[linkIndex] }}</view>
          </picker>
        </view>
        <view class="field">
          <text class="label">备注（可不填）</text>
          <input id="exp-note" v-model="form.note" class="input" maxlength="500" />
        </view>
        <view v-if="formError" class="err">{{ formError }}</view>
        <view class="acts">
          <button class="btn ghost" @click="form = null">取消</button>
          <button class="btn" :disabled="saving" @click="save">{{ saving ? "保存中…" : "保存" }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { Trip, TripBooking, TripExpense } from "../api/types";
import { CURRENCIES, money } from "../utils/money";
import { ymd } from "../utils/format";

/**
 * 行程详情的「费用」标签：总预算、已记录支出、预算余额，支出构成和明细（增删改）。
 * 没记录不等于花了 0 元；不同币种没有统一汇率，分开统计，余额只按预算的币种算。
 * 记账时可以关联一条预订，一条预订只能记一笔，避免和预订金额重复计算。
 */
const props = defineProps<{ trip: Trip; estimate: { perPerson: number; stayPerRoom: number } }>();
const emit = defineEmits<{ (e: "edit-info"): void }>();

const CATS: Record<TripExpense["category"], string> = { TRANSPORT: "交通", STAY: "住宿", FOOD: "餐饮", TICKET: "门票", SHOPPING: "购物", OTHER: "其他" };
const today = ymd(new Date());

const expenses = ref<TripExpense[]>([]);
const bookings = ref<TripBooking[]>([]);
const loaded = ref(false);
const saving = ref(false);
const formError = ref("");
const form = ref<{ id?: number; category: TripExpense["category"]; title: string; amount: string; currency: string; spentOn: string; bookingId: number | null; note: string } | null>(null);

const budgetCur = computed(() => props.trip.budgetCurrency || "CNY");
const remaining = computed(() => Number(props.trip.budget || 0) - spentIn(budgetCur.value));
const otherCurrencies = computed(() => [...new Set(expenses.value.map((e) => e.currency))].filter((c) => c !== budgetCur.value));
const breakdown = computed(() => {
  const sums = new Map<TripExpense["category"], number>();
  for (const e of expenses.value) if (e.currency === budgetCur.value) sums.set(e.category, (sums.get(e.category) || 0) + Number(e.amount));
  const max = Math.max(1, ...sums.values());
  return [...sums.entries()].sort((a, b) => b[1] - a[1]).map(([cat, sum]) => ({ cat, sum, pct: Math.round((sum / max) * 100) }));
});
/** 能关联的预订：还没记过账的，加上正在改的这笔已经关联的 */
const linkable = computed(() => bookings.value.filter((b) => !b.expenseId || b.id === form.value?.bookingId));
const linkLabels = computed(() => ["不关联", ...linkable.value.map((b) => `${b.title}${b.amount != null ? ` · ${money(b.amount, b.currency)}` : ""}`)]);
const linkIndex = computed(() => {
  const i = linkable.value.findIndex((b) => b.id === form.value?.bookingId);
  return i < 0 ? 0 : i + 1;
});

onMounted(load);
defineExpose({ load });

async function load() {
  try {
    const [es, bs] = await Promise.all([api.tripExpenses(props.trip.id), api.tripBookings(props.trip.id)]);
    expenses.value = es;
    bookings.value = bs;
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
}

function spentIn(cur: string): number {
  return expenses.value.filter((e) => e.currency === cur).reduce((s, e) => s + Number(e.amount), 0);
}

function openForm(e?: TripExpense) {
  formError.value = "";
  form.value = e
    ? { id: e.id, category: e.category, title: e.title, amount: String(e.amount), currency: e.currency, spentOn: e.spentOn || "", bookingId: e.bookingId ?? null, note: e.note || "" }
    : { category: "FOOD", title: "", amount: "", currency: budgetCur.value, spentOn: today, bookingId: null, note: "" };
}

/** 选了一条预订：没填的名称、金额、分类按预订补上 */
function pickLink(i: number) {
  if (!form.value) return;
  const b = i === 0 ? null : linkable.value[i - 1];
  form.value.bookingId = b?.id ?? null;
  if (!b) return;
  if (!form.value.title) form.value.title = b.title;
  if (!form.value.amount && b.amount != null) form.value.amount = String(b.amount);
  form.value.currency = b.currency;
  form.value.category = b.kind === "FLIGHT" ? "TRANSPORT" : b.kind === "HOTEL" ? "STAY" : b.kind === "TICKET" ? "TICKET" : form.value.category;
}

async function save() {
  const f = form.value;
  if (!f) return;
  if (!f.title.trim()) return void (formError.value = "请填写名称");
  if (!/^\d+(\.\d{1,2})?$/.test(f.amount.trim())) return void (formError.value = "金额填数字，最多两位小数");
  formError.value = "";
  saving.value = true;
  const body = { category: f.category, title: f.title.trim(), amount: f.amount.trim(), currency: f.currency, spentOn: f.spentOn || null, bookingId: f.bookingId, note: f.note.trim() || null };
  try {
    if (f.id) await api.updateExpense(props.trip.id, f.id, body);
    else await api.createExpense(props.trip.id, body);
    form.value = null;
    uni.showToast({ title: f.id ? "已保存" : "已记录", icon: "none" });
    await load();
  } catch (e) {
    formError.value = e instanceof Error ? e.message : String(e);
  } finally {
    saving.value = false;
  }
}

function remove(e: TripExpense) {
  uni.showModal({
    title: "删除这笔支出",
    content: `删除「${e.title}」${money(e.amount, e.currency)}？`,
    confirmText: "删除",
    confirmColor: "#C4372B",
    success: async (r) => {
      if (!r.confirm) return;
      try {
        await api.deleteExpense(props.trip.id, e.id);
        await load();
      } catch (err) {
        showError(err);
      }
    },
  });
}
</script>

<style scoped>
.ledger { display: flex; flex-direction: column; gap: 16px; }
.stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); }
.stat { padding: 16px 20px; border-right: 1px solid var(--line); min-width: 0; }
.stat:last-child { border-right: none; }
.stat-label { font-size: 13px; color: var(--muted); }
.stat-num { font-size: 24px; font-weight: 700; font-variant-numeric: tabular-nums; margin-top: 2px; }
.stat-num.over { color: var(--danger); }
.stat-empty { font-size: 15px; color: var(--muted); margin-top: 6px; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 16px 20px; display: flex; flex-direction: column; gap: 10px; }
.c-head { display: flex; align-items: center; justify-content: space-between; }
.c-head .btn { margin: 0; }
.c-title { font-size: 15px; font-weight: 600; }
.bar-row { display: grid; grid-template-columns: 48px minmax(0, 1fr) 100px; align-items: center; gap: 12px; font-size: 14px; }
.bar { height: 8px; background: var(--surface-2); border-radius: 4px; overflow: hidden; }
.bar-in { height: 100%; background: var(--accent); border-radius: 4px; }
.bar-num { text-align: right; font-variant-numeric: tabular-nums; }
.empty-box { font-size: 14px; color: var(--muted); padding: 8px 0; }
.row { display: flex; align-items: center; gap: 14px; padding-top: 10px; border-top: 1px solid var(--line); font-size: 14px; }
.c-head + .row, .empty-box + .row { border-top: none; }
.row-main { flex: 1; min-width: 0; }
.row-title { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-amt { font-weight: 600; font-variant-numeric: tabular-nums; white-space: nowrap; }
.tag { margin-left: 6px; font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--surface-2); color: var(--muted); font-weight: 400; }
.link { color: var(--accent); cursor: pointer; font-size: 13px; white-space: nowrap; }
.link.danger { color: var(--danger); }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.mask { position: fixed; inset: 0; z-index: 100; background: rgba(0, 0, 0, 0.3); display: flex; align-items: center; justify-content: center; padding: 16px; }
.sheet { width: 100%; max-width: 480px; max-height: 90vh; overflow-y: auto; background: var(--surface); border-radius: 14px; padding: 20px; display: flex; flex-direction: column; gap: 14px; box-sizing: border-box; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-row { display: flex; gap: 12px; }
.grow { flex: 1; }
.label { font-size: 13px; color: var(--muted); }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip-opt { padding: 5px 12px; border-radius: 999px; border: 1px solid var(--line); font-size: 13px; cursor: pointer; }
.chip-opt.on { border-color: var(--accent); color: var(--accent); background: var(--accent-soft); }
.picker { display: flex; align-items: center; justify-content: space-between; min-width: 90px; }
.clear { font-size: 12px; }
.err { font-size: 13px; color: var(--danger); }
.acts { display: flex; justify-content: flex-end; gap: 10px; }
.acts .btn { margin: 0; }
@media (max-width: 767px) {
  .stats { grid-template-columns: 1fr; }
  .stat { border-right: none; border-bottom: 1px solid var(--line); padding: 12px 16px; }
  .stat:last-child { border-bottom: none; }
  .stat-num { font-size: 20px; }
  .card { padding: 14px 16px; }
  .row { flex-wrap: wrap; gap: 6px 12px; }
  .row-main { flex-basis: 100%; }
  .mask { align-items: flex-end; padding: 0; }
  .sheet { max-width: none; border-radius: 14px 14px 0 0; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
}
</style>
