import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// findBy* waits up to 1s by default; under a full parallel run that is occasionally too short.
configure({ asyncUtilTimeout: 3000 })

// jsdom has no printing; a completed sale prints its receipt by itself.
window.print = () => {}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})
