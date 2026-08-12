<script setup lang="ts">
const roles = ["Viewer", "Editor", "Space Admin", "Platform Admin"];
</script>

<template>
  <main>
    <h1>RAGForge</h1>
    <p>Phase 1 工程与领域骨架</p>
    <ul>
      <li v-for="role in roles" :key="role">{{ role }}</li>
    </ul>
  </main>
</template>
