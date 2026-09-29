<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <header class="site-header">
    <div class="brand">
      <span class="brand-mark">F</span>
      <div>
        <h1>ForgeOJ</h1>
        <p>从提交到判题结果的最小可信链路</p>
      </div>
    </div>
  </header>
  <main>
    <RouterView />
  </main>
</template>

<style>
:root {
  color: #152033;
  background: #f3f6fa;
  font-family:
    Inter,
    ui-sans-serif,
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    sans-serif;
  font-synthesis: none;
}

body {
  min-width: 320px;
  min-height: 100vh;
  margin: 0;
}

button,
input,
textarea {
  font: inherit;
}

.site-header {
  border-bottom: 1px solid #dce3ec;
  background: rgb(255 255 255 / 92%);
}

.brand {
  display: flex;
  width: min(1180px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 1rem 0;
  gap: 0.8rem;
  align-items: center;
}

.brand-mark {
  display: grid;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  color: #ffffff;
  background: #176b87;
  font-size: 1.25rem;
  font-weight: 800;
  place-items: center;
}

.brand h1,
.brand p {
  margin: 0;
}

.brand h1 {
  font-size: 1.1rem;
}

.brand p {
  margin-top: 0.15rem;
  color: #637083;
  font-size: 0.82rem;
}
</style>
