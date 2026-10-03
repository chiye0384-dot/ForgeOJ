import { createRouter, createWebHistory } from 'vue-router'

import JudgeWorkspaceView from '@/views/JudgeWorkspaceView.vue'
import AccountView from '@/views/AccountView.vue'
import ProblemLibraryView from '@/views/ProblemLibraryView.vue'
import LearningView from '@/views/LearningView.vue'
import AuthoringView from '@/views/AuthoringView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/authoring', name: 'authoring', component: AuthoringView },
    { path: '/learning', name: 'learning', component: LearningView },
    { path: '/account', name: 'account', component: AccountView },
    { path: '/problems', name: 'problem-library', component: ProblemLibraryView },
    {
      path: '/problems/:slug',
      name: 'problem-workspace',
      component: JudgeWorkspaceView,
      props: true,
    },
    {
      path: '/',
      name: 'judge-workspace',
      component: JudgeWorkspaceView,
    },
  ],
})

export default router
