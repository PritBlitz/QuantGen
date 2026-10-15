import { motion, useReducedMotion } from 'motion/react'
import {
  ArrowRight,
  Fingerprint,
  KeyRound,
  Link2,
  ScrollText,
  ShieldCheck,
  Timer,
} from 'lucide-react'
import { Link } from 'react-router'
import { Logo } from '@/components/Logo'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/features/auth/store'
import { EASE_OUT_EXPO } from '@/lib/motion'
import { Bento } from './components/Bento'
import { LedgerStory } from './components/LedgerStory'
import { PriceMarquee } from './components/PriceMarquee'
import { ProductShot } from './components/ProductShot'
import { Reveal, SplitReveal } from './components/SplitReveal'

function LandingNav() {
  const authed = useAuthStore((s) => s.status === 'authenticated')
  return (
    <header className="fixed inset-x-0 top-0 z-40 border-b border-transparent bg-bg/70 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-[1280px] items-center gap-6 px-4 sm:px-6 lg:px-10">
        <Link to="/" aria-label="QuantGen home">
          <Logo />
        </Link>
        <nav aria-label="Sections" className="hidden items-center gap-6 text-sm text-muted md:flex">
          <a href="#product" className="hover:text-text">
            Product
          </a>
          <a href="#ledger" className="hover:text-text">
            Ledger
          </a>
          <a href="#security" className="hover:text-text">
            Security
          </a>
        </nav>
        <div className="ml-auto flex items-center gap-1 sm:gap-2">
          <ThemeToggle />
          {authed ? (
            <Button asChild size="sm">
              <Link to="/dashboard">Dashboard</Link>
            </Button>
          ) : (
            <>
              <Button asChild variant="ghost" size="sm" className="hidden sm:inline-flex">
                <Link to="/login">Sign in</Link>
              </Button>
              <Button asChild size="sm">
                <Link to="/register">Open account</Link>
              </Button>
            </>
          )}
        </div>
      </div>
    </header>
  )
}

function Hero() {
  const reduce = useReducedMotion()
  const fade = (delay: number) => ({
    initial: { opacity: 0, y: reduce ? 0 : 12 },
    animate: { opacity: 1, y: 0 },
    transition: { duration: reduce ? 0.2 : 0.8, ease: EASE_OUT_EXPO, delay },
  })
  return (
    <section className="relative px-4 pb-24 pt-32 sm:px-6 sm:pt-40 lg:px-10">
      <div className="mx-auto max-w-[1280px]">
        <motion.p
          {...fade(0)}
          className="inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-xs text-muted hairline"
        >
          <span className="size-1.5 rounded-full bg-gain" aria-hidden="true" />
          Banking · Trading · Loans, on one ledger
        </motion.p>
        <SplitReveal
          className="mt-8 font-display text-[clamp(3rem,10.5vw,7rem)] text-balance"
          lines={[
            'Banking, with the',
            <>
              precision <span className="italic text-muted">of a quant.</span>
            </>,
          ]}
          delay={0.1}
        />
        <div className="mt-10 flex flex-col gap-8 md:flex-row md:items-end md:justify-between">
          <motion.p {...fade(0.4)} className="max-w-md text-lg text-muted">
            Accounts, instant transfers, live trading and loans, all settled through a double-entry
            ledger that can’t lose a paisa.
          </motion.p>
          <motion.div {...fade(0.5)} className="flex flex-col gap-3 sm:flex-row">
            <Button asChild size="lg">
              <Link to="/register">
                Open an account <ArrowRight aria-hidden="true" />
              </Link>
            </Button>
            <Button asChild size="lg" variant="outline">
              <Link to="/login">Try the demo</Link>
            </Button>
          </motion.div>
        </div>
      </div>
      <motion.div {...fade(0.65)} className="mt-24">
        <ProductShot />
      </motion.div>
    </section>
  )
}

const CONTROLS = [
  {
    icon: KeyRound,
    title: 'Memory-only tokens',
    body: 'Access tokens live 15 minutes and are never written to browser storage.',
  },
  {
    icon: Timer,
    title: 'Rotating refresh',
    body: 'HttpOnly, SameSite=Strict cookies rotate on every use. Reuse ends the whole session family.',
  },
  {
    icon: Fingerprint,
    title: 'Idempotent money',
    body: 'Every money request carries a unique key. Retries replay; they never double-charge.',
  },
  {
    icon: ScrollText,
    title: 'Audit trail',
    body: 'Logins, freezes, loan decisions and large transfers are recorded with who, when and where.',
  },
] as const

function Security() {
  return (
    <section id="security" className="mx-auto max-w-[1280px] scroll-mt-24 px-4 sm:px-6 lg:px-10">
      <div className="grid grid-cols-1 gap-12 lg:grid-cols-[1fr_1.3fr] lg:gap-20">
        <Reveal>
          <p className="text-xs uppercase tracking-[0.18em] text-muted">Security</p>
          <h2 className="mt-4 font-display text-4xl sm:text-5xl">
            Secure by default, not by checkbox.
          </h2>
          <div className="mt-10 rounded-[var(--radius-card)] border border-dashed border-border p-5">
            <p className="flex items-center gap-2 text-sm font-medium">
              <Link2 aria-hidden="true" className="size-4 text-accent" /> Coming in v2: a
              hash-chained ledger
            </p>
            <p className="mt-2 text-sm text-muted">
              Each journal entry will carry the hash of the one before it, with a daily Merkle root,
              so any tampering is detectable.
            </p>
            <p aria-hidden="true" className="num mt-4 truncate text-xs text-muted">
              prev 9f2c…a71e → hash 41d0…c3b8 → hash e7aa…0f52
            </p>
          </div>
        </Reveal>
        <ul className="grid gap-px overflow-hidden rounded-[var(--radius-card)] bg-border hairline sm:grid-cols-2">
          {CONTROLS.map(({ icon: Icon, title, body }, i) => (
            <li key={title} className="bg-bg p-6">
              <Reveal delay={i * 0.04}>
                <Icon aria-hidden="true" className="size-5 text-accent" />
                <h3 className="mt-5 font-medium">{title}</h3>
                <p className="mt-2 text-sm text-muted">{body}</p>
              </Reveal>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}

function FinalCta() {
  return (
    <section className="mx-auto max-w-[1280px] px-4 sm:px-6 lg:px-10">
      <div className="relative overflow-hidden rounded-[24px] bg-surface px-6 py-20 text-center hairline sm:px-12 sm:py-28">
        <div
          aria-hidden="true"
          className="absolute left-1/2 top-0 h-64 w-[36rem] -translate-x-1/2 -translate-y-1/2 rounded-full bg-accent/25 blur-[100px]"
        />
        <SplitReveal
          as="h2"
          className="relative font-display text-[clamp(2.5rem,7vw,4.5rem)]"
          lines={[
            'Open an account',
            <span key="2" className="italic text-muted">
              in two minutes.
            </span>,
          ]}
        />
        <Reveal
          className="relative mt-10 flex flex-col justify-center gap-3 sm:flex-row"
          delay={0.2}
        >
          <Button asChild size="lg">
            <Link to="/register">
              Get started <ArrowRight aria-hidden="true" />
            </Link>
          </Button>
          <Button asChild size="lg" variant="outline">
            <Link to="/login">Sign in</Link>
          </Button>
        </Reveal>
      </div>
    </section>
  )
}

function Footer() {
  return (
    <footer className="mx-auto mt-24 max-w-[1280px] border-t border-border px-4 py-10 sm:px-6 lg:px-10">
      <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
        <Logo />
        <p className="flex items-center gap-2 text-xs text-muted">
          <ShieldCheck aria-hidden="true" className="size-3.5" />A demo platform. Funding and market
          prices are simulated; no real money moves.
        </p>
      </div>
      <p className="mt-6 text-xs text-muted">© {new Date().getFullYear()} QuantGen</p>
    </footer>
  )
}

export function LandingPage() {
  return (
    <>
      <LandingNav />
      <main>
        <Hero />
        <PriceMarquee />
        <div className="space-y-40 py-40">
          <Bento />
          <LedgerStory />
          <Security />
          <FinalCta />
        </div>
      </main>
      <Footer />
    </>
  )
}
