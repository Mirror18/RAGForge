import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  // Browser talks only to gateway (RF-9); gateway forwards /api to server.
  const serverTarget = env.VITE_SERVER_TARGET || "http://127.0.0.1:25080";

  return {
    plugins: [vue()],
    cacheDir: "../../tmp/local-run/vite",
    server: {
      proxy: {
        "/api": serverTarget,
        "/actuator": serverTarget,
      },
    },
  };
});
