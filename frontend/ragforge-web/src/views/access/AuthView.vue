<script setup lang="ts">
type AuthMode = "login" | "register" | "bootstrap";

const props = defineProps<{
  mode: AuthMode;
  email: string;
  password: string;
  displayName: string;
  bootstrapToken: string;
  bootstrapRequired: boolean;
  bootstrapAvailable: boolean;
  loading: boolean;
  error: string;
}>();

const emit = defineEmits<{
  "update:mode": [value: AuthMode];
  "update:email": [value: string];
  "update:password": [value: string];
  "update:displayName": [value: string];
  "update:bootstrapToken": [value: string];
  submit: [];
}>();

function updateField(field: "email" | "password" | "displayName" | "bootstrapToken", event: Event): void {
  const value = (event.target as HTMLInputElement).value;
  emit(`update:${field}`, value);
}

function heading(): string {
  if (props.mode === "bootstrap") return "初始化平台管理员";
  return props.mode === "login" ? "欢迎回来" : "创建你的工作台";
}

function description(): string {
  if (props.mode === "bootstrap") return "使用运维一次性 Secret 建立首个平台管理员，完成后即可登录。";
  return props.mode === "login" ? "登录后继续你的知识空间、索引发布和带引用问答。" : "创建账号后即可建立第一个个人知识空间。";
}

function submitLabel(): string {
  if (props.loading) return "处理中…";
  if (props.mode === "login") return "进入工作台";
  if (props.mode === "register") return "注册并登录";
  return "初始化并登录";
}
</script>

<template>
  <section class="auth-page" aria-labelledby="auth-page-title">
    <div class="auth-glow" aria-hidden="true"></div>
    <div class="auth-panel card">
      <div class="auth-brand">
        <span class="brand-mark" aria-hidden="true">玖</span>
        <div>
          <strong>RAGForge</strong>
          <small>Nakano Miku Desk</small>
        </div>
      </div>

      <div v-if="props.mode !== 'bootstrap'" class="auth-tabs" role="tablist" aria-label="登录或注册">
        <button type="button" role="tab" :aria-selected="props.mode === 'login'" :class="{ active: props.mode === 'login' }" @click="emit('update:mode', 'login')">登录</button>
        <button type="button" role="tab" :aria-selected="props.mode === 'register'" :class="{ active: props.mode === 'register' }" @click="emit('update:mode', 'register')">注册</button>
      </div>

      <div class="auth-panel-heading">
        <div>
          <h2 id="auth-page-title">{{ heading() }}</h2>
          <p>{{ description() }}</p>
        </div>
        <span class="secure-badge">安全会话</span>
      </div>

      <p v-if="props.error" class="alert error" role="alert">{{ props.error }}</p>

      <form class="auth-form" @submit.prevent="emit('submit')">
        <div v-if="props.mode !== 'login'" class="field">
          <label for="auth-display-name">显示名称</label>
          <input id="auth-display-name" :value="props.displayName" autocomplete="name" maxlength="120" placeholder="例如：林晓" @input="updateField('displayName', $event)" />
        </div>
        <div class="field">
          <label for="auth-email">邮箱</label>
          <input id="auth-email" :value="props.email" type="email" autocomplete="username" placeholder="name@example.com" required @input="updateField('email', $event)" />
        </div>
        <div class="field">
          <div class="field-label-row">
            <label for="auth-password">密码</label>
            <span>至少 12 位</span>
          </div>
          <input id="auth-password" :value="props.password" type="password" :autocomplete="props.mode === 'login' ? 'current-password' : 'new-password'" minlength="12" maxlength="128" placeholder="输入密码" required @input="updateField('password', $event)" />
        </div>
        <div v-if="props.mode === 'bootstrap'" class="field">
          <div class="field-label-row">
            <label for="auth-bootstrap-token">一次性 Bootstrap Secret</label>
            <span>至少 32 位</span>
          </div>
          <input id="auth-bootstrap-token" :value="props.bootstrapToken" type="password" autocomplete="off" minlength="32" maxlength="512" required placeholder="由运维人员临时提供" @input="updateField('bootstrapToken', $event)" />
        </div>
        <button class="auth-submit" type="submit" :disabled="props.loading">{{ submitLabel() }}</button>
      </form>

      <div class="auth-footer">
        <button v-if="props.mode === 'bootstrap'" type="button" class="auth-text-switch" @click="emit('update:mode', 'login')">返回登录</button>
        <button v-if="props.bootstrapAvailable && props.mode !== 'bootstrap'" type="button" class="auth-text-switch" @click="emit('update:mode', 'bootstrap')">初始化首个平台管理员</button>
      </div>
      <p v-if="props.bootstrapRequired && !props.bootstrapAvailable" class="bootstrap-note">系统尚未初始化平台管理员。请由运维人员临时配置 <code>RAGFORGE_BOOTSTRAP_ADMIN_TOKEN</code> 后刷新；不要把 Secret 写入仓库或浏览器存储。</p>
      <p class="auth-security-note">HttpOnly Cookie 会话 · 密码不写入 URL、日志或浏览器存储</p>
    </div>

    <aside class="auth-stage" aria-label="产品能力">
      <div class="auth-stage-copy">
        <p class="auth-stage-kicker">把知识空间，变成可验证的工作流</p>
        <p class="auth-stage-lead">空间隔离、证据可追溯、云端出境必须显式授权。登录后从资料到带引用回答，走同一条闭环。</p>
        <ul class="auth-stage-points">
          <li>空间隔离</li>
          <li>证据可追溯</li>
          <li>本地优先</li>
        </ul>
      </div>
    </aside>
  </section>
</template>

<style scoped>
.auth-page {
  position: relative;
  display: grid;
  grid-template-columns: minmax(340px, 440px) minmax(0, 1fr);
  align-items: stretch;
  height: 100vh;
  max-height: 100vh;
  overflow: hidden;
  background: transparent;
}
.auth-glow {
  position: absolute;
  left: -80px;
  top: 12%;
  width: 360px;
  height: 360px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(210, 186, 245, .42), rgba(210, 186, 245, 0) 68%);
  pointer-events: none;
}
.auth-panel {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 0;
  max-height: calc(100vh - 40px);
  margin: 20px 0 20px 24px;
  padding: 28px 28px 20px;
  overflow: auto;
  border: 1px solid rgba(255, 255, 255, .72);
  background: linear-gradient(180deg, rgba(255, 250, 253, .9), rgba(248, 240, 252, .78));
  box-shadow: 0 24px 60px rgba(48, 28, 72, .28);
}
.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}
.brand-mark {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 14px;
  background: linear-gradient(135deg, #d7c4f4, #8b7bb8 58%, #f4c7d8);
  color: #fff;
  font-size: 1.05rem;
  font-weight: 900;
  box-shadow: 0 10px 20px rgba(139, 123, 184, .35);
}
.auth-brand strong, .auth-brand small { display: block; }
.auth-brand strong { color: #3a2d52; font-size: 1rem; letter-spacing: -.02em; }
.auth-brand small { color: #9a88b4; font-size: .7rem; font-weight: 600; }
.auth-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-bottom: 16px;
  padding: 4px;
  border-radius: 999px;
  background: rgba(139, 123, 184, .12);
}
.auth-tabs button {
  padding: 8px 12px;
  background: transparent;
  color: #6b5b95;
  box-shadow: none;
  font-size: .82rem;
}
.auth-tabs button.active {
  background: #fff;
  color: #3a2d52;
  box-shadow: 0 6px 16px rgba(107, 91, 149, .18);
}
.auth-tabs button:hover:not(:disabled) { filter: none; transform: none; }
.auth-panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 16px;
}
.auth-panel h2 { margin: 0 0 6px; font-size: 1.48rem; letter-spacing: -.03em; }
.auth-panel-heading p { margin: 0; color: #7d6e93; font-size: .84rem; line-height: 1.5; }
.secure-badge {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(226, 245, 236, .92);
  color: #19714c;
  font-size: .66rem;
  font-weight: 800;
  white-space: nowrap;
}
.auth-form { display: grid; gap: 12px; }
.field-label-row { display: flex; justify-content: space-between; gap: 8px; }
.field-label-row span { color: #9a88b4; font-size: .7rem; }
.auth-submit {
  width: 100%;
  margin-top: 4px;
  padding: 12px 16px;
  font-size: .95rem;
}
.auth-footer { display: flex; flex-wrap: wrap; gap: 8px 14px; margin-top: 14px; }
.auth-text-switch {
  padding: 0;
  background: transparent;
  color: #6b5b95;
  box-shadow: none;
  font-size: .78rem;
  font-weight: 700;
}
.auth-text-switch:hover:not(:disabled) { filter: none; transform: none; color: #3a2d52; background: transparent; }
.bootstrap-note { margin: 12px 0 0; padding: 10px 12px; border: 1px solid #eed39a; border-radius: 12px; background: #fff9eb; color: #765b24; font-size: .72rem; line-height: 1.5; }
.auth-security-note { margin: 14px 0 0; color: #9a88b4; font-size: .7rem; }
.auth-page :deep(.alert) { margin: 0 0 12px; padding: 9px 11px; font-size: .8rem; }
.auth-page :deep(.field label) { margin-bottom: 5px; font-size: .8rem; }
.auth-page :deep(input) { padding: 11px 12px; }
.auth-stage {
  display: flex;
  align-self: stretch;
  flex-direction: column;
  justify-content: flex-end;
  min-width: 0;
  padding: 36px 40px;
  color: #fff;
}
.auth-stage-copy {
  max-width: 420px;
  padding: 18px 18px 16px;
  border: 1px solid rgba(255, 255, 255, .18);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(28, 16, 42, .28), rgba(28, 16, 42, .08));
  backdrop-filter: blur(14px);
}
.auth-stage-kicker {
  margin: 0 0 8px;
  font-size: 1.28rem;
  font-weight: 780;
  line-height: 1.35;
  text-shadow: 0 8px 22px rgba(24, 12, 36, .45);
}
.auth-stage-lead {
  margin: 0 0 14px;
  color: rgba(255, 246, 252, .86);
  font-size: .86rem;
  line-height: 1.6;
}
.auth-stage-points {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.auth-stage-points li {
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, .16);
  backdrop-filter: blur(10px);
  font-size: .74rem;
  font-weight: 700;
}
@media (max-width: 850px) {
  .auth-page { grid-template-columns: 1fr; }
  .auth-stage { display: none; }
  .auth-panel { margin: 12px; max-height: calc(100vh - 24px); }
}
</style>
