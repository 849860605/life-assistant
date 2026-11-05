import { createRouter, createWebHistory } from "vue-router";
import { setUnauthorizedHandler } from "./api/http";
import { ensureAdmin, session } from "./session";

/** 管理后台路由：除登录页外都要登录；meta.super 的页面只有超级管理员能进 */
export const router = createRouter({
  history: createWebHistory("/admin/"),
  routes: [
    { path: "/login", name: "login", component: () => import("./views/Login.vue"), meta: { public: true } },
    {
      path: "/",
      component: () => import("./views/Layout.vue"),
      children: [
        { path: "", redirect: "/dashboard" },
        { path: "dashboard", name: "dashboard", component: () => import("./views/Dashboard.vue"), meta: { title: "总览" } },
        { path: "users", name: "users", component: () => import("./views/Users.vue"), meta: { title: "用户管理" } },
        { path: "admins", name: "admins", component: () => import("./views/Admins.vue"), meta: { title: "管理员", super: true } },
        { path: "orders", name: "orders", component: () => import("./views/Orders.vue"), meta: { title: "会员与账单" } },
        { path: "models", name: "models", component: () => import("./views/Models.vue"), meta: { title: "模型配置" } },
        { path: "integrations", name: "integrations", component: () => import("./views/Integrations.vue"), meta: { title: "第三方平台" } },
        { path: "logs", name: "logs", component: () => import("./views/OpLogs.vue"), meta: { title: "操作日志" } },
        { path: "password", name: "password", component: () => import("./views/Password.vue"), meta: { title: "修改密码" } },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/dashboard" },
  ],
});

router.beforeEach(async (to) => {
  if (to.meta.public) return true;
  const admin = await ensureAdmin();
  if (!admin) return { name: "login", query: to.fullPath !== "/dashboard" ? { from: to.fullPath } : {} };
  if (to.meta.super && admin.role !== "SUPER") return { name: "dashboard" };
  return true;
});

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 星络 管理后台` : "星络 管理后台";
});

// 接口返回 401（token 过期、被停用）时回登录页
setUnauthorizedHandler(() => {
  session.admin = null;
  if (router.currentRoute.value.name !== "login") router.replace({ name: "login" });
});
