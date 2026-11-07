<template>
  <view class="bk">
    <view class="bk-head">
      <view class="seg">
        <view v-for="k in KIND_TABS" :key="k.key" class="seg-item" :class="{ on: kind === k.key }" @click="kind = k.key">
          {{ k.label }}<text v-if="countOf(k.key)" class="cnt">{{ countOf(k.key) }}</text>
        </view>
      </view>
      <button class="btn small" @click="openForm()">添加预订</button>
    </view>

    <view v-if="!loaded" class="muted small">加载中…</view>
    <view v-else-if="!shown.length" class="empty-box">
      {{ bookings.length ? "这一类还没有预订" : "还没有预订记录。订好机票、酒店或门票后在这里记一下，凭证（截图、PDF）也可以传上来。" }}
    </view>

    <view v-for="b in shown" :key="b.id" class="item" :class="{ cancelled: b.status === 'CANCELLED' }">
      <view class="item-top">
        <Icon :name="KIND_ICON[b.kind]" :size="18" color="var(--muted)" />
        <view class="item-main">
          <view class="item-title">{{ b.title }}</view>
          <view class="muted small">{{ [b.platform, timeRange(b), b.people ? `${b.people} 人` : "", b.location].filter(Boolean).join(" · ") }}</view>
        </view>
        <text v-if="b.amount != null" class="item-amt">{{ money(b.amount, b.currency) }}</text>
      </view>
      <view class="item-tags">
        <text class="tag" :class="b.status">{{ STATUS[b.status] }}</text>
        <text class="tag src" :class="b.source">{{ SOURCE[b.source] }}</text>
        <text v-if="b.bookingNo" class="muted small">订单号 {{ b.bookingNo }}</text>
        <text v-if="b.files.length" class="link small" @click="openFile(b.files[0].id)">凭证 {{ b.files.length }} 个</text>
      </view>
      <view class="item-acts">
        <text v-if="b.expenseId" class="muted small">已记账</text>
        <text v-else-if="b.amount != null && b.status !== 'CANCELLED'" class="link" @click="addExpense(b)">记一笔支出</text>
        <text class="link" @click="openForm(b)">{{ b.source === "SYNC" ? "查看" : "查看和编辑" }}</text>
        <text class="link danger" @click="remove(b)">删除</text>
      </view>
    </view>
    <view class="muted small">"已预订"是你自己标记的，星络没有替你下单，以平台订单为准；只有平台同步来的记录才算平台确认。</view>

    <view v-if="form" class="mask" @click.self="form = null">
      <view class="sheet" role="dialog" :aria-label="form.id ? '编辑预订' : '添加预订'">
        <view class="c-title">{{ form.id ? "预订详情" : "添加预订" }}<text class="muted small"> · {{ SOURCE[form.source] }}</text></view>
        <view class="field">
          <text class="label">类型</text>
          <view class="chips">
            <text v-for="k in KIND_TABS.slice(1)" :key="k.key" class="chip-opt" :class="{ on: form.kind === k.key }" @click="form.kind = k.key as TripBooking['kind']">{{ k.label }}</text>
          </view>
        </view>
        <view class="field">
          <text class="label">名称</text>
          <input id="bk-title" v-model="form.title" class="input" maxlength="120" placeholder="比如：CA1234 北京→成都、春熙路某酒店" />
        </view>
        <view class="field-row">
          <view class="field grow"><text class="label">平台</text><input id="bk-platform" v-model="form.platform" class="input" maxlength="40" placeholder="携程、飞猪、官网…" /></view>
          <view class="field grow"><text class="label">订单号</text><input id="bk-no" v-model="form.bookingNo" class="input" maxlength="80" /></view>
        </view>
        <view class="field-row">
          <view class="field grow">
            <text class="label">开始</text>
            <view class="dt">
              <picker mode="date" :value="form.startDate || today" @change="form.startDate = $event.detail.value"><view class="input picker">{{ form.startDate || "日期" }}</view></picker>
              <picker mode="time" :value="form.startTime || '09:00'" @change="form.startTime = $event.detail.value"><view class="input picker">{{ form.startTime || "时间" }}</view></picker>
            </view>
          </view>
          <view class="field grow">
            <text class="label">结束（可不填）</text>
            <view class="dt">
              <picker mode="date" :value="form.endDate || form.startDate || today" @change="form.endDate = $event.detail.value"><view class="input picker">{{ form.endDate || "日期" }}</view></picker>
              <picker mode="time" :value="form.endTime || '12:00'" @change="form.endTime = $event.detail.value"><view class="input picker">{{ form.endTime || "时间" }}</view></picker>
            </view>
          </view>
        </view>
        <view class="field-row">
          <view class="field"><text class="label">人数</text><input id="bk-people" v-model="form.people" class="input narrow" type="number" maxlength="2" /></view>
          <view class="field grow"><text class="label">金额</text><input id="bk-amount" v-model="form.amount" class="input" type="digit" placeholder="0.00" /></view>
          <view class="field">
            <text class="label">币种</text>
            <picker :range="CURRENCIES" :value="CURRENCIES.indexOf(form.currency)" @change="form.currency = CURRENCIES[Number($event.detail.value)]">
              <view class="input picker">{{ form.currency }}</view>
            </picker>
          </view>
        </view>
        <view class="field"><text class="label">地点</text><input id="bk-location" v-model="form.location" class="input" maxlength="200" /></view>
        <view class="field">
          <text class="label">状态（你自己标记）</text>
          <view class="chips">
            <text v-for="(label, k) in STATUS" :key="k" class="chip-opt" :class="{ on: form.status === k }" @click="form.status = k as TripBooking['status']">{{ label }}</text>
          </view>
        </view>
        <view class="field">
          <text class="label">凭证（截图或 PDF，最多 5 个，只有你自己能看）</text>
          <view class="files">
            <view v-for="(f, i) in form.files" :key="f.key" class="file" :class="f.state">
              <text class="file-name" @click="f.id && openFile(f.id)">{{ f.name }}</text>
              <text class="muted small">{{ f.state === "uploading" ? "上传中…" : f.state === "error" ? f.error : "" }}</text>
              <view class="file-x" @click="form.files.splice(i, 1)"><Icon name="close" :size="12" color="var(--muted)" /></view>
            </view>
            <view v-if="form.files.length < 5" class="file add" @click="pickFiles"><Icon name="plus" :size="14" color="var(--muted)" /><text>上传凭证</text></view>
          </view>
        </view>
        <view class="field"><text class="label">备注</text><input id="bk-note" v-model="form.note" class="input" maxlength="500" /></view>
        <view v-if="formError" class="err">{{ formError }}</view>
        <view class="acts">
          <button class="btn ghost" @click="form = null">{{ form.source === "SYNC" ? "关闭" : "取消" }}</button>
          <button v-if="form.source !== 'SYNC'" class="btn" :disabled="saving || form.files.some((f) => f.state === 'uploading')" @click="save">{{ saving ? "保存中…" : "保存" }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { api } from "../api";
import { showError } from "../api/http";
import type { TripBooking } from "../api/types";
import Icon from "./Icon.vue";
import { fileUrl } from "../utils/attach";
import { ensureMediaTicket } from "../utils/media";
import { CURRENCIES, money } from "../utils/money";
import { ymd } from "../utils/format";

/**
 * 行程详情的「预订信息」标签：机票、酒店、门票分类，添加、查看编辑、删除，上传凭证（只有本人能看），
 * 按预订记一笔支出（一条预订只记一次）。来源分手动录入、凭证导入、平台同步，用户标记的"已预订"不冒充平台确认。
 */
const props = defineProps<{ tripId: number }>();
const emit = defineEmits<{ (e: "changed"): void }>();

const KIND_TABS = [
  { key: "ALL", label: "全部" },
  { key: "FLIGHT", label: "机票" },
  { key: "HOTEL", label: "酒店" },
  { key: "TICKET", label: "门票" },
  { key: "OTHER", label: "其他" },
];
const KIND_ICON: Record<TripBooking["kind"], string> = { FLIGHT: "plane", HOTEL: "bed", TICKET: "ticket", OTHER: "bookmark" };
const STATUS: Record<TripBooking["status"], string> = { PLANNED: "打算订", BOOKED: "已预订（我标记的）", CANCELLED: "已取消" };
const SOURCE: Record<TripBooking["source"], string> = { MANUAL: "手动录入", IMPORT: "凭证导入", SYNC: "平台同步" };
const today = ymd(new Date());

interface FileItem {
  key: number;
  id?: string;
  name: string;
  state: "ok" | "uploading" | "error";
  error?: string;
}
interface Form {
  id?: number;
  kind: TripBooking["kind"];
  title: string;
  platform: string;
  bookingNo: string;
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
  people: string;
  amount: string;
  currency: string;
  location: string;
  status: TripBooking["status"];
  source: TripBooking["source"];
  files: FileItem[];
  note: string;
}

const bookings = ref<TripBooking[]>([]);
const loaded = ref(false);
const kind = ref("ALL");
const form = ref<Form | null>(null);
const formError = ref("");
const saving = ref(false);
let seq = 0;

const shown = computed(() => (kind.value === "ALL" ? bookings.value : bookings.value.filter((b) => b.kind === kind.value)));

onMounted(load);
defineExpose({ load });

async function load() {
  try {
    bookings.value = await api.tripBookings(props.tripId);
  } catch (e) {
    showError(e);
  } finally {
    loaded.value = true;
  }
}

function countOf(k: string) {
  return k === "ALL" ? bookings.value.length : bookings.value.filter((b) => b.kind === k).length;
}

function timeRange(b: TripBooking): string {
  const f = (iso?: string | null) => {
    if (!iso) return "";
    const d = new Date(iso);
    const p = (n: number) => String(n).padStart(2, "0");
    return `${d.getMonth() + 1}月${d.getDate()}日 ${p(d.getHours())}:${p(d.getMinutes())}`;
  };
  return [f(b.startAt), f(b.endAt)].filter(Boolean).join(" – ");
}

function split(iso?: string | null): [string, string] {
  if (!iso) return ["", ""];
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return [ymd(d), `${p(d.getHours())}:${p(d.getMinutes())}`];
}

function openForm(b?: TripBooking) {
  formError.value = "";
  if (!b) {
    form.value = { kind: kind.value === "ALL" ? "FLIGHT" : (kind.value as TripBooking["kind"]), title: "", platform: "", bookingNo: "", startDate: "", startTime: "", endDate: "", endTime: "", people: "", amount: "", currency: "CNY", location: "", status: "BOOKED", source: "MANUAL", files: [], note: "" };
    return;
  }
  const [sd, st] = split(b.startAt);
  const [ed, et] = split(b.endAt);
  form.value = {
    id: b.id, kind: b.kind, title: b.title, platform: b.platform || "", bookingNo: b.bookingNo || "",
    startDate: sd, startTime: st, endDate: ed, endTime: et, people: b.people ? String(b.people) : "",
    amount: b.amount != null ? String(b.amount) : "", currency: b.currency, location: b.location || "",
    status: b.status, source: b.source, note: b.note || "",
    files: b.files.map((f) => ({ key: ++seq, id: f.id, name: f.name || "文件已删除", state: f.name ? "ok" : "error", error: f.name ? undefined : "找不到了" })),
  };
}

function pickFiles() {
  const f = form.value;
  if (!f) return;
  uni.chooseFile({
    count: 5 - f.files.length,
    extension: [".jpg", ".jpeg", ".png", ".webp", ".pdf"],
    success: (res) => {
      const list = (Array.isArray(res.tempFiles) ? res.tempFiles : [res.tempFiles]) as any[];
      for (const file of list.slice(0, 5 - f.files.length)) {
        const item = reactive<FileItem>({ key: ++seq, name: file.name || "凭证", state: "uploading" });
        f.files.push(item);
        api.uploadFile({ path: file.path, name: file.name, file: file instanceof File ? file : undefined })
          .then((v) => {
            item.id = v.id;
            item.state = "ok";
          })
          .catch((e) => {
            item.state = "error";
            item.error = e instanceof Error ? e.message : "上传失败";
          });
      }
    },
  });
}

async function openFile(id: string) {
  await ensureMediaTicket();
  window.open(fileUrl(id), "_blank");
}

async function save() {
  const f = form.value;
  if (!f) return;
  if (!f.title.trim()) return void (formError.value = "请填写名称");
  if (f.amount.trim() && !/^\d+(\.\d{1,2})?$/.test(f.amount.trim())) return void (formError.value = "金额填数字，最多两位小数");
  if (f.people.trim() && !/^\d{1,2}$/.test(f.people.trim())) return void (formError.value = "人数填 1 到 99");
  if ((f.startTime && !f.startDate) || (f.endTime && !f.endDate)) return void (formError.value = "填了时间也要选日期");
  if (f.files.some((x) => x.state === "error" && !x.id)) return void (formError.value = "有凭证没传上去，删掉或重新上传");
  const start = f.startDate ? `${f.startDate}T${f.startTime || "00:00"}:00+08:00` : null;
  const end = f.endDate ? `${f.endDate}T${f.endTime || "00:00"}:00+08:00` : null;
  if (start && end && end < start) return void (formError.value = "结束时间不能早于开始时间");
  formError.value = "";
  saving.value = true;
  const body = {
    kind: f.kind, title: f.title.trim(), platform: f.platform.trim() || null, startAt: start, endAt: end,
    people: f.people.trim() ? Number(f.people) : null, amount: f.amount.trim() || null, currency: f.currency,
    bookingNo: f.bookingNo.trim() || null, location: f.location.trim() || null, status: f.status,
    source: (f.source === "IMPORT" ? "IMPORT" : "MANUAL") as "MANUAL" | "IMPORT",
    fileIds: f.files.filter((x) => x.id && x.state === "ok").map((x) => x.id!), note: f.note.trim() || null,
  };
  try {
    if (f.id) await api.updateBooking(props.tripId, f.id, body);
    else await api.createBooking(props.tripId, body);
    form.value = null;
    uni.showToast({ title: "已保存", icon: "none" });
    await load();
    emit("changed");
  } catch (e) {
    formError.value = e instanceof Error ? e.message : String(e);
  } finally {
    saving.value = false;
  }
}

/** 按这条预订记一笔支出，金额币种照搬，分类按预订类型 */
async function addExpense(b: TripBooking) {
  try {
    await api.createExpense(props.tripId, {
      category: b.kind === "FLIGHT" ? "TRANSPORT" : b.kind === "HOTEL" ? "STAY" : b.kind === "TICKET" ? "TICKET" : "OTHER",
      title: b.title, amount: String(b.amount), currency: b.currency, spentOn: b.startAt ? ymd(new Date(b.startAt)) : null, bookingId: b.id,
    });
    uni.showToast({ title: "已记到费用里", icon: "none" });
    await load();
    emit("changed");
  } catch (e) {
    showError(e);
  }
}

function remove(b: TripBooking) {
  uni.showModal({
    title: "删除预订",
    content: `删除「${b.title}」？${b.expenseId ? "已经记的那笔支出会保留。" : ""}`,
    confirmText: "删除",
    confirmColor: "#C4372B",
    success: async (r) => {
      if (!r.confirm) return;
      try {
        await api.deleteBooking(props.tripId, b.id);
        await load();
        emit("changed");
      } catch (e) {
        showError(e);
      }
    },
  });
}
</script>

<style scoped>
.bk { display: flex; flex-direction: column; gap: 12px; }
.bk-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.bk-head .btn { margin: 0; }
.cnt { margin-left: 4px; font-size: 12px; color: var(--muted); }
.empty-box { font-size: 14px; color: var(--muted); padding: 24px 16px; text-align: center; background: var(--surface); border: 1px dashed var(--line); border-radius: var(--radius); }
.item { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 18px; display: flex; flex-direction: column; gap: 8px; }
.item.cancelled { opacity: 0.6; }
.item-top { display: flex; align-items: flex-start; gap: 12px; }
.item-main { flex: 1; min-width: 0; }
.item-title { font-size: 15px; font-weight: 600; }
.item-amt { font-weight: 600; font-variant-numeric: tabular-nums; white-space: nowrap; }
.item-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.tag { font-size: 12px; padding: 2px 8px; border-radius: 4px; background: var(--surface-2); color: var(--muted); }
.tag.BOOKED { background: var(--ok-soft); color: var(--ok); }
.tag.CANCELLED { background: var(--danger-soft); color: var(--danger); }
.tag.src.SYNC { background: var(--accent-soft); color: var(--accent); }
.item-acts { display: flex; justify-content: flex-end; align-items: center; gap: 16px; }
.link { color: var(--accent); cursor: pointer; font-size: 13px; white-space: nowrap; }
.link.danger { color: var(--danger); }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.c-title { font-size: 15px; font-weight: 600; }
.mask { position: fixed; inset: 0; z-index: 100; background: rgba(0, 0, 0, 0.3); display: flex; align-items: center; justify-content: center; padding: 16px; }
.sheet { width: 100%; max-width: 560px; max-height: 90vh; overflow-y: auto; background: var(--surface); border-radius: 14px; padding: 20px; display: flex; flex-direction: column; gap: 14px; box-sizing: border-box; }
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.field-row { display: flex; gap: 12px; }
.grow { flex: 1; }
.label { font-size: 13px; color: var(--muted); }
.narrow { width: 72px; }
.dt { display: flex; gap: 6px; }
.dt picker { flex: 1; }
.picker { display: flex; align-items: center; min-width: 72px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip-opt { padding: 5px 12px; border-radius: 999px; border: 1px solid var(--line); font-size: 13px; cursor: pointer; }
.chip-opt.on { border-color: var(--accent); color: var(--accent); background: var(--accent-soft); }
.files { display: flex; flex-wrap: wrap; gap: 8px; }
.file { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border-radius: 8px; background: var(--surface-2); font-size: 13px; max-width: 100%; }
.file.error { background: var(--danger-soft); color: var(--danger); }
.file.add { cursor: pointer; color: var(--muted); border: 1px dashed var(--line); background: transparent; }
.file-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 220px; cursor: pointer; }
.file-x { display: flex; cursor: pointer; }
.err { font-size: 13px; color: var(--danger); }
.acts { display: flex; justify-content: flex-end; gap: 10px; }
.acts .btn { margin: 0; }
@media (max-width: 767px) {
  .item { padding: 12px 14px; }
  .field-row { flex-wrap: wrap; }
  .mask { align-items: flex-end; padding: 0; }
  .sheet { max-width: none; border-radius: 14px 14px 0 0; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
}
</style>
