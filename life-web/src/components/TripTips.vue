<template>
  <view v-if="items.length || text" class="tt">
    <view class="tt-head" @click="open = !open">
      <text class="tt-title">出发前注意<text v-if="items.length" class="tt-count"> · {{ items.length }} 条</text></text>
      <text class="tt-toggle">{{ open ? "收起" : "展开" }}<Icon name="down" :size="12" color="var(--accent)" :class="{ flip: open }" /></text>
    </view>
    <view v-if="items.length" class="tt-list">
      <view v-for="(t, i) in items" :key="i" class="tt-item" :class="{ open }" @click="open = !open">
        <view class="tt-icon" :class="t.kind"><Icon :name="ICON[t.kind]" :size="14" :color="`var(--tt-${t.kind})`" /></view>
        <view class="tt-text">
          <text v-if="open" class="tt-label">{{ t.title }}</text>
          <text class="tt-line">{{ open ? t.body : t.brief }}</text>
        </view>
      </view>
    </view>
    <!-- 拆不出条目的（模型没按"标题：内容"写），折起来时只露出开头 -->
    <view v-else class="tt-raw" :class="{ open }"><MarkdownView :text="text" /></view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import Icon from "./Icon.vue";
import MarkdownView from "./MarkdownView.vue";

/**
 * 行程的注意事项，放在行程最前面：默认每条只显示一句要点（要做什么），点开看完整说明。
 * 按多急排：出发前就要决定的（交通、省钱、预约）在前，带什么衣服其次，到了当地才用得上的（安全）在后。
 * 美食推荐不算注意事项（页面上有专门的美食推荐），不放进来。
 */
const props = defineProps<{ text: string }>();
const open = ref(false);

type Kind = "go" | "book" | "wear" | "safe" | "other";
const ICON: Record<Kind, string> = { go: "plane", book: "ticket", wear: "weather", safe: "shield", other: "flag" };
const ORDER: Kind[] = ["go", "book", "wear", "safe", "other"];

/** 标题、内容里有这些词就归到这一类（按顺序先命中先算） */
const KINDS: [Kind, RegExp][] = [
  ["wear", /天气|穿衣|气温|衣物|保暖|防晒|下雨|降雨/],
  ["go", /交通|航班|机票|机场|出发|高铁|火车|省钱|费用|预算/],
  ["book", /预约|门票|订票|预订|抢票|闭馆|开放/],
  ["safe", /安全|防滑|小心|注意身体|高反|治安|防盗|健康/],
];
const FOOD = /美食|必吃|必尝|小吃|餐厅推荐/;
/** 句子里有这些词，说明是"要做什么"，优先当要点 */
const ACTION = /带|备|准备|穿|提前|记得|建议|可选|可以|省|别|不要|不宜|注意|预约|避开/;

interface Tip { kind: Kind; title: string; body: string; brief: string }

/** "**天气与穿衣**：……" 或 "- 天气与穿衣：……" 一行一条 */
const items = computed<Tip[]>(() => {
  const out: Tip[] = [];
  for (const raw of (props.text || "").split(/\n+/)) {
    const line = raw.replace(/^\s*(?:[-*•]|\d+[.、])\s*/, "").trim();
    const m = line.match(/^\*{0,2}([^*：:]{2,12})\*{0,2}\s*[：:]\s*(.+)$/);
    if (!m) continue;
    const title = m[1].trim();
    const body = m[2].replace(/\*\*/g, "").trim();
    if (FOOD.test(title)) continue;
    const kind = KINDS.find(([, re]) => re.test(title))?.[0] || KINDS.find(([, re]) => re.test(body))?.[0] || "other";
    out.push({ kind, title, body, brief: brief(kind, body) });
  }
  return out.sort((a, b) => ORDER.indexOf(a.kind) - ORDER.indexOf(b.kind));
});

/** 一句要点：优先挑"要做什么"的那句；穿衣那条在前面带上气温 */
function brief(kind: Kind, body: string): string {
  const sentences = body.split(/[。；;！!]/).map((s) => s.trim()).filter(Boolean);
  let s = sentences.find((x) => ACTION.test(x)) || sentences[0] || body;
  // 括号里的举例、"但需……"这类附加条件留到展开后再看；能省钱的那半句放最前面
  const clauses = s.replace(/[（(][^）)]*[）)]/g, "").replace(/^请/, "").split(/[，,]/).map((c) => c.trim()).filter((c) => c && !/^(但|不过)/.test(c));
  const save = clauses.findIndex((c) => /省.{0,4}\d+\s*(元|块)|省.{0,3}[¥￥]\d+/.test(c));
  if (save > 0) clauses.unshift(...clauses.splice(save, 1));
  s = clauses.join("，") || s;
  if (kind === "wear") {
    const temp = body.match(/-?\d+\s*°?C?\s*[至到~～-]\s*-?\d+\s*°C/);
    if (temp && !s.includes(temp[0])) s = `${temp[0].replace(/\s/g, "")}，${s}`;
  }
  return s;
}
</script>

<style scoped>
.tt {
  --tt-go: var(--accent);
  --tt-book: var(--accent);
  --tt-wear: #2b7fd8;
  --tt-safe: var(--warn);
  --tt-other: var(--muted);
  background: var(--surface); border: 1px solid var(--line); border-radius: 14px; padding: 12px 14px;
}
.tt-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; cursor: pointer; }
.tt-title { font-size: 14px; font-weight: 700; }
.tt-count { font-weight: 400; color: var(--muted); }
.tt-toggle { display: inline-flex; align-items: center; gap: 2px; font-size: 13px; color: var(--accent); }
.flip { transform: rotate(180deg); }
.tt-list { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.tt-item { display: flex; align-items: flex-start; gap: 10px; cursor: pointer; }
.tt-icon { flex-shrink: 0; width: 24px; height: 24px; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: var(--surface-2); }
.tt-icon.go, .tt-icon.book { background: var(--accent-soft); }
.tt-icon.safe { background: var(--warn-soft); }
.tt-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; padding-top: 2px; }
.tt-label { font-size: 13px; font-weight: 700; }
.tt-line { font-size: 14px; line-height: 1.55; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tt-item.open .tt-line { white-space: normal; }
.tt-item.open + .tt-item.open { padding-top: 8px; border-top: 1px dashed var(--line); }
.tt-raw { margin-top: 8px; max-height: 4.8em; overflow: hidden; }
.tt-raw.open { max-height: none; }
</style>
