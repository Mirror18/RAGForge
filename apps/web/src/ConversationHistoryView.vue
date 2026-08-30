<script setup lang="ts">
import type { ConversationHistoryItem, ConversationRunItem } from "./answer";

defineProps<{
  items: ConversationHistoryItem[];
  selectedConversationId: string;
  selectedRuns: ConversationRunItem[];
  includeArchived: boolean;
  loading: boolean;
  error: string;
  disabled: boolean;
}>();

defineEmits<{
  "new-conversation": [];
  "select-conversation": [item: ConversationHistoryItem];
  "archive-conversation": [item: ConversationHistoryItem];
  "rename-conversation": [item: ConversationHistoryItem];
  "delete-conversation": [item: ConversationHistoryItem];
  "select-run": [run: ConversationRunItem];
  "update:includeArchived": [value: boolean];
}>();
</script>

<template>
  <aside class="card answer-history" aria-label="问答历史">
    <div class="card-title"><div><span class="card-label">历史与归档</span><h3>继续已有会话</h3></div><div class="history-actions"><button type="button" class="secondary-button" :disabled="disabled" @click="$emit('new-conversation')">新会话</button><label class="history-toggle"><input :checked="includeArchived" type="checkbox" @change="$emit('update:includeArchived', ($event.target as HTMLInputElement).checked)" />显示已归档</label></div></div>
    <p v-if="error" class="alert error">{{ error }}</p><p v-if="loading" class="muted">读取历史记录中…</p><div v-else-if="!items.length" class="muted">还没有问答历史。点击“新会话”后提交第一个问题，会话会自动出现在这里。</div><div v-else class="history-list"><article v-for="item in items" :key="item.id" class="history-item" :class="{ selected: selectedConversationId === item.id }"><button type="button" class="history-select" @click="$emit('select-conversation', item)"><strong>{{ item.title }}</strong><small>{{ item.status === "ARCHIVED" ? "已归档" : "进行中" }} · {{ new Date(item.updatedAt).toLocaleString() }}</small></button><div class="history-actions"><button v-if='item.status !== "ARCHIVED"' type="button" class="quiet-button" @click="$emit('archive-conversation', item)">归档</button><button v-if='item.status !== "ARCHIVED"' type="button" class="quiet-button" @click="$emit('rename-conversation', item)">重命名</button><button type="button" class="danger-button" @click="$emit('delete-conversation', item)">删除</button></div><div v-if="selectedConversationId === item.id && selectedRuns.length" class="history-runs"><button v-for="run in selectedRuns" :key="run.runId" type="button" class="run-history-row" @click="$emit('select-run', run)"><span>{{ run.status }}</span><small>{{ run.createdAt ? new Date(run.createdAt).toLocaleString() : run.runId }}</small></button></div></article></div>
  </aside>
</template>
