import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../index.css'
import { DisplayApp } from './DisplayApp'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <DisplayApp />
  </StrictMode>,
)
