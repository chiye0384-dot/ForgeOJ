import type { NavigationGuard, Router } from 'vue-router'
import { restoreSession } from '@/services/forgeojApi'

export const requireAccount: NavigationGuard = async (to) => {
  if (!to.meta.requiresAccount) return true
  try {
    if ((await restoreSession()).authenticated) return true
  } catch {
    // The login page provides the retry/error state when session lookup is unavailable.
  }
  return { path: '/account', query: { redirect: to.fullPath } }
}

export function accountReturnPath(router: Router, value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^\/(?!\/)/.test(value) ||
    value.includes('\\') ||
    Array.from(value).some((char) => char.charCodeAt(0) <= 32)
  )
    return '/problems'
  const target = router.resolve(value)
  if (!target.matched.length || /^\/(?:account|admin)(?:\/|$)/.test(target.path)) return '/problems'
  return target.fullPath
}
