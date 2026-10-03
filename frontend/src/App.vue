<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <header class="site-header">
    <div class="brand">
      <span class="brand-mark">F</span>
      <div>
        <h1>ForgeOJ</h1>
        <p>Java 21 在线练习与判题</p>
      </div>
      <nav aria-label="主导航">
        <RouterLink to="/problems">题库</RouterLink>
        <RouterLink to="/learning">学习记录</RouterLink>
        <RouterLink to="/authoring">我的题目</RouterLink>
        <RouterLink to="/">做题</RouterLink>
        <RouterLink to="/account">账号</RouterLink>
      </nav>
    </div>
  </header>
  <main>
    <RouterView v-slot="{ Component, route }">
      <component :is="Component" :key="route.path" />
    </RouterView>
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
  flex-wrap: wrap;
}

.brand nav {
  display: flex;
  margin-left: auto;
  gap: 1rem;
}

.brand nav a {
  color: #15576d;
  font-weight: 650;
  text-decoration: none;
}

.brand nav a.router-link-exact-active {
  text-decoration: underline;
  text-underline-offset: 0.3rem;
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
