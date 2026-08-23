<script setup lang="ts">
import { ref } from "vue";
import type { CurrentSession, PlatformRole, Space, SpaceRole } from "./api";

type PersonalAction = "home" | "providers" | "models" | "prompts" | "runs";

defineProps<{
  session: CurrentSession;
  spaces: Space[];
  selectedSpaceId: string;
  currentRole: SpaceRole | PlatformRole | string;
  spaceCreating: boolean;
}>();

const emit = defineEmits<{
  "select-space": [spaceId: string];
  "open-action": [action: PersonalAction];
  "create-space": [payload: { name: string; description: string }];
  logout: [];
  refresh: [];
}>();

const newSpaceName = ref("我的新知识空间");
const newSpaceDescription = ref("用于整理和验证 RAG 内容的个人空间");

function submitSpace(): void {
  if (!newSpaceName.value.trim()) return;
  emit("create-space", { name: newSpaceName.value.trim(), description: newSpaceDescription.value.trim() });
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(date);
}
</script>

<template>
  <section class="personal-page view-section" aria-labelledby="personal-space-heading">
    <div class="personal-heading"><div><p class="eyebrow">Account · Personal workspace</p><h2 id="personal-space-heading">个人空间</h2><p>管理你的账号、知识空间和常用工作入口。空间是内容隔离与权限控制边界。</p></div><div class="button-row"><button type="button" class="quiet-button" :disabled="spaceCreating" @click="emit('refresh')">刷新状态</button><button type="button" class="secondary-button" @click="emit('logout')">退出登录</button></div></div>

    <div class="personal-overview">
      <article class="card account-card"><div class="avatar">{{ session.user.displayName.slice(0, 1).toUpperCase() }}</div><div class="account-copy"><span class="card-label">当前账号</span><h3>{{ session.user.displayName }}</h3><p>{{ session.user.email }}</p><span class="role-pill">{{ session.user.platformRole }}</span></div></article>
      <article class="card security-card"><div class="card-title"><div><span class="card-label">会话安全</span><h3>安全会话已建立</h3></div><span class="state-pill">ACTIVE</span></div><dl class="details"><dt>session expires</dt><dd>{{ formatDate(session.session.expiresAt) }}</dd><dt>storage</dt><dd>HttpOnly Cookie</dd><dt>authorization</dt><dd>服务端按角色与 spaceId 强制校验</dd></dl></article>
    </div>

    <div class="personal-layout">
      <div class="space-column"><div class="subsection-heading"><div><span class="card-label">Your spaces</span><h3>我的知识空间 <span class="count-badge">{{ spaces.length }}</span></h3></div><span class="muted">当前角色：{{ currentRole }}</span></div><div v-if="!spaces.length" class="card empty-space-card"><strong>还没有知识空间</strong><p>创建第一个空间后，才能开始内容编辑、检索实验和带引用问答。</p></div><div class="space-list"><article v-for="space in spaces" :key="space.spaceId" class="card space-card" :class="{ selected: selectedSpaceId === space.spaceId }"><div class="space-card-heading"><div><span class="space-status" :class="space.status.toLowerCase()">{{ space.status }}</span><h3>{{ space.name }}</h3></div><span class="role-pill">{{ space.role }}</span></div><p>{{ space.description || "暂无空间描述" }}</p><code>{{ space.spaceId }}</code><div class="space-card-footer"><span>v{{ space.version ?? "—" }} · 创建于 {{ formatDate(space.createdAt) }}</span><button type="button" :class="selectedSpaceId === space.spaceId ? 'quiet-button' : ''" @click="emit('select-space', space.spaceId)">{{ selectedSpaceId === space.spaceId ? "当前空间" : "进入空间" }}</button></div></article></div></div>

      <aside class="personal-sidebar"><form class="card create-space-card" @submit.prevent="submitSpace"><span class="card-label">Create space</span><h3>创建另一个空间</h3><p>为不同项目或资料集建立独立的内容边界。</p><div class="field"><label for="personal-space-name">空间名称</label><input id="personal-space-name" v-model="newSpaceName" maxlength="120" required /></div><div class="field"><label for="personal-space-description">描述</label><textarea id="personal-space-description" v-model="newSpaceDescription" rows="3" maxlength="2000"></textarea></div><button type="submit" :disabled="spaceCreating">{{ spaceCreating ? "创建中…" : "创建空间" }}</button></form><div class="card quick-actions"><span class="card-label">Quick access</span><h3>常用入口</h3><button type="button" class="quick-action" @click="emit('open-action', 'home')"><span>功能入口</span><small>查看全部工作区</small><b>→</b></button><button type="button" class="quick-action" @click="emit('open-action', 'providers')"><span>Provider 连接</span><small>管理本地与云端连接</small><b>→</b></button><button type="button" class="quick-action" @click="emit('open-action', 'runs')"><span>Run / Step 追踪</span><small>查看执行状态与错误</small><b>→</b></button></div></aside>
    </div>
  </section>
</template>

<style scoped>
.personal-page { margin-top: 30px; }.personal-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 24px; }.personal-heading > div:first-child { max-width: 720px; }.personal-heading p:not(.eyebrow) { margin-bottom: 0; color: #687893; line-height: 1.6; }.personal-overview { display: grid; grid-template-columns: minmax(250px, .85fr) minmax(0, 1.15fr); gap: 15px; }.account-card { display: flex; align-items: center; gap: 17px; }.avatar { display: grid; width: 62px; height: 62px; flex: 0 0 62px; place-items: center; border-radius: 19px; background: linear-gradient(135deg, #1d4f98, #47a1bd); color: #fff; font-size: 1.65rem; font-weight: 900; }.account-copy h3 { margin: 3px 0 4px; }.account-copy p { margin: 0 0 9px; color: #687893; font-size: .87rem; }.role-pill { display: inline-flex; padding: 5px 8px; border-radius: 999px; background: #eef3fa; color: #52709e; font-size: .69rem; font-weight: 800; }.security-card .card-title { align-items: flex-start; }.security-card h3 { margin-top: 3px; }.security-card .details { margin-top: 16px; }.personal-layout { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(290px, .75fr); gap: 20px; margin-top: 20px; }.subsection-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 15px; margin-bottom: 12px; }.subsection-heading h3 { margin-top: 4px; }.count-badge { display: inline-grid; min-width: 22px; height: 22px; place-items: center; margin-left: 4px; border-radius: 999px; background: #e6eef9; color: #31598f; font-size: .7rem; vertical-align: 2px; }.space-list { display: grid; gap: 12px; }.space-card { padding: 17px 19px; }.space-card.selected { border-color: #75a0d5; box-shadow: 0 0 0 3px #dceafe; }.space-card-heading, .space-card-footer { display: flex; align-items: center; justify-content: space-between; gap: 15px; }.space-card-heading h3 { margin-top: 8px; }.space-card > p { margin: 12px 0; color: #687893; font-size: .85rem; line-height: 1.5; }.space-card code { display: block; overflow-wrap: anywhere; color: #315b9a; font-family: "Cascadia Code", Consolas, monospace; font-size: .75rem; }.space-status { display: inline-flex; padding: 4px 7px; border-radius: 999px; background: #e7f6ed; color: #19714c; font-size: .65rem; font-weight: 800; }.space-status.archived { background: #f0f2f5; color: #778399; }.space-card-footer { margin-top: 15px; color: #8190a6; font-size: .73rem; }.space-card-footer button { padding: 8px 11px; font-size: .76rem; }.empty-space-card { color: #687893; }.empty-space-card strong { color: #284572; }.empty-space-card p { margin: 8px 0 0; line-height: 1.5; }.personal-sidebar { display: grid; align-content: start; gap: 15px; }.create-space-card h3, .quick-actions h3 { margin: 5px 0 7px; }.create-space-card > p { color: #687893; font-size: .82rem; line-height: 1.5; }.create-space-card .field { margin-top: 14px; }.create-space-card button { width: 100%; margin-top: 16px; }.quick-actions { display: grid; gap: 9px; }.quick-actions > .card-label { margin-bottom: 0; }.quick-action { position: relative; display: grid; justify-items: start; width: 100%; padding: 12px 34px 12px 13px; border: 1px solid #dbe5f1; border-radius: 10px; background: #f8faff; color: #284572; text-align: left; }.quick-action:hover { border-color: #8eb0e0; background: #eef5ff; }.quick-action span { font-size: .83rem; font-weight: 800; }.quick-action small { margin-top: 3px; color: #7c8ba2; font-size: .7rem; }.quick-action b { position: absolute; top: 50%; right: 13px; color: #1d4f98; transform: translateY(-50%); }
@media (max-width: 850px) { .personal-heading, .subsection-heading { align-items: flex-start; flex-direction: column; }.personal-overview, .personal-layout { grid-template-columns: 1fr; }.personal-sidebar { grid-template-columns: repeat(2, minmax(0, 1fr)); }.personal-sidebar .create-space-card { grid-row: span 2; } }
@media (max-width: 520px) { .personal-page { margin-top: 18px; }.personal-sidebar { grid-template-columns: 1fr; }.personal-sidebar .create-space-card { grid-row: auto; }.space-card-heading, .space-card-footer { align-items: flex-start; flex-direction: column; }.space-card-footer { gap: 10px; }.space-card-footer button { width: 100%; }.account-card { align-items: flex-start; flex-direction: column; } }
</style>
