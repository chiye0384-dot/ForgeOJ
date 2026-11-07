// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
const key = 'forgeoj.editor-preferences.v1'
export interface EditorPreferences {
  share: number
  wrapped: boolean
  fontSize: number
}
export function readEditorPreferences(): EditorPreferences {
  const defaults = { share: 38, wrapped: true, fontSize: 14 }
  try {
    const data = JSON.parse(localStorage.getItem(key) ?? '{}')
    return {
      share:
        typeof data.share === 'number' &&
        Number.isFinite(data.share) &&
        data.share >= 22 &&
        data.share <= 65
          ? data.share
          : defaults.share,
      wrapped: typeof data.wrapped === 'boolean' ? data.wrapped : defaults.wrapped,
      fontSize: [12, 14, 16, 18, 20].includes(data.fontSize) ? data.fontSize : defaults.fontSize,
    }
  } catch {
    return defaults
  }
}
export function saveEditorPreferences(patch: Partial<EditorPreferences>) {
  try {
    localStorage.setItem(key, JSON.stringify({ ...readEditorPreferences(), ...patch }))
  } catch {
    /* Editing stays available when storage is disabled. */
  }
}
