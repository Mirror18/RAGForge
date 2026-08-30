import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  const serverTarget = env.VITE_SERVER_TARGET || "http://127.0.0.1:18084";

  return {
    plugins: [vue()],
    server: {
      proxy: {
        "/api": serverTarget,
        "/actuator": serverTarget,
      },
    },
  };
});
