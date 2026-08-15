<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

type ApiStatus = "checking" | "ready" | "unavailable";

const apiStatus = ref<ApiStatus>("checking");
const checkedAt = ref<string>("");

const statusText = computed(() => {
  if (apiStatus.value === "ready") return "服务可用";
  if (apiStatus.value === "unavailable") return "暂不可用";
  return "正在检查";
});

async function checkApiHealth() {
  apiStatus.value = "checking";
  try {
    const response = await fetch("/actuator/health", { headers: { Accept: "application/json" } });
    const payload = (await response.json()) as { status?: string };
    apiStatus.value = response.ok && payload.status === "UP" ? "ready" : "unavailable";
  } catch {
    apiStatus.value = "unavailable";
  } finally {
    checkedAt.value = new Intl.DateTimeFormat("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    }).format(new Date());
  }
}

onMounted(checkApiHealth);
</script>

<template>
  <main class="shell">
    <header class="hero">
      <div>
        <p class="eyebrow">RAGForge · Local Development</p>
        <h1>知识空间工程控制台</h1>
        <p class="intro">
          当前交付版本已覆盖身份与空间隔离、模型运行以及版本化摄取；索引、检索和带引用问答将在后续阶段接入。
        </p>
      </div>
      <div class="phase-badge">Phase 3 Complete<br /><span>Phase 4 Ready</span></div>
    </header>

    <section class="status-panel" aria-live="polite">
      <div>
        <p class="panel-label">Server</p>
        <strong :class="['status', apiStatus]">
          <span class="status-dot" aria-hidden="true"></span>{{ statusText }}
        </strong>
        <p class="muted">{{ checkedAt ? `最后检查：${checkedAt}` : "等待健康检查结果" }}</p>
      </div>
      <button type="button" :disabled="apiStatus === 'checking'" @click="checkApiHealth">
        {{ apiStatus === "checking" ? "检查中…" : "重新检查" }}
      </button>
    </section>

    <section class="grid" aria-label="已交付能力">
      <article class="card">
        <p class="card-index">01</p>
        <h2>身份与空间</h2>
        <p>本地账号、Session、CSRF、空间级 RBAC 和跨空间隔离。</p>
        <span>Phase 1</span>
      </article>
      <article class="card">
        <p class="card-index">02</p>
        <h2>模型运行</h2>
        <p>Provider、Prompt、Run/Step、SSE 与本地 Ollama 路由。</p>
        <span>Phase 2</span>
      </article>
      <article class="card">
        <p class="card-index">03</p>
        <h2>版本化摄取</h2>
        <p>文件、目录、Git 数据源与解析、OCR、检查点及幂等处理。</p>
        <span>Phase 3</span>
      </article>
    </section>

    <section class="next-step">
      <div>
        <p class="panel-label">下一阶段</p>
        <h2>Chunk Studio、索引与检索</h2>
        <p>在知识空间安全边界内构建可回滚索引，并为后续可验证引用提供证据基础。</p>
      </div>
      <a href="/actuator/health" target="_blank" rel="noreferrer">打开健康端点 ↗</a>
    </section>
  </main>
</template>

<style scoped>
:global(*) { box-sizing: border-box; }
:global(body) { margin: 0; min-width: 320px; background: #f5f7fb; color: #172033; font-family: Inter, "Microsoft YaHei", sans-serif; }
:global(button) { font: inherit; }
.shell { width: min(1120px, calc(100% - 40px)); margin: 0 auto; padding: 64px 0 80px; }
.hero { display: flex; justify-content: space-between; gap: 32px; align-items: flex-start; margin-bottom: 32px; }
.eyebrow, .panel-label, .card-index { margin: 0; color: #596884; font-size: 0.75rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
h1, h2, p { margin-top: 0; }
h1 { max-width: 720px; margin-bottom: 12px; color: #15233f; font-size: clamp(2.1rem, 5vw, 4rem); line-height: 1.08; letter-spacing: -0.045em; }
.intro { max-width: 700px; margin-bottom: 0; color: #52617b; font-size: 1.05rem; line-height: 1.7; }
.phase-badge { flex: 0 0 auto; padding: 14px 18px; border-radius: 14px; background: #172f59; color: #fff; font-weight: 700; line-height: 1.5; box-shadow: 0 12px 24px rgb(23 47 89 / 18%); }
.phase-badge span { color: #aec8ff; font-size: 0.86rem; }
.status-panel, .next-step { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 24px; border: 1px solid #dce3f0; border-radius: 18px; background: #fff; box-shadow: 0 8px 30px rgb(31 50 86 / 6%); }
.status-panel { margin-bottom: 22px; }
.status { display: flex; align-items: center; gap: 8px; margin: 6px 0; color: #a45d11; font-size: 1.3rem; }
.status.ready { color: #14734e; }
.status.unavailable { color: #bc3d3d; }
.status-dot { width: 9px; height: 9px; border-radius: 50%; background: currentColor; }
.muted { margin-bottom: 0; color: #71809a; font-size: 0.9rem; }
button { border: 0; border-radius: 10px; padding: 11px 16px; background: #1e4d98; color: #fff; font-weight: 700; cursor: pointer; }
button:disabled { cursor: wait; opacity: 0.65; }
.grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.card { min-height: 210px; padding: 24px; border-radius: 18px; background: #e8effd; }
.card:nth-child(2) { background: #eaf7f2; }
.card:nth-child(3) { background: #fff2dc; }
.card-index { margin-bottom: 30px; }
.card h2, .next-step h2 { margin-bottom: 8px; color: #1d2b46; font-size: 1.35rem; }
.card p:not(.card-index), .next-step p:not(.panel-label) { color: #52617b; line-height: 1.65; }
.card span { display: inline-block; margin-top: 6px; color: #395986; font-size: 0.82rem; font-weight: 700; }
.next-step { margin-top: 22px; }
.next-step p { margin-bottom: 0; }
.next-step a { flex: 0 0 auto; color: #1e4d98; font-weight: 700; text-decoration: none; }
@media (max-width: 720px) { .shell { width: min(100% - 28px, 1120px); padding-top: 36px; } .hero, .status-panel, .next-step { flex-direction: column; align-items: flex-start; } .grid { grid-template-columns: 1fr; } .phase-badge { width: 100%; } }
</style>
