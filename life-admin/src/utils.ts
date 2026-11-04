import type { FormItemRule } from "element-plus";

/** 手机宽度（表格的操作列不固定在右边，免得盖住内容） */
export const narrow = typeof window !== "undefined" && window.innerWidth < 700;

/** 时间显示成"2026-11-03 18:20"，空值显示"—" */
export function fmtTime(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 相对时间："刚刚""3 小时前""5 天前"，超过 30 天显示日期 */
export function fromNow(iso?: string | null): string {
  if (!iso) return "从未";
  const diff = Date.now() - new Date(iso).getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} 小时前`;
  const days = Math.floor(h / 24);
  return days <= 30 ? `${days} 天前` : fmtTime(iso).slice(0, 10);
}

export function money(n?: number | null): string {
  return `¥${(n ?? 0).toFixed(2)}`;
}

/** 大数字加千分位 */
export function num(n?: number | null): string {
  return (n ?? 0).toLocaleString("zh-CN");
}

/** 操作日志里的操作类型 → 中文 */
export const ACTION_LABELS: Record<string, string> = {
  LOGIN: "登录后台",
  CHANGE_OWN_PASSWORD: "修改自己的密码",
  CREATE_USER: "新建用户",
  RESET_USER_PASSWORD: "重置用户密码",
  ENABLE_USER: "启用用户",
  DISABLE_USER: "停用用户",
  CREATE_ADMIN: "新建管理员",
  RESET_ADMIN_PASSWORD: "重置管理员密码",
  ENABLE_ADMIN: "启用管理员",
  DISABLE_ADMIN: "停用管理员",
  CREATE_LLM_PROVIDER: "新建模型提供方",
  UPDATE_LLM_PROVIDER: "修改模型提供方",
  DELETE_LLM_PROVIDER: "删除模型提供方",
  CREATE_LLM_MODEL: "新建模型",
  UPDATE_LLM_MODEL: "修改模型",
  DELETE_LLM_MODEL: "删除模型",
  ASSIGN_LLM_ROLE: "分配模型用途",
  EVAL_ROUTING: "运行路由评测",
  RETRY_REMINDER: "重发提醒",
  UPDATE_INTEGRATION: "修改第三方平台",
  ENABLE_INTEGRATION: "启用第三方平台",
  DISABLE_INTEGRATION: "停用第三方平台",
  TEST_INTEGRATION: "测试第三方平台",
};

export function actionLabel(a: string): string {
  return ACTION_LABELS[a] || a;
}

/** 和后端一样的密码规则：8~64 位，同时有字母和数字，前后不能有空格 */
export function passwordProblem(p: string): string {
  if (!p || p.length < 8) return "密码至少 8 位";
  if (p.length > 64) return "密码最多 64 位";
  if (p !== p.trim()) return "密码前后不能有空格";
  if (!/[A-Za-z一-龥]/.test(p) || !/\d/.test(p)) return "密码要同时包含字母和数字";
  return "";
}

export const passwordRules: FormItemRule[] = [
  { required: true, message: "请输入密码", trigger: "blur" },
  {
    validator: (_r, v: string, cb) => {
      const problem = passwordProblem(v);
      problem ? cb(new Error(problem)) : cb();
    },
    trigger: "blur",
  },
];

export const usernameRules: FormItemRule[] = [
  { required: true, message: "请输入用户名", trigger: "blur" },
  { pattern: /^[A-Za-z0-9_.-]{3,32}$/, message: "3 到 32 位，只能用字母、数字、下划线、点和减号", trigger: "blur" },
];

/** 生成一个符合规则的随机密码（重置密码时用），12 位，去掉容易看混的字符 */
export function randomPassword(): string {
  const letters = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
  const digits = "23456789";
  const all = letters + digits;
  const pick = (s: string) => s[crypto.getRandomValues(new Uint32Array(1))[0] % s.length];
  const chars = [pick(letters), pick(digits), ...Array.from({ length: 10 }, () => pick(all))];
  for (let i = chars.length - 1; i > 0; i--) {
    const j = crypto.getRandomValues(new Uint32Array(1))[0] % (i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join("");
}
