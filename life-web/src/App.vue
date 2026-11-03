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
 * 配色跟原型图走：晴空蓝做强调色，中性色略偏蓝；价格用暖橙，和蓝色主操作区分开。
 */
page {
  --bg: #f4f6fa;
  --surface: #ffffff;
  --surface-2: #eef2f8;
  --ink: #1b2333;
  --muted: #687386;
  --line: #dfe4ee;
  --accent: #2563eb;
  --accent-ink: #ffffff;
  --accent-soft: #e6eefd;
  --danger: #b4442f;
  --danger-soft: #f8e7e3;
  --price: #e8590c;
  --ok: #1f8a4c;
  --ok-soft: #e3f4ea;
  --skill-schedule: #7a4fc7;
  --skill-schedule-soft: #f0eafb;
  /* 双色图标底下的浅色圆角方块 */
  --tile-bg: linear-gradient(145deg, #ffffff, #e4ecff);
  --tile-shadow: 0 2px 6px rgba(47, 107, 242, 0.10), inset 0 0 0 1px rgba(47, 107, 242, 0.10);
  --brand-shadow: 0 4px 12px rgba(47, 107, 242, 0.16), inset 0 0 0 1px rgba(47, 107, 242, 0.16);
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
    --bg: #10141c;
    --surface: #181e2a;
    --surface-2: #202838;
    --ink: #e5e9f2;
    --muted: #97a1b3;
    --line: #2a3345;
    --accent: #7aa7ff;
    --accent-ink: #0d1422;
    --accent-soft: #1c2a47;
    --danger: #e0806b;
    --danger-soft: #3a211b;
    --price: #ff9a5a;
    --ok: #6fd39a;
    --ok-soft: #173326;
    --skill-schedule: #b9a0ea;
    --skill-schedule-soft: #2a2140;
    --tile-bg: linear-gradient(145deg, #27324c, #1b2438);
    --tile-shadow: inset 0 0 0 1px rgba(122, 167, 255, 0.18);
    --brand-shadow: 0 4px 12px rgba(0, 0, 0, 0.25), inset 0 0 0 1px rgba(122, 167, 255, 0.28);
  }
}

/* 宽屏（电脑）：左侧导航栏代替底部标签栏和顶部标题栏，见 components/Shell.vue */
@media (min-width: 1024px) {
  uni-tabbar, uni-page-head, .uni-tabbar-bottom { display: none !important; }
  html, body, uni-app, uni-page, uni-page-wrapper { --window-top: 0px !important; --window-bottom: 0px !important; }
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
.chip.food { background: #fdeee3; color: #c2560c; }
.chip.ok { background: var(--ok-soft); color: var(--ok); }
@media (prefers-color-scheme: dark) { .chip.food { background: #3a2617; color: #ffb27a; } }

/* 对话里的卡片：工具结果直接渲染，和回答文字区分开 */
.xcard {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  overflow: hidden;
}
.xcard-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
}
.xcard-title { font-weight: 600; font-size: 15px; }
.xcard-meta { font-size: 12px; color: var(--muted); white-space: nowrap; }
.xcard-row { padding: 10px 14px; border-bottom: 1px solid var(--line); }
.xcard-row:last-child { border-bottom: none; }
.xcard-foot { padding: 8px 14px; font-size: 12px; color: var(--muted); background: var(--surface-2); }
.price { color: var(--price); font-weight: 600; font-variant-numeric: tabular-nums; }
.link { color: var(--accent); cursor: pointer; text-decoration: none; }

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
