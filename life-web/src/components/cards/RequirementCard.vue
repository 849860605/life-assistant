<template>
  <view class="xcard req">
    <view class="rows">
      <view class="row">
        <Icon name="pin" :size="16" color="var(--accent)" />
        <text class="label">目的地</text>
        <input id="req-destination" v-model="form.destination" class="value" :disabled="locked" placeholder="比如：成都" />
      </view>
      <view class="row">
        <Icon name="calendar" :size="16" color="var(--accent)" />
        <text class="label">出发时间</text>
        <view class="value dates">
          <picker mode="date" :value="form.startDate" :disabled="locked" @change="pickStart($event.detail.value)">
            <text :class="{ ph: !form.startDate }">{{ form.startDate ? md(form.startDate) : "开始" }}</text>
          </picker>
          <text class="muted">–</text>
          <picker mode="date" :value="form.endDate" :disabled="locked" @change="form.endDate = $event.detail.value">
            <text :class="{ ph: !form.endDate }">{{ form.endDate ? md(form.endDate) : "结束" }}</text>
          </picker>
          <text v-if="nights" class="muted">（{{ nights + 1 }}天{{ nights }}晚）</text>
          <text v-else-if="days && !form.startDate" class="muted">（玩 {{ days }} 天，选好开始日期自动算结束）</text>
        </view>
      </view>
      <view class="row">
        <Icon name="depart" :size="16" color="var(--accent)" />
        <text class="label">出发城市</text>
        <input id="req-origin" v-model="form.origin" class="value" :disabled="locked" placeholder="比如：深圳" />
      </view>
      <view class="row">
        <Icon name="users" :size="16" color="var(--accent)" />
        <text class="label">同行人数</text>
        <view class="value counts">
          <view v-for="c in COUNTS" :key="c.key" class="count">
            <text class="step" @click="bump(c.key, -1)">−</text>
            <text class="count-no">{{ form[c.key] }}{{ c.unit }}</text>
            <text class="step" @click="bump(c.key, 1)">+</text>
          </view>
        </view>
      </view>
      <view class="row">
        <Icon name="wallet" :size="16" color="var(--accent)" />
        <text class="label">预算（人均）</text>
        <view class="value budget">
          <text class="muted">约</text>
          <input id="req-budget" v-model="form.budget" type="number" class="budget-input" :disabled="locked" placeholder="5000" />
          <text class="muted">元</text>
        </view>
      </view>
      <view class="row block">
        <view class="row-head"><Icon name="heart" :size="16" color="var(--accent)" /><text class="label">旅行偏好（可多选）</text></view>
        <view class="options">
          <text v-for="o in data.interestOptions" :key="o" class="option" :class="{ on: form.interests.includes(o) }" @click="toggle(o)">{{ o }}</text>
        </view>
      </view>
      <view class="row block">
        <view class="row-head"><Icon name="flag" :size="16" color="var(--accent)" /><text class="label">已去过的地方（选填）</text></view>
        <view class="options">
          <text v-for="(v, i) in form.visited" :key="v" class="option on" @click="removeVisited(i)">{{ v }} ×</text>
          <input id="req-visited" v-model="visitedInput" class="visited-input" :disabled="locked" placeholder="输入后回车添加" @confirm="addVisited" />
        </view>
      </view>
      <view class="row block">
        <view class="row-head"><Icon name="plane" :size="16" color="var(--accent)" /><text class="label">大交通</text></view>
        <view class="options">
          <text v-for="t in TRANSPORTS" :key="t" class="option" :class="{ on: form.transport === t }" @click="!locked && (form.transport = t)">{{ t }}</text>
        </view>
        <view v-if="form.transport === SMART && suggest" class="hint">推荐{{ suggest }}：{{ props.data.suggestReason }}</view>
        <view v-if="mode === '飞机'" class="options sub">
          <text v-for="t in TRANSFERS" :key="t" class="option" :class="{ on: form.transfer === t }" @click="!locked && (form.transfer = t)">{{ t }}</text>
        </view>
      </view>
      <view class="row block">
        <view class="row-head"><Icon name="route" :size="16" color="var(--accent)" /><text class="label">节奏</text></view>
        <view class="options">
          <text v-for="p in PACES" :key="p" class="option" :class="{ on: form.pace === p }" @click="!locked && (form.pace = p)">{{ p }}</text>
        </view>
      </view>
    </view>
    <view v-if="editing" class="actions">
      <button class="btn go" :disabled="!form.destination.trim() || !changes.length" @click="submit">{{ changes.length ? "按修改重新生成" : "还没改动" }}</button>
      <text class="skip link" @click="editing = false">取消</text>
    </view>
    <view v-else-if="!locked" class="actions">
      <button class="btn go" :disabled="!form.destination.trim()" @click="submit">开始生成方案</button>
      <text class="skip link" @click="skip">按已选的直接生成，没填的你定</text>
    </view>
    <view v-else class="muted done">
      <text>{{ submitted ? "已提交" : "这张需求卡已过期" }}</text>
      <text class="link redo" @click="startEdit">修改需求</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import Icon from "../Icon.vue";

/**
 * 需求卡：出游技能 askTripRequirements 发来的，已知信息预先填好；提交时拼成一句话当作用户消息发出去。
 * active 为 false（不是最新一条回答，或正在发送）时只读；提交过或过期的卡片可以点"修改需求"改了再发，
 * 发出去的话里带上"这次改了什么"，星络按新需求重新生成。
 */
const props = defineProps<{ data: any; active: boolean }>();
const emit = defineEmits<{ (e: "submit", text: string): void }>();

type CountKey = "adults" | "children" | "elders";
const COUNTS: { key: CountKey; unit: string }[] = [
  { key: "adults", unit: " 成人" },
  { key: "children", unit: " 儿童" },
  { key: "elders", unit: " 老人" },
];
const PACES = ["轻松", "适中", "紧凑"];
/** 交通方式："智能推荐"按两地距离选（后端给出推荐和理由），用户也可以自己选 */
const SMART = "智能推荐";
const TRANSPORTS = [SMART, "地铁公交", "高铁火车", "飞机", "自驾"];
/** 坐飞机时能不能接受中转；换城市进出这类大改都要先问用户 */
const TRANSFERS = ["尽量直飞", "中转也行", "只要直飞"];
/** 后端按距离给的推荐（地铁公交 / 高铁火车 / 飞机），没有出发地或目的地时为空 */
const suggest: string = props.data.suggestTransport || "";

const submitted = ref(false);
/** 提交过或过期后，用户点了"修改需求"，正在改 */
const editing = ref(false);
const locked = computed(() => !editing.value && (!props.active || submitted.value));
const form = reactive({
  destination: props.data.destination || "",
  origin: props.data.origin || "",
  startDate: props.data.startDate || "",
  endDate: props.data.endDate || "",
  adults: props.data.adults ?? 2,
  children: props.data.children ?? 0,
  elders: props.data.elders ?? 0,
  budget: props.data.budgetPerPerson ? String(props.data.budgetPerPerson) : "",
  interests: [...(props.data.interests || [])] as string[],
  pace: props.data.pace || "适中",
  transfer: TRANSFERS.includes(props.data.transfer) ? props.data.transfer : "尽量直飞",
  transport: TRANSPORTS.includes(props.data.transport) ? props.data.transport : SMART,
  visited: [...(props.data.visited || [])] as string[],
});
const visitedInput = ref("");
/** 实际按哪种交通规划：智能推荐时取推荐的那种 */
const mode = computed(() => (form.transport === SMART ? suggest : form.transport));
/** 用户只说了玩几天、没说日期时的天数 */
const days = props.data.days || 0;

/** 选开始日期；只知道玩几天、结束日期还没填时顺手算出结束日期 */
function pickStart(date: string) {
  form.startDate = date;
  if (days > 0 && !form.endDate) {
    const end = new Date(Date.parse(date) + (days - 1) * 86400000);
    form.endDate = end.toISOString().slice(0, 10);
  }
}
/** 住几晚，日期没填全时为 0 */
const nights = computed(() => {
  if (!form.startDate || !form.endDate) return 0;
  const n = Math.round((Date.parse(form.endDate) - Date.parse(form.startDate)) / 86400000);
  return n > 0 ? n : 0;
});

/** "2026-03-10" 显示成"3月10日"。 */
function md(date: string) {
  return `${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日`;
}

function addVisited() {
  const v = visitedInput.value.trim();
  if (v && !form.visited.includes(v)) form.visited.push(v);
  visitedInput.value = "";
}

function removeVisited(i: number) {
  if (!locked.value) form.visited.splice(i, 1);
}

function bump(key: CountKey, delta: number) {
  if (locked.value) return;
  form[key] = Math.max(key === "adults" ? 1 : 0, Math.min(20, form[key] + delta));
}

function toggle(option: string) {
  if (locked.value) return;
  const i = form.interests.indexOf(option);
  if (i >= 0) form.interests.splice(i, 1);
  else form.interests.push(option);
}

/** 各项需求的文字，key 是项目名（"日期""节奏"…），没填的项不写。 */
function fields(): Record<string, string> {
  const people = [`${form.adults} 位成人`];
  if (form.children) people.push(`${form.children} 个小孩`);
  if (form.elders) people.push(`${form.elders} 位老人`);
  const out: Record<string, string> = { 目的地: form.destination.trim() };
  if (form.origin.trim()) out["出发地"] = form.origin.trim();
  if (form.startDate) out["日期"] = `${form.startDate}${form.endDate ? " 至 " + form.endDate : ""}`;
  else out["日期"] = days ? `日期未定，玩 ${days} 天` : "日期未定";
  out["同行"] = people.join("、");
  if (form.budget) out["预算"] = `每人 ${form.budget} 元`;
  if (form.interests.length) out["偏好"] = form.interests.join("、");
  out["节奏"] = form.pace;
  if (form.transport === SMART) out["大交通"] = suggest ? `智能推荐：${suggest}` : "智能推荐（按距离选）";
  else out["大交通"] = form.transport;
  if (mode.value === "飞机") out["大交通"] += `，${form.transfer}（要中转、换城市进出先给我选）`;
  if (form.visited.length) out["去过"] = `${form.visited.join("、")}（不要再安排）`;
  return out;
}

/** 上次发出去的需求；没发过时用卡片刚弹出来时的内容，修改时和它比 */
const initial = fields();
const lastSent = ref<Record<string, string> | null>(null);

/** 和上次相比改了哪几项，如"节奏 适中 → 紧凑" */
const changes = computed(() => {
  const now = fields();
  const before = lastSent.value || initial;
  return [...new Set([...Object.keys(before), ...Object.keys(now)])]
    .filter((k) => before[k] !== now[k])
    .map((k) => (now[k] ? `${k} ${before[k] ? before[k] + " → " : ""}${now[k]}` : `去掉${k}`));
});

function text(f: Record<string, string>) {
  return Object.entries(f).map(([k, v]) => `${k} ${v}`).join("；");
}

/** 提交：第一次发完整需求；修改时也发完整需求，再说清这次改了什么，免得星络沿用旧的 */
function submit() {
  addVisited();
  const f = fields();
  const msg = editing.value
    ? `需求修改：${text(f)}。这次改了：${changes.value.join("；")}。请按新需求重新生成方案。`
    : `需求确认：${text(f)}。请按这个生成方案。`;
  lastSent.value = f;
  submitted.value = true;
  editing.value = false;
  emit("submit", msg);
}

/** 跳过：已选的照样带上，没填的让星络自己定 */
function skip() {
  addVisited();
  const f = fields();
  lastSent.value = f;
  submitted.value = true;
  emit("submit", `需求确认：${text(f)}。没填的你看着定，直接生成方案。`);
}

/** 提交过或过期的卡片重新打开编辑 */
function startEdit() {
  editing.value = true;
}
</script>

<style scoped>
.req { padding: 4px 0 0; }
.rows { padding: 0 14px; }
.row { display: flex; align-items: center; gap: 8px; padding: 11px 0; border-bottom: 1px solid var(--line); font-size: 14px; }
.row.block { flex-direction: column; align-items: stretch; gap: 8px; }
.row-head { display: flex; align-items: center; gap: 8px; }
.label { color: var(--ink); white-space: nowrap; }
.value { flex: 1; min-width: 0; text-align: right; font-size: 14px; color: var(--ink); }
.dates, .counts, .budget { display: flex; align-items: center; justify-content: flex-end; gap: 6px; flex-wrap: wrap; }
.ph { color: var(--muted); }
.count { display: flex; align-items: center; border: 1px solid var(--line); border-radius: 8px; }
.step { width: 24px; text-align: center; color: var(--accent); cursor: pointer; line-height: 26px; }
.count-no { font-size: 13px; font-variant-numeric: tabular-nums; }
.budget-input { width: 64px; text-align: right; }
.options { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.option { font-size: 13px; padding: 5px 12px; border-radius: 999px; border: 1px solid var(--line); background: var(--surface-2); cursor: pointer; }
.option.on { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
.visited-input { flex: 1; min-width: 120px; font-size: 13px; height: 30px; }
.actions { display: flex; flex-direction: column; align-items: stretch; gap: 10px; padding: 14px; }
.go { margin: 0; height: 44px; }
.skip { text-align: center; font-size: 14px; }
.done { display: flex; justify-content: center; gap: 12px; font-size: 13px; padding: 12px; }
.redo { cursor: pointer; }
.hint { font-size: 12px; color: var(--muted); margin-top: 6px; }
.options.sub { margin-top: 8px; }
</style>
