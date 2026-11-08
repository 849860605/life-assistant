import { createSSRApp } from "vue";
import App from "./App.vue";
import { initTheme } from "./utils/theme";

// 先把外观设置写到 <html> 上，页面出来时就是对的颜色
initTheme();
export function createApp() {
  const app = createSSRApp(App);
  return {
    app,
  };
}
