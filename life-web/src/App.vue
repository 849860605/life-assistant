<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { getToken } from "./api/http";

// 没登录直接去登录页
onLaunch(() => {
  if (!getToken()) {
    uni.reLaunch({ url: "/pages/login/login" });
  }
});
</script>

<style>
/*
 * 版式：移动优先，单列内容最宽 760px 居中；对话页在宽屏上左边多一栏会话列表。
 * 配色取户外的松绿和石灰：中性色都略偏绿，强调色只用在主操作和技能标签上。
 */
page {
  --bg: #f4f6f5;
  --surface: #ffffff;
  --surface-2: #eef2f0;
  --ink: #1e2a26;
  --muted: #66746f;
  --line: #dde3e0;
  --accent: #2e6a57;
  --accent-ink: #ffffff;
  --accent-soft: #e3eee9;
  --danger: #b4442f;
  --danger-soft: #f8e7e3;
  --skill-schedule: #3b6b9a;
  --skill-schedule-soft: #e4edf6;
  --radius: 12px;
  --font: -apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif;
  --mono: "SF Mono", Menlo, Consolas, monospace;

  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
  font-size: 15px;
  line-height: 1.6;
}

@media (prefers-color-scheme: dark) {
  page {
    --bg: #121715;
    --surface: #1b2220;
    --surface-2: #222b28;
    --ink: #e6ece9;
    --muted: #94a39d;
    --line: #2b3532;
    --accent: #6fb49a;
    --accent-ink: #0f1a16;
    --accent-soft: #1f3029;
    --danger: #e0806b;
    --danger-soft: #3a211b;
    --skill-schedule: #8fb5dc;
    --skill-schedule-soft: #1d2a37;
  }
}

/* 通用小部件，各页面共用 */
.container {
  max-width: 760px;
  margin: 0 auto;
  padding: 16px;
  box-sizing: border-box;
}

.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px;
}

.section-title {
  font-size: 13px;
  color: var(--muted);
  letter-spacing: 0.04em;
  margin: 20px 4px 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--muted);
  white-space: nowrap;
}
.chip.travel { background: var(--accent-soft); color: var(--accent); }
.chip.schedule { background: var(--skill-schedule-soft); color: var(--skill-schedule); }

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 40px;
  padding: 0 18px;
  border-radius: 10px;
  font-size: 15px;
  background: var(--accent);
  color: var(--accent-ink);
  border: none;
  cursor: pointer;
}
.btn::after { border: none; }
.btn.ghost { background: transparent; color: var(--accent); border: 1px solid var(--line); }
.btn.danger { background: transparent; color: var(--danger); border: 1px solid var(--line); }
.btn.small { height: 32px; padding: 0 12px; font-size: 14px; }
/* uni-button[disabled] 自带灰底，具体度和这里一样且样式表加载在后，只能用 !important 盖回来 */
.btn[disabled] { background: var(--accent) !important; color: var(--accent-ink) !important; opacity: 0.45; cursor: default; }
.btn.ghost[disabled], .btn.danger[disabled] { background: transparent !important; color: var(--muted) !important; }

.input {
  height: 42px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink);
  font-size: 15px;
  box-sizing: border-box;
}

.empty {
  text-align: center;
  color: var(--muted);
  padding: 40px 16px;
  font-size: 14px;
}

.muted { color: var(--muted); }

/* Markdown 正文：对话回答和行程正文共用 */
.md { word-break: break-word; }
.md h1, .md h2, .md h3 { font-size: 16px; margin: 14px 0 6px; line-height: 1.4; }
.md p { margin: 6px 0; }
.md ul, .md ol { padding-left: 20px; margin: 6px 0; }
.md li { margin: 2px 0; }
.md strong { font-weight: 600; }
.md a { color: var(--accent); }
.md code { font-family: var(--mono); font-size: 13px; background: var(--surface-2); padding: 1px 4px; border-radius: 4px; }
.md pre { background: var(--surface-2); padding: 10px; border-radius: 8px; overflow-x: auto; }
.md blockquote { margin: 8px 0; padding-left: 10px; border-left: 3px solid var(--line); color: var(--muted); }
.md table { border-collapse: collapse; margin: 8px 0; font-size: 14px; display: block; overflow-x: auto; }
.md th, .md td { border: 1px solid var(--line); padding: 6px 8px; text-align: left; font-variant-numeric: tabular-nums; }
.md th { background: var(--surface-2); font-weight: 600; }
.md hr { border: none; border-top: 1px solid var(--line); margin: 12px 0; }
</style>
