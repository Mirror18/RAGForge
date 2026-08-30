<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ApiError, addSpaceMember, archiveSpace, createManagedUser, disableManagedUser, listAllCursorPages, removeSpaceMember, updateManagedUser, updateSpace, updateSpaceMember, type CurrentSession, type ManagedUser, type PlatformRole, type Space, type SpaceMember, type SpaceRole } from "./api";
import { currentTimeZone, formatDateTime } from "./format";

type PersonalAction = "home" | "providers" | "models" | "prompts" | "runs";

const props = defineProps<{
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
const members = ref<SpaceMember[]>([]);
const users = ref<ManagedUser[]>([]);
const managementLoading = ref(false);
const managementError = ref("");
const editingSpaceId = ref("");
const editSpaceName = ref("");
const editSpaceDescription = ref("");
const editingUserId = ref("");
const editUserDisplayName = ref("");
const editUserRole = ref<PlatformRole>("USER");
const userForm = ref({ email: "", displayName: "", password: "" });
const memberForm = ref<{ email: string; role: SpaceRole }>({ email: "", role: "VIEWER" });
const memberNotice = ref("");
const isPlatformAdmin = computed(() => props.session.user.platformRole === "PLATFORM_ADMIN");
const canManageSpace = computed(() => props.currentRole === "SPACE_ADMIN" || isPlatformAdmin.value);
const spaceSearch = ref("");
const memberSearch = ref("");
const userSearch = ref("");
const visibleSpaces = computed(() => {
  const query = spaceSearch.value.trim().toLocaleLowerCase();
  if (!query) return props.spaces;
  return props.spaces.filter((space) => [space.name, space.description, space.spaceId, space.status, space.role].filter(Boolean).join(" ").toLocaleLowerCase().includes(query));
});
const visibleMembers = computed(() => {
  const query = memberSearch.value.trim().toLocaleLowerCase();
  if (!query) return members.value;
  return members.value.filter((member) => [member.displayName, member.email, member.userId, member.role].filter(Boolean).join(" ").toLocaleLowerCase().includes(query));
});
const visibleUsers = computed(() => {
  const query = userSearch.value.trim().toLocaleLowerCase();
  if (!query) return users.value;
  return users.value.filter((user) => [user.displayName, user.email, user.userId, user.platformRole, user.status].filter(Boolean).join(" ").toLocaleLowerCase().includes(query));
});

function formatDate(value: string): string { return formatDateTime(value); }
function describeManagementError(value: unknown, fallback: string): string {
  if (value instanceof ApiError) return value.problem?.detail ?? value.message;
  return value instanceof Error ? value.message : fallback;
}

async function loadManagementData(): Promise<void> {
  if (!canManageSpace.value && !isPlatformAdmin.value) { members.value = []; return; }
  managementLoading.value = true; managementError.value = "";
  try {
    if (props.selectedSpaceId && canManageSpace.value) members.value = await listAllCursorPages<SpaceMember>(`/api/v1/spaces/${encodeURIComponent(props.selectedSpaceId)}/members`, { limit: 10 });
    else members.value = [];
    if (isPlatformAdmin.value) users.value = await listAllCursorPages<ManagedUser>("/api/v1/users", { limit: 10 });
  } catch (value) { managementError.value = describeManagementError(value, "管理数据加载失败。"); }
  finally { managementLoading.value = false; }
}

function beginEdit(space: Space): void {
  editingSpaceId.value = space.spaceId; editSpaceName.value = space.name; editSpaceDescription.value = space.description ?? "";
}

async function saveSpace(space: Space): Promise<void> {
  managementLoading.value = true; managementError.value = "";
  try { await updateSpace(space.spaceId, { name: editSpaceName.value.trim(), description: editSpaceDescription.value.trim(), version: space.version ?? 0 }); editingSpaceId.value = ""; emit("refresh"); }
  catch (value) { managementError.value = describeManagementError(value, "知识空间更新失败。"); }
  finally { managementLoading.value = false; }
}

async function archiveSelectedSpace(space: Space): Promise<void> {
  if (!window.confirm(`确认归档“${space.name}”？归档后不会删除历史数据，但将从当前空间列表隐藏。`)) return;
  managementLoading.value = true; managementError.value = "";
  try { await archiveSpace(space.spaceId, space.version ?? 0); editingSpaceId.value = ""; emit("refresh"); }
  catch (value) { managementError.value = describeManagementError(value, "知识空间归档失败。"); }
  finally { managementLoading.value = false; }
}

async function changeMemberRole(member: SpaceMember): Promise<void> {
  if (!props.selectedSpaceId) return;
  managementLoading.value = true; managementError.value = "";
  try { await updateSpaceMember(props.selectedSpaceId, member.userId, member.role); await loadManagementData(); }
  catch (value) { managementError.value = describeManagementError(value, "成员角色更新失败。"); }
  finally { managementLoading.value = false; }
}

async function addMember(): Promise<void> {
  if (!props.selectedSpaceId || !memberForm.value.email.trim()) return;
  managementLoading.value = true; managementError.value = ""; memberNotice.value = "";
  try {
    await addSpaceMember(props.selectedSpaceId, { email: memberForm.value.email.trim(), role: memberForm.value.role });
    memberNotice.value = `已将 ${memberForm.value.email.trim()} 加入当前空间。`;
    memberForm.value = { email: "", role: "VIEWER" };
    await loadManagementData();
  } catch (value) { managementError.value = describeManagementError(value, "成员添加失败。"); }
  finally { managementLoading.value = false; }
}

async function removeMember(member: SpaceMember): Promise<void> {
  if (!props.selectedSpaceId || !window.confirm(`确认移除成员“${member.displayName}”？`)) return;
  managementLoading.value = true; managementError.value = "";
  try { await removeSpaceMember(props.selectedSpaceId, member.userId); await loadManagementData(); }
  catch (value) { managementError.value = describeManagementError(value, "成员移除失败。"); }
  finally { managementLoading.value = false; }
}

async function createUser(): Promise<void> {
  managementLoading.value = true; managementError.value = "";
  try { await createManagedUser(userForm.value); userForm.value = { email: "", displayName: "", password: "" }; await loadManagementData(); }
  catch (value) { managementError.value = describeManagementError(value, "用户创建失败。"); }
  finally { managementLoading.value = false; }
}

function beginUserEdit(user: ManagedUser): void {
  editingUserId.value = user.userId;
  editUserDisplayName.value = user.displayName;
  editUserRole.value = user.platformRole;
}

async function saveUser(user: ManagedUser): Promise<void> {
  managementLoading.value = true; managementError.value = "";
  try {
    await updateManagedUser(user.userId, { displayName: editUserDisplayName.value.trim(), platformRole: editUserRole.value, status: user.status });
    editingUserId.value = "";
    await loadManagementData();
  } catch (value) { managementError.value = describeManagementError(value, "用户信息更新失败。"); }
  finally { managementLoading.value = false; }
}

async function toggleUser(user: ManagedUser): Promise<void> {
  managementLoading.value = true; managementError.value = "";
  try { await (user.status === "ACTIVE" ? disableManagedUser(user.userId) : updateManagedUser(user.userId, { displayName: user.displayName, platformRole: user.platformRole, status: "ACTIVE" })); await loadManagementData(); }
  catch (value) { managementError.value = describeManagementError(value, "用户状态更新失败。"); }
  finally { managementLoading.value = false; }
}

watch(() => props.selectedSpaceId, () => { memberNotice.value = ""; memberForm.value = { email: "", role: "VIEWER" }; void loadManagementData(); }, { immediate: true });
onMounted(() => { void loadManagementData(); });

function submitSpace(): void {
  if (!newSpaceName.value.trim()) return;
  emit("create-space", { name: newSpaceName.value.trim(), description: newSpaceDescription.value.trim() });
}

</script>

<template>
  <section class="personal-page view-section" aria-labelledby="personal-space-heading">
    <div class="personal-heading"><div><p class="eyebrow">Account · Personal workspace</p><h2 id="personal-space-heading">个人空间</h2><p>管理你的账号、知识空间和常用工作入口。空间是内容隔离与权限控制边界。</p></div><div class="button-row"><button type="button" class="quiet-button" :disabled="spaceCreating" @click="emit('refresh')">刷新状态</button><button type="button" class="secondary-button" @click="emit('logout')">退出登录</button></div></div>

    <p class="timezone-banner">所有时间均按浏览器时区显示：{{ currentTimeZone() }}。服务端时间保持 UTC。</p>
    <p v-if="managementError" class="alert error" role="alert">{{ managementError }}</p>
    <div class="personal-overview">
      <article class="card account-card"><div class="avatar">{{ session.user.displayName.slice(0, 1).toUpperCase() }}</div><div class="account-copy"><span class="card-label">当前账号</span><h3>{{ session.user.displayName }}</h3><p>{{ session.user.email }}</p><span class="role-pill">{{ session.user.platformRole }}</span></div></article>
      <article class="card security-card"><div class="card-title"><div><span class="card-label">会话安全</span><h3>安全会话已建立</h3></div><span class="state-pill">ACTIVE</span></div><dl class="details"><dt>session expires</dt><dd>{{ formatDate(session.session.expiresAt) }}</dd><dt>storage</dt><dd>HttpOnly Cookie</dd><dt>authorization</dt><dd>服务端按角色与 spaceId 强制校验</dd></dl></article>
    </div>

    <div class="personal-layout">
      <div class="space-column"><div class="subsection-heading"><div><span class="card-label">Your spaces</span><h3>我的知识空间 <span class="count-badge">{{ visibleSpaces.length }}/{{ spaces.length }}</span></h3></div><span class="muted">当前角色：{{ currentRole }}</span></div><div class="list-search"><label for="space-search">搜索空间</label><input id="space-search" v-model="spaceSearch" maxlength="120" placeholder="名称、描述、状态或 ID" /></div><div v-if="!spaces.length" class="card empty-space-card"><strong>还没有知识空间</strong><p>创建第一个空间后，才能开始内容编辑、检索实验和带引用问答。</p></div><div v-else-if="!visibleSpaces.length" class="card empty-space-card"><strong>没有匹配的空间</strong><p>请换一个名称、描述、状态或 ID。</p></div><div class="space-list"><article v-for="space in visibleSpaces" :key="space.spaceId" class="card space-card" :class="{ selected: selectedSpaceId === space.spaceId }"><div class="space-card-heading"><div><span class="space-status" :class="space.status.toLowerCase()">{{ space.status }}</span><h3>{{ space.name }}</h3></div><span class="role-pill">{{ space.role }}</span></div><p>{{ space.description || "暂无空间描述" }}</p><code>{{ space.spaceId }}</code><div v-if="editingSpaceId === space.spaceId" class="inline-edit"><div class="field"><label :for="`edit-space-name-${space.spaceId}`">空间名称</label><input :id="`edit-space-name-${space.spaceId}`" v-model="editSpaceName" maxlength="120" /></div><div class="field"><label :for="`edit-space-description-${space.spaceId}`">空间描述</label><textarea :id="`edit-space-description-${space.spaceId}`" v-model="editSpaceDescription" rows="2" maxlength="2000"></textarea></div><div class="button-row"><button type="button" :disabled="managementLoading" @click="saveSpace(space)">保存修改</button><button type="button" class="quiet-button" @click="editingSpaceId = ''">取消</button></div></div><div class="space-card-footer"><span>v{{ space.version ?? "—" }} · 创建于 {{ formatDate(space.createdAt) }}</span><div class="space-actions"><button type="button" :class="selectedSpaceId === space.spaceId ? 'quiet-button' : ''" @click="emit('select-space', space.spaceId)">{{ selectedSpaceId === space.spaceId ? "当前空间" : "进入空间" }}</button><button v-if="canManageSpace" type="button" class="quiet-button" @click="beginEdit(space)">编辑</button><button v-if="canManageSpace" type="button" class="danger-button" @click="archiveSelectedSpace(space)">归档</button></div></div></article></div></div>

      <aside class="personal-sidebar"><form class="card create-space-card" @submit.prevent="submitSpace"><span class="card-label">Create space</span><h3>创建另一个空间</h3><p>为不同项目或资料集建立独立的内容边界。</p><div class="field"><label for="personal-space-name">空间名称</label><input id="personal-space-name" v-model="newSpaceName" maxlength="120" required /></div><div class="field"><label for="personal-space-description">描述</label><textarea id="personal-space-description" v-model="newSpaceDescription" rows="3" maxlength="2000"></textarea></div><button type="submit" :disabled="spaceCreating">{{ spaceCreating ? "创建中…" : "创建空间" }}</button></form><div v-if="canManageSpace && selectedSpaceId" class="card member-card"><div class="card-title"><div><span class="card-label">Space access</span><h3>成员与权限 <span class="count-badge">{{ visibleMembers.length }}/{{ members.length }}</span></h3></div><button type="button" class="quiet-button" :disabled="managementLoading" @click="loadManagementData">刷新</button></div><p class="muted">输入对方注册邮箱即可加入 ACTIVE 用户，不会开放平台用户目录；服务端会阻止跨空间访问和移除最后一名空间管理员。</p><form class="member-add-form" @submit.prevent="addMember"><div class="field"><label for="space-member-email">成员邮箱</label><input id="space-member-email" v-model="memberForm.email" type="email" maxlength="320" autocomplete="off" required placeholder="member@example.com" /></div><div class="field"><label for="space-member-role">初始角色</label><select id="space-member-role" v-model="memberForm.role"><option value="EDITOR">编辑者</option><option value="VIEWER">只读</option><option value="SPACE_ADMIN">空间管理员</option></select></div><button type="submit" :disabled="managementLoading || !memberForm.email.trim()">{{ managementLoading ? "处理中…" : "加入空间" }}</button></form><div class="list-search"><label for="member-search">搜索成员</label><input id="member-search" v-model="memberSearch" maxlength="120" placeholder="姓名、邮箱、角色或 ID" /></div><p v-if="memberNotice" class="alert success" role="status">{{ memberNotice }}</p><p v-if="!members.length" class="empty-state">当前空间还没有成员。</p><p v-else-if="!visibleMembers.length" class="empty-state">没有匹配的成员。</p><div v-for="member in visibleMembers" :key="member.userId" class="member-row"><div><strong>{{ member.displayName }}</strong><small>{{ member.email }}</small></div><div class="member-actions"><select v-model="member.role" :disabled="managementLoading" @change="changeMemberRole(member)"><option value="SPACE_ADMIN">空间管理员</option><option value="EDITOR">编辑者</option><option value="VIEWER">只读</option></select><button type="button" class="quiet-button" :disabled="managementLoading" @click="removeMember(member)">移除</button></div></div></div><div class="card quick-actions"><span class="card-label">Quick access</span><h3>常用入口</h3><button type="button" class="quick-action" @click="emit('open-action', 'home')"><span>功能入口</span><small>查看全部工作区</small><b>→</b></button><button type="button" class="quick-action" @click="emit('open-action', 'providers')"><span>Provider 连接</span><small>管理本地与云端连接</small><b>→</b></button><button type="button" class="quick-action" @click="emit('open-action', 'runs')"><span>Run / Step 追踪</span><small>查看执行状态与错误</small><b>→</b></button></div></aside>
    </div>

    <section v-if="isPlatformAdmin" class="admin-panel" aria-labelledby="user-management-heading">
      <div class="subsection-heading"><div><span class="card-label">Platform administration</span><h3 id="user-management-heading">用户管理 <span class="count-badge">{{ visibleUsers.length }}/{{ users.length }}</span></h3></div><span class="muted">仅 PLATFORM_ADMIN 可见；删除操作采用可审计停用。</span></div>
      <div class="admin-grid"><form class="card form-card" @submit.prevent="createUser"><h3>创建本地测试账号</h3><p class="muted">密码只通过 TLS/会话提交，服务端使用 BCrypt 存储；不会回显或进入日志。</p><div class="field"><label for="managed-user-email">邮箱</label><input id="managed-user-email" v-model="userForm.email" type="email" required /></div><div class="field"><label for="managed-user-name">显示名称</label><input id="managed-user-name" v-model="userForm.displayName" required maxlength="120" /></div><div class="field"><label for="managed-user-password">初始密码</label><input id="managed-user-password" v-model="userForm.password" type="password" minlength="12" required /></div><button type="submit" :disabled="managementLoading">创建用户</button></form><div class="card list-card"><div class="card-title"><h3>已注册用户</h3><button type="button" class="quiet-button" :disabled="managementLoading" @click="loadManagementData">刷新</button></div><div class="list-search"><label for="user-search">搜索用户</label><input id="user-search" v-model="userSearch" maxlength="120" placeholder="姓名、邮箱、角色、状态或 ID" /></div><p v-if="!users.length" class="empty-state">暂无用户。</p><p v-else-if="!visibleUsers.length" class="empty-state">没有匹配的用户。</p><div v-for="user in visibleUsers" :key="user.userId" class="user-row"><template v-if="editingUserId === user.userId"><div class="user-edit-fields"><div class="field"><label :for="`managed-user-edit-name-${user.userId}`">显示名称</label><input :id="`managed-user-edit-name-${user.userId}`" v-model="editUserDisplayName" maxlength="120" /></div><div class="field"><label :for="`managed-user-edit-role-${user.userId}`">平台角色</label><select :id="`managed-user-edit-role-${user.userId}`" v-model="editUserRole"><option value="USER">USER</option><option value="PLATFORM_ADMIN">PLATFORM_ADMIN</option></select></div></div><div class="member-actions"><button type="button" :disabled="managementLoading" @click="saveUser(user)">保存</button><button type="button" class="quiet-button" @click="editingUserId = ''">取消</button></div></template><template v-else><div><strong>{{ user.displayName }}</strong><small>{{ user.email }} · {{ user.platformRole }} · {{ formatDate(user.createdAt) }}</small></div><div class="member-actions"><span class="state-pill" :class="user.status.toLowerCase()">{{ user.status }}</span><button type="button" class="quiet-button" :disabled="managementLoading" @click="beginUserEdit(user)">编辑</button><button type="button" class="quiet-button" :disabled="managementLoading || user.userId === session.user.userId" @click="toggleUser(user)">{{ user.status === "ACTIVE" ? "停用" : "启用" }}</button></div></template></div></div></div>
    </section>
  </section>
</template>

<style scoped>
.personal-page { margin-top: 30px; }.personal-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 24px; }.personal-heading > div:first-child { max-width: 720px; }.personal-heading p:not(.eyebrow) { margin-bottom: 0; color: #687893; line-height: 1.6; }.personal-overview { display: grid; grid-template-columns: minmax(250px, .85fr) minmax(0, 1.15fr); gap: 15px; }.account-card { display: flex; align-items: center; gap: 17px; }.avatar { display: grid; width: 62px; height: 62px; flex: 0 0 62px; place-items: center; border-radius: 19px; background: linear-gradient(135deg, #1d4f98, #47a1bd); color: #fff; font-size: 1.65rem; font-weight: 900; }.account-copy h3 { margin: 3px 0 4px; }.account-copy p { margin: 0 0 9px; color: #687893; font-size: .87rem; }.role-pill { display: inline-flex; padding: 5px 8px; border-radius: 999px; background: #eef3fa; color: #52709e; font-size: .69rem; font-weight: 800; }.security-card .card-title { align-items: flex-start; }.security-card h3 { margin-top: 3px; }.security-card .details { margin-top: 16px; }.personal-layout { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(290px, .75fr); gap: 20px; margin-top: 20px; }.subsection-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 15px; margin-bottom: 12px; }.subsection-heading h3 { margin-top: 4px; }.count-badge { display: inline-grid; min-width: 22px; height: 22px; place-items: center; margin-left: 4px; border-radius: 999px; background: #e6eef9; color: #31598f; font-size: .7rem; vertical-align: 2px; }.list-search { display: grid; gap: 6px; margin-bottom: 12px; }.list-search label { color: #314b77; font-size: .78rem; font-weight: 700; }.space-list { display: grid; gap: 12px; }.space-card { padding: 17px 19px; }.space-card.selected { border-color: #75a0d5; box-shadow: 0 0 0 3px #dceafe; }.space-card-heading, .space-card-footer { display: flex; align-items: center; justify-content: space-between; gap: 15px; }.space-card-heading h3 { margin-top: 8px; }.space-card > p { margin: 12px 0; color: #687893; font-size: .85rem; line-height: 1.5; }.space-card code { display: block; overflow-wrap: anywhere; color: #315b9a; font-family: "Cascadia Code", Consolas, monospace; font-size: .75rem; }.space-status { display: inline-flex; padding: 4px 7px; border-radius: 999px; background: #e7f6ed; color: #19714c; font-size: .65rem; font-weight: 800; }.space-status.archived { background: #f0f2f5; color: #778399; }.space-card-footer { margin-top: 15px; color: #8190a6; font-size: .73rem; }.space-card-footer button { padding: 8px 11px; font-size: .76rem; }.empty-space-card { color: #687893; }.empty-space-card strong { color: #284572; }.empty-space-card p { margin: 8px 0 0; line-height: 1.5; }.personal-sidebar { display: grid; align-content: start; gap: 15px; }.create-space-card h3, .quick-actions h3 { margin: 5px 0 7px; }.create-space-card > p { color: #687893; font-size: .82rem; line-height: 1.5; }.create-space-card .field { margin-top: 14px; }.create-space-card button { width: 100%; margin-top: 16px; }.quick-actions { display: grid; gap: 9px; }.quick-actions > .card-label { margin-bottom: 0; }.quick-action { position: relative; display: grid; justify-items: start; width: 100%; padding: 12px 34px 12px 13px; border: 1px solid #dbe5f1; border-radius: 10px; background: #f8faff; color: #284572; text-align: left; }.quick-action:hover { border-color: #8eb0e0; background: #eef5ff; }.quick-action span { font-size: .83rem; font-weight: 800; }.quick-action small { margin-top: 3px; color: #7c8ba2; font-size: .7rem; }.quick-action b { position: absolute; top: 50%; right: 13px; color: #1d4f98; transform: translateY(-50%); }
.timezone-banner { margin: -12px 0 16px; color: #71809a; font-size: .76rem; }.timezone-note { display: block; margin-top: 4px; color: #8b99ac; font-size: .66rem; }.space-actions, .member-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }.inline-edit { display: grid; gap: 8px; margin-top: 13px; padding: 13px; border: 1px solid #dbe6f4; border-radius: 10px; background: #f8fbff; }.member-card { display: grid; gap: 10px; }.member-card .card-title { align-items: flex-start; }.member-row, .user-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 0; border-top: 1px solid #edf1f6; }.member-row strong, .user-row strong { display: block; color: #355174; font-size: .8rem; }.member-row small, .user-row small { display: block; margin-top: 3px; color: #8190a6; font-size: .68rem; }.member-row select { min-width: 105px; padding: 7px; font-size: .7rem; }.user-edit-fields { display: grid; grid-template-columns: minmax(130px, 1fr) 150px; gap: 8px; flex: 1; }.user-edit-fields .field { margin: 0; }.admin-panel { margin-top: 26px; }.admin-grid { display: grid; grid-template-columns: minmax(260px, .75fr) minmax(0, 1.25fr); gap: 15px; }.admin-grid .form-card { align-self: start; }.admin-grid .list-card { min-width: 0; }
.member-add-form { display: grid; grid-template-columns: minmax(0, 1fr) 120px; gap: 8px; padding: 12px; border: 1px solid #dce6f2; border-radius: 10px; background: #f8fbff; }.member-add-form .field { margin: 0; }.member-add-form button { grid-column: 1 / -1; }
@media (max-width: 850px) { .personal-heading, .subsection-heading { align-items: flex-start; flex-direction: column; }.personal-overview, .personal-layout, .admin-grid { grid-template-columns: 1fr; }.personal-sidebar { grid-template-columns: repeat(2, minmax(0, 1fr)); }.personal-sidebar .create-space-card { grid-row: span 2; } }
@media (max-width: 520px) { .personal-page { margin-top: 18px; }.personal-sidebar, .member-add-form { grid-template-columns: 1fr; }.personal-sidebar .create-space-card { grid-row: auto; }.space-card-heading, .space-card-footer { align-items: flex-start; flex-direction: column; }.space-card-footer { gap: 10px; }.space-card-footer button { width: 100%; }.account-card { align-items: flex-start; flex-direction: column; } }
</style>
