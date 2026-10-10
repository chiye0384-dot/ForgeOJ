import { createRouter, createWebHistory } from 'vue-router'

import JudgeWorkspaceView from '@/views/JudgeWorkspaceView.vue'
import AccountView from '@/views/AccountView.vue'
import ProblemLibraryView from '@/views/ProblemLibraryView.vue'
import LearningView from '@/views/LearningView.vue'
import AuthoringView from '@/views/AuthoringView.vue'
import ClassroomView from '@/views/ClassroomView.vue'
import ClassroomProblemsView from '@/views/ClassroomProblemsView.vue'
import AssignmentsView from '@/views/AssignmentsView.vue'
import TeacherRecordsView from '@/views/TeacherRecordsView.vue'
import AdminView from '@/views/AdminView.vue'
import AdminContentView from '@/views/AdminContentView.vue'
import AdminOperationsView from '@/views/AdminOperationsView.vue'
import AdminSearchView from '@/views/AdminSearchView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/admin/search', component: AdminSearchView },
    { path: '/admin/operations', component: AdminOperationsView },
    { path: '/admin/reviews', component: AdminContentView, props: { section: 'reviews' } },
    { path: '/admin/problems', component: AdminContentView, props: { section: 'problems' } },
    { path: '/admin/feedback', component: AdminContentView, props: { section: 'feedback' } },
    {
      path: '/admin/login',
      name: 'admin-login',
      component: AdminView,
      props: { section: 'login' },
    },
    {
      path: '/admin/accounts',
      name: 'admin-accounts',
      component: AdminView,
      props: { section: 'accounts' },
    },
    {
      path: '/admin/audit',
      name: 'admin-audit',
      component: AdminView,
      props: { section: 'audit' },
    },
    { path: '/admin', name: 'admin-home', component: AdminView, props: { section: 'home' } },
    {
      path: '/classrooms/:id/assignments/:assignmentId/records',
      name: 'teacher-records',
      component: TeacherRecordsView,
      props: true,
    },
    {
      path: '/classrooms/:id/assignments',
      name: 'assignments',
      component: AssignmentsView,
      props: true,
    },
    {
      path: '/classrooms/:id/problems',
      name: 'classroom-problems',
      component: ClassroomProblemsView,
      props: true,
    },
    { path: '/classrooms', name: 'classrooms', component: ClassroomView },
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
