import { createRouter, createWebHistory } from 'vue-router'
import { requireAccount } from './accountNavigation'

const JudgeWorkspaceView = () => import('@/views/JudgeWorkspaceView.vue')
const AccountView = () => import('@/views/AccountView.vue')
const ProblemLibraryView = () => import('@/views/ProblemLibraryView.vue')
const LearningView = () => import('@/views/LearningView.vue')
const AuthoringView = () => import('@/views/AuthoringView.vue')
const ClassroomView = () => import('@/views/ClassroomView.vue')
const ClassroomProblemsView = () => import('@/views/ClassroomProblemsView.vue')
const AssignmentsView = () => import('@/views/AssignmentsView.vue')
const TeacherRecordsView = () => import('@/views/TeacherRecordsView.vue')
const AdminView = () => import('@/views/AdminView.vue')
const AdminContentView = () => import('@/views/AdminContentView.vue')
const AdminOperationsView = () => import('@/views/AdminOperationsView.vue')
const AdminSearchView = () => import('@/views/AdminSearchView.vue')

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
      meta: { requiresAccount: true },
      component: TeacherRecordsView,
      props: true,
    },
    {
      path: '/classrooms/:id/assignments',
      name: 'assignments',
      meta: { requiresAccount: true },
      component: AssignmentsView,
      props: true,
    },
    {
      path: '/classrooms/:id/problems',
      name: 'classroom-problems',
      meta: { requiresAccount: true },
      component: ClassroomProblemsView,
      props: true,
    },
    {
      path: '/classrooms',
      name: 'classrooms',
      meta: { requiresAccount: true },
      component: ClassroomView,
    },
    {
      path: '/authoring',
      name: 'authoring',
      meta: { requiresAccount: true },
      component: AuthoringView,
    },
    {
      path: '/learning',
      name: 'learning',
      meta: { requiresAccount: true },
      component: LearningView,
    },
    { path: '/account', name: 'account', component: AccountView },
    { path: '/account/:action(register|forgot|resend)', component: AccountView },
    { path: '/problems', name: 'problem-library', component: ProblemLibraryView },
    {
      path: '/problems/:slug',
      name: 'problem-workspace',
      meta: { requiresAccount: true },
      component: JudgeWorkspaceView,
      props: true,
    },
    {
      path: '/',
      name: 'judge-workspace',
      redirect: { name: 'problem-library' },
    },
  ],
})

router.beforeEach(requireAccount)

export default router
