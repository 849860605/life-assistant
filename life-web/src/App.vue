<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { getToken, rememberBack } from "./api/http";

// 没登录直接去登录页；打开的是某个页面（比如分享来的行程链接）就记下来，登录后回去
onLaunch((opts) => {
  if (!getToken()) {
    if (opts?.path) rememberBack({ route: opts.path, options: opts.query as Record<string, string> });
    uni.reLaunch({ url: "/pages/login/login" });
  }
});
</script>

<style>
/*
 * 版式：工具型界面，白底浅灰、近黑正文、灰色辅助字；蓝色只用于主要操作和选中状态。
 * 8px 间距体系；圆角小、边框细、阴影轻；默认不用渐变、发光和彩色图标方块。价格用暗橙和蓝色主操作区分开。
 * 用户可以在外观设置里换成深色，或换"星光紫"背景（见下面 data-theme / data-bg）。
 */
page {
  --bg: #f5f5f7;
  --surface: #ffffff;
  --surface-2: #f2f2f5;
  --ink: #1d1d1f;
  --muted: #6e6e73;
  --line: #e3e3e8;
  --accent: #0071e3;
  --accent-ink: #ffffff;
  --accent-soft: #eaf2fd;
  --danger: #c4372b;
  --danger-soft: #fbeceb;
  --price: #c2410c;
  --ok: #1d8348;
  --ok-soft: #e7f5ec;
  --warn: #a15c00;
  --warn-soft: #fdf3e2;
  --skill-schedule: #6e4bb8;
  --skill-schedule-soft: #f1ecfa;
  /* 图标底块：浅灰平铺，不再用渐变 */
  --tile-bg: var(--surface-2);
  --tile-shadow: none;
  --brand-shadow: none;
  --shadow: 0 1px 2px rgba(0, 0, 0, 0.04), 0 4px 16px rgba(0, 0, 0, 0.05);
  --nav-bg: rgba(255, 255, 255, 0.86);
  --side-bg: var(--surface-2);
  --radius: 12px;
  --font: -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif;
  --mono: "SF Mono", Menlo, Consolas, monospace;

  background: var(--bg);
  color: var(--ink);
  font-family: var(--font);
  font-size: 15px;
  line-height: 1.6;
  -webkit-font-smoothing: antialiased;
}

/* 深色：<html data-theme="dark">，由 utils/theme.ts 按用户设置（或跟随系统）写上 */
html[data-theme="dark"] page {
  color-scheme: dark;
  --bg: #000000;
  --surface: #1c1c1e;
  --surface-2: #2c2c2e;
  --ink: #f5f5f7;
  --muted: #98989d;
  --line: #38383a;
  --accent: #2997ff;
  --accent-ink: #ffffff;
  --accent-soft: #0f2a47;
  --danger: #ff6b5e;
  --danger-soft: #3a1d1a;
  --price: #ff9f5a;
  --ok: #4cd18a;
  --ok-soft: #12301f;
  --warn: #f5b84a;
  --warn-soft: #3a2a10;
  --skill-schedule: #b9a0ea;
  --skill-schedule-soft: #2a2140;
  --nav-bg: rgba(28, 28, 30, 0.86);
  --shadow: 0 1px 2px rgba(0, 0, 0, 0.4);
}

/*
 * 星光紫背景：<html data-bg="aurora">。主色换成紫色，页面底是淡紫到淡粉的渐变，卡片仍是实色，保证字看得清。
 * 深色下是深靛蓝底和紫色光晕。
 */
html[data-bg="aurora"] page {
  --bg: #f3f1fc;
  --surface-2: #f1eefb;
  --line: #e4dff5;
  --muted: #6b6880;
  --accent: #6a4ff0;
  --accent-soft: #eeeaff;
  --nav-bg: rgba(255, 255, 255, 0.62);
  --shadow: 0 1px 2px rgba(60, 40, 140, 0.05), 0 8px 24px rgba(90, 70, 180, 0.08);
  --side-bg: rgba(255, 255, 255, 0.4);
  background: radial-gradient(1100px 560px at 70% -8%, #cbbdff 0%, rgba(203, 189, 255, 0) 70%),
    radial-gradient(800px 520px at -5% 25%, #ddd4ff 0%, rgba(221, 212, 255, 0) 70%),
    radial-gradient(900px 420px at 60% 110%, #f7cfe2 0%, rgba(247, 207, 226, 0) 70%),
    linear-gradient(180deg, #ebe6ff 0%, #f2effd 50%, #f8eef6 100%) fixed;
}
html[data-theme="dark"][data-bg="aurora"] page {
  --bg: #0e0c1d;
  --surface: #18152c;
  --surface-2: #221e3a;
  --line: #2f2a4c;
  --muted: #a19cbb;
  --accent: #9d8bff;
  --accent-soft: #2b2453;
  --nav-bg: rgba(20, 17, 38, 0.7);
  --shadow: 0 1px 2px rgba(0, 0, 0, 0.5);
  --side-bg: rgba(24, 21, 44, 0.55);
  background: radial-gradient(1100px 560px at 70% -8%, #3a2b85 0%, rgba(58, 43, 133, 0) 70%),
    radial-gradient(800px 520px at -5% 25%, #241d57 0%, rgba(36, 29, 87, 0) 70%),
    radial-gradient(900px 420px at 60% 110%, #3a1838 0%, rgba(58, 24, 56, 0) 70%),
    linear-gradient(180deg, #141029 0%, #0e0c1d 60%, #170f22 100%) fixed;
}

/* 内容不满一屏时，下面也是页面底色 */
html, body { background: #f5f5f7; }
html[data-theme="dark"], html[data-theme="dark"] body { background: #000; }
html[data-bg="aurora"], html[data-bg="aurora"] body { background: #f3f1fc; }
html[data-theme="dark"][data-bg="aurora"], html[data-theme="dark"][data-bg="aurora"] body { background: #0e0c1d; }

/* 宽屏（电脑）：顶部导航代替底部标签栏和顶部标题栏，见 components/Shell.vue */
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
html[data-theme="dark"] .chip.food { background: #3a2617; color: #ffb27a; }

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
  border-radius: 8px;
  font-size: 15px;
  font-weight: 500;
  background: var(--accent);
  color: var(--accent-ink);
  border: none;
  cursor: pointer;
}
.btn::after { border: none; }
.btn.ghost { background: transparent; color: var(--accent); border: 1px solid var(--line); }
.btn.danger { background: transparent; color: var(--danger); border: 1px solid var(--line); }
.btn.small { height: 32px; padding: 0 12px; font-size: 14px; }
.btn:focus-visible, .input:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.btn.ghost:hover { background: var(--surface-2); }
/* uni-button[disabled] 自带灰底，具体度和这里一样且样式表加载在后，只能用 !important 盖回来 */
.btn[disabled] { background: var(--accent) !important; color: var(--accent-ink) !important; opacity: 0.45; cursor: default; }
.btn.ghost[disabled], .btn.danger[disabled] { background: transparent !important; color: var(--muted) !important; }

.input {
  height: 42px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
  font-size: 15px;
  box-sizing: border-box;
}
.input:focus { border-color: var(--accent); }

/* 工具页：最宽 1200px 居中，标题 28px */
.page-wrap { max-width: 1200px; margin: 0 auto; padding: 24px; box-sizing: border-box; display: flex; flex-direction: column; gap: 16px; }
.page-title { font-size: 28px; font-weight: 700; letter-spacing: -0.01em; line-height: 1.25; }
.page-sub { font-size: 14px; color: var(--muted); }
@media (max-width: 1023px) { .page-wrap { padding: 16px; } .page-title { font-size: 24px; } }

/* 分段切换（日程/行程、行程详情三个标签等） */
.seg { display: inline-flex; padding: 2px; border-radius: 9px; background: var(--surface-2); gap: 2px; }
.seg-item { padding: 6px 16px; border-radius: 7px; font-size: 14px; color: var(--muted); cursor: pointer; white-space: nowrap; }
.seg-item.on { background: var(--surface); color: var(--ink); font-weight: 600; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08); }

/* 状态提示条 */
.notice { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-radius: 10px; font-size: 14px; background: var(--surface-2); color: var(--ink); }
.notice.ok { background: var(--ok-soft); color: var(--ok); }
.notice.warn { background: var(--warn-soft); color: var(--warn); }
.notice.danger { background: var(--danger-soft); color: var(--danger); }

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
/* 原生底部栏的底色写死在 pages.json，深色模式下跟着页面变暗 */
html[data-theme="dark"] .uni-tabbar { background-color: #1c1c1e !important; }
html[data-theme="dark"] .uni-tabbar-border { background-color: rgba(255, 255, 255, 0.08) !important; }
</style>
