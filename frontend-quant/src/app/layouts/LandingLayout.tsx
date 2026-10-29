import Lenis from 'lenis'
import 'lenis/dist/lenis.css'
import { useReducedMotion } from 'motion/react'
import { useEffect } from 'react'
import { Outlet } from 'react-router'

/** Landing only: Lenis smooth scroll (off under reduced motion). */
export function LandingLayout() {
  const reduce = useReducedMotion()

  useEffect(() => {
    if (reduce) return
    const lenis = new Lenis({ autoRaf: true, anchors: true, lerp: 0.12 })
    return () => lenis.destroy()
  }, [reduce])

  return (
    <div className="min-h-dvh overflow-x-clip">
      <Outlet />
    </div>
  )
}
