<script setup lang="ts">
type AuthMode = "login" | "register";

const props = defineProps<{
  mode: AuthMode;
  email: string;
  password: string;
  displayName: string;
  loading: boolean;
  error: string;
}>();

const emit = defineEmits<{
  "update:mode": [value: AuthMode];
  "update:email": [value: string];
  "update:password": [value: string];
  "update:displayName": [value: string];
  submit: [];
}>();

function updateField(field: "email" | "password" | "displayName", event: Event): void {
  const value = (event.target as HTMLInputElement).value;
  emit(`update:${field}`, value);
}
</script>

<template>
  <section class="auth-page" aria-labelledby="auth-page-title">
    <div class="auth-hero">
      <div class="brand-mark" aria-hidden="true">R</div>
      <p class="eyebrow">RAGForge Workspace</p>
      <h2 id="auth-page-title">把知识空间，变成可验证的工作流。</h2>
      <p class="auth-hero-copy">从内容版本、检索实验到带引用问答，所有操作都围绕空间边界、可追踪运行和证据链组织。</p>
      <div class="auth-feature-list" aria-label="产品能力">
        <div><span class="feature-icon">01</span><span><strong>空间隔离</strong><small>每次请求都绑定当前 spaceId</small></span></div>
        <div><span class="feature-icon">02</span><span><strong>证据可追溯</strong><small>回答保留 provenance 与 citation anchor</small></span></div>
        <div><span class="feature-icon">03</span><span><strong>本地优先</strong><small>云端出境由服务端策略明确控制</small></span></div>
      </div>
    </div>

    <div class="auth-panel card">
      <div class="auth-panel-heading">
        <div><p class="eyebrow">Welcome back</p><h3>{{ props.mode === "login" ? "登录你的工作台" : "创建个人账号" }}</h3><p>{{ props.mode === "login" ? "继续管理你的知识空间和 RAG 实验。" : "创建账号后即可建立第一个个人知识空间。" }}</p></div>
        <span class="secure-badge">安全会话</span>
      </div>
      <p v-if="props.error" class="alert error" role="alert">{{ props.error }}</p>
      <form class="auth-form" @submit.prevent="emit('submit')">
        <div v-if="props.mode === 'register'" class="field"><label for="auth-display-name">显示名称</label><input id="auth-display-name" :value="props.displayName" autocomplete="name" maxlength="120" placeholder="例如：林晓" @input="updateField('displayName', $event)" /></div>
        <div class="field"><label for="auth-email">邮箱</label><input id="auth-email" :value="props.email" type="email" autocomplete="username" placeholder="name@example.com" required @input="updateField('email', $event)" /></div>
        <div class="field"><div class="field-label-row"><label for="auth-password">密码</label><span>至少 12 位</span></div><input id="auth-password" :value="props.password" type="password" :autocomplete="props.mode === 'login' ? 'current-password' : 'new-password'" minlength="12" maxlength="128" placeholder="输入密码" required @input="updateField('password', $event)" /></div>
        <button class="auth-submit" type="submit" :disabled="props.loading">{{ props.loading ? "处理中…" : (props.mode === "login" ? "登录工作台" : "注册并登录") }}</button>
      </form>
      <div class="auth-divider"><span>或</span></div>
      <button type="button" class="secondary-button auth-switch" @click="emit('update:mode', props.mode === 'login' ? 'register' : 'login')">{{ props.mode === "login" ? "没有账号？创建个人账号" : "已有账号？返回登录" }}</button>
      <p class="auth-security-note"><span aria-hidden="true">◈</span> 使用 HttpOnly Cookie 维护会话；密码不会写入 URL、日志或浏览器存储。</p>
    </div>
  </section>
</template>

<style scoped>
.auth-page { display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(380px, .92fr); min-height: 600px; margin-top: 28px; overflow: hidden; border: 1px solid #d9e3f1; border-radius: 24px; background: #fff; box-shadow: 0 20px 55px #243c6414; }
.auth-hero { position: relative; overflow: hidden; padding: 58px clamp(30px, 6vw, 88px); background: linear-gradient(145deg, #173b74 0%, #24599d 58%, #2e7bb0 100%); color: #fff; }
.auth-hero::after { position: absolute; right: -100px; bottom: -120px; width: 360px; height: 360px; border: 1px solid #ffffff25; border-radius: 50%; box-shadow: 0 0 0 38px #ffffff08, 0 0 0 78px #ffffff05; content: ""; }
.brand-mark { display: grid; width: 45px; height: 45px; place-items: center; margin-bottom: 50px; border: 1px solid #ffffff55; border-radius: 13px; background: #ffffff18; font-size: 1.5rem; font-weight: 900; }
.auth-hero .eyebrow { color: #bcd6f8; }.auth-hero h2 { max-width: 560px; margin: 13px 0 18px; color: #fff; font-size: clamp(2rem, 4vw, 3.35rem); line-height: 1.12; }.auth-hero-copy { max-width: 560px; margin-bottom: 45px; color: #d9e8fa; line-height: 1.75; }
.auth-feature-list { display: grid; gap: 18px; max-width: 490px; }.auth-feature-list > div { display: flex; align-items: center; gap: 13px; }.auth-feature-list strong, .auth-feature-list small { display: block; }.auth-feature-list strong { margin-bottom: 3px; color: #fff; }.auth-feature-list small { color: #bad2ee; font-size: .78rem; }.feature-icon { display: grid; width: 33px; height: 33px; flex: 0 0 33px; place-items: center; border: 1px solid #ffffff45; border-radius: 9px; color: #d5e9ff; font-size: .68rem; font-weight: 800; }
.auth-panel { align-self: center; margin: 36px; padding: clamp(25px, 4vw, 45px); box-shadow: none; }.auth-panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; margin-bottom: 28px; }.auth-panel h3 { margin: 8px 0; color: #173363; font-size: 1.7rem; }.auth-panel-heading p:not(.eyebrow) { margin-bottom: 0; color: #687893; font-size: .87rem; line-height: 1.55; }.secure-badge { padding: 7px 9px; border-radius: 999px; background: #e7f6ed; color: #19714c; font-size: .69rem; font-weight: 800; white-space: nowrap; }.auth-form { display: grid; gap: 17px; }.field-label-row { display: flex; justify-content: space-between; gap: 10px; }.field-label-row span { color: #8390a5; font-size: .74rem; }.auth-submit { width: 100%; margin-top: 5px; padding: 13px 16px; }.auth-divider { display: flex; align-items: center; gap: 12px; margin: 24px 0 16px; color: #9aa7b9; font-size: .75rem; }.auth-divider::before, .auth-divider::after { height: 1px; flex: 1; background: #e3e9f1; content: ""; }.auth-switch { width: 100%; }.auth-security-note { display: flex; gap: 7px; margin: 22px 0 0; color: #8290a4; font-size: .73rem; line-height: 1.5; }.auth-security-note span { color: #2e72ad; }
@media (max-width: 850px) { .auth-page { grid-template-columns: 1fr; }.auth-hero { padding: 38px 30px; }.brand-mark { margin-bottom: 28px; }.auth-hero-copy { margin-bottom: 28px; }.auth-feature-list { gap: 12px; }.auth-panel { margin: 0; border: 0; border-radius: 0; }.auth-hero::after { right: -150px; bottom: -170px; } }
@media (max-width: 520px) { .auth-page { margin-top: 15px; border-radius: 16px; }.auth-hero { padding: 30px 22px; }.auth-hero h2 { font-size: 2rem; }.auth-panel { padding: 25px 20px; }.auth-panel-heading { flex-direction: column; }.secure-badge { align-self: flex-start; } }
</style>
