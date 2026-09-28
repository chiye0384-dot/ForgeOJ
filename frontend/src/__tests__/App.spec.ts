import { describe, it, expect } from 'vitest'
import { createApp } from 'vue'

import App from '../App.vue'
import router from '../router'

describe('App', () => {
  it('renders the scaffold status', () => {
    const host = document.createElement('div')
    const app = createApp(App)

    app.use(router)
    app.mount(host)

    expect(host.textContent).toContain('ForgeOJ')
    expect(host.textContent).toContain('业务功能尚未开始')

    app.unmount()
  })
})
