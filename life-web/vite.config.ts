import { defineConfig, loadEnv } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

// 开发时把 /api 转发到后端，前端用相对路径请求，不需要配跨域。后端地址用 VITE_API_TARGET 改
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [uni()],
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target: env.VITE_API_TARGET || "http://localhost:8081",
          changeOrigin: true,
        },
      },
    },
  };
});
