import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// 管理后台：部署在 /admin/ 下，接口走同域的 /admin/api/v1；本地开发把接口转给后端（默认 8080，可用 ADMIN_API 改）
export default defineConfig({
  base: "/admin/",
  plugins: [vue()],
  build: {
    // Element Plus 单独成一个包，后台代码更新时浏览器不用重新下载它
    chunkSizeWarningLimit: 1200,
    rollupOptions: { output: { manualChunks: { vendor: ["vue", "vue-router", "element-plus"] } } },
  },
  server: {
    port: 5174,
    proxy: {
      "/admin/api": { target: process.env.ADMIN_API || "http://localhost:8080", changeOrigin: true },
    },
  },
});
