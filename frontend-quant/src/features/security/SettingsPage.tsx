import {
  Check,
  Copy,
  Fingerprint,
  KeyRound,
  LaptopMinimal,
  LogOut,
  Plus,
  ShieldCheck,
  Smartphone,
  Terminal,
  Trash2,
} from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import { useFlag } from '@/api/flags'
import type { CreatedTokenDto, TokenScope } from '@/api/types'
import { FormField } from '@/components/FormField'
import { AmountInput } from '@/components/money/AmountInput'
import { PageHeader, EmptyState } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { PinInput } from '@/components/PinInput'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { TabPanel, Tabs } from '@/components/ui/tabs'
import { ProfilePage } from '@/features/profile/ProfilePage'
import { AMOUNT_INPUT_RE, formatINR, ratio, toDecimal } from '@/lib/money'
import { formatAbsolute, formatRelative } from '@/lib/time'
import { webauthnSupported } from '@/lib/webauthn'
import {
  useAddPasskey,
  useCreateToken,
  useKyc,
  useLimits,
  useMe,
  usePasskeys,
  useRemovePasskey,
  useRevokeSession,
  useRevokeToken,
  useSessions,
  useSetKyc,
  useSetLimits,
  useSetPin,
  useTokens,
} from './hooks'

type Tab = 'profile' | 'security' | 'limits' | 'developer'
const TABS: Tab[] = ['profile', 'security', 'limits', 'developer']

/** Settings (PRD F-SEC-1…7, F-MCP-2): profile, security, limits & KYC, developer access. */
export function SettingsPage() {
  const { tab: raw } = useParams()
  const navigate = useNavigate()
  const tab = (TABS.includes(raw as Tab) ? raw : 'profile') as Tab
  const mcp = useFlag('mcp')

  return (
    <div>
      <PageHeader eyebrow="Settings" title="Your account, your rules." description="Profile, sign-in security, limits, identity and developer access." />
      <Tabs
        label="Settings sections"
        value={tab}
        onValueChange={(v) => void navigate(v === 'profile' ? '/settings' : `/settings/${v}`, { replace: true })}
        tabs={[
          { value: 'profile', label: 'Profile' },
          { value: 'security', label: 'Security' },
          { value: 'limits', label: 'Limits & KYC' },
          ...(mcp ? [{ value: 'developer' as const, label: 'Developer' }] : []),
        ]}
      >
        <TabPanel value="profile">
          <ProfilePage embedded />
        </TabPanel>
        <TabPanel value="security">
          <SecurityTab />
        </TabPanel>
        <TabPanel value="limits">
          <LimitsTab />
        </TabPanel>
        {mcp && (
          <TabPanel value="developer">
            <DeveloperTab />
          </TabPanel>
        )}
      </Tabs>
    </div>
  )
}

// ── Security ────────────────────────────────────────────────────────────────────────────
function SecurityTab() {
  const me = useMe()
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <PasskeysPanel />
      <PinPanel hasPin={!!me.data?.hasPin} hasPasskey={!!me.data?.hasPasskey} />
      <SessionsPanel />
    </div>
  )
}

function PasskeysPanel() {
  const passkeys = usePasskeys()
  const add = useAddPasskey()
  const remove = useRemovePasskey()
  const [name, setName] = useState('')
  const [adding, setAdding] = useState(false)
  const supported = webauthnSupported()

  function submit(e: FormEvent) {
    e.preventDefault()
    const n = name.trim()
    if (!n) return
    add.mutate(n, {
      onSuccess: (pk) => {
        if (!pk) {
          toast('Passkey setup was cancelled.')
          return
        }
        toast.success(`Passkey “${pk.name}” added`)
        setName('')
        setAdding(false)
      },
      onError: (err) => toast.error(toApiError(err).fieldErrors[0]?.message ?? errorMessage(err)),
    })
  }

  return (
    <Panel
      eyebrow="Passwordless"
      title="Passkeys"
      description="Sign in and confirm payments with your fingerprint, face or device PIN. Nothing to remember, nothing to phish."
      action={
        supported && (
          <Button size="sm" variant="secondary" onClick={() => setAdding(true)}>
            <Plus aria-hidden="true" /> Add
          </Button>
        )
      }
    >
      {!supported && <p className="mb-4 text-sm text-warn">This browser doesn’t support passkeys. Use your PIN instead.</p>}
      {passkeys.isError ? (
        <PanelError what="your passkeys" onRetry={() => void passkeys.refetch()} />
      ) : !passkeys.data ? (
        <Skeleton className="h-24 w-full" />
      ) : passkeys.data.length === 0 ? (
        <EmptyState icon={<Fingerprint />} title="No passkeys yet">Add one to sign in without a password.</EmptyState>
      ) : (
        <ul className="divide-y divide-border">
          {passkeys.data.map((p) => (
            <li key={p.id} className="flex items-center gap-3 py-3">
              <span className="grid size-9 place-items-center rounded-xl bg-accent/12 text-accent">
                <Fingerprint aria-hidden="true" className="size-4" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">{p.name}</p>
                <p className="text-xs text-muted">
                  Added {formatRelative(p.createdAt)} · {p.lastUsedAt ? `last used ${formatRelative(p.lastUsedAt)}` : 'never used'}
                </p>
              </div>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Remove passkey ${p.name}`}
                disabled={remove.isPending}
                onClick={() =>
                  remove.mutate(p.id, {
                    onSuccess: () => toast.success('Passkey removed'),
                    onError: (e) => toast.error(errorMessage(e)),
                  })
                }
              >
                <Trash2 />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent title="Add a passkey" description="Give it a name you’ll recognise, like “MacBook Touch ID”.">
          <form onSubmit={submit} className="space-y-4">
            <FormField label="Name" value={name} maxLength={40} autoFocus onChange={(e) => setName(e.target.value)} />
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setAdding(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={!name.trim() || add.isPending}>
                <Fingerprint aria-hidden="true" /> {add.isPending ? 'Waiting for your device…' : 'Create passkey'}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </Panel>
  )
}

function PinPanel({ hasPin, hasPasskey }: { hasPin: boolean; hasPasskey: boolean }) {
  const setPin = useSetPin()
  const [pin, setPinValue] = useState('')
  const [confirm, setConfirm] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  // The first PIN needs the account password; once a PIN or passkey exists, the server asks for
  // step-up instead (the client handles that automatically).
  const needsPassword = !hasPin && !hasPasskey

  function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (pin.length !== 6) return setError('Enter 6 digits.')
    if (pin !== confirm) return setError('The two PINs don’t match.')
    if (needsPassword && !password) return setError('Enter your account password.')
    setPin.mutate(
      { pin, password: needsPassword ? password : undefined },
      {
        onSuccess: () => {
          toast.success(hasPin ? 'PIN changed' : 'PIN set')
          setPinValue('')
          setConfirm('')
          setPassword('')
        },
        onError: (err) => {
          const ae = toApiError(err)
          setPinValue('')
          setConfirm('')
          setError(ae.fieldErrors[0]?.message ? `${ae.fieldErrors[0].field === 'password' ? 'Password' : 'PIN'}: ${ae.fieldErrors[0].message}` : errorMessage(ae))
        },
      },
    )
  }

  return (
    <Panel
      eyebrow="Step-up"
      title="Transaction PIN"
      description="Six digits we ask for before large payments or security changes when a passkey isn’t available."
      action={<Badge tone={hasPin ? 'gain' : 'warn'} dot>{hasPin ? 'Set' : 'Not set'}</Badge>}
    >
      <form onSubmit={submit} className="space-y-4" noValidate>
        <div className="space-y-2">
          <p className="text-sm font-medium">{hasPin ? 'New PIN' : 'Choose a PIN'}</p>
          <PinInput className="justify-start" label={hasPin ? 'New PIN' : 'Choose a PIN'} value={pin} onChange={setPinValue} />
        </div>
        <div className="space-y-2">
          <p className="text-sm font-medium">Confirm PIN</p>
          <PinInput className="justify-start" label="Confirm PIN" value={confirm} onChange={setConfirm} invalid={confirm.length === 6 && confirm !== pin} />
        </div>
        {needsPassword && (
          <FormField label="Account password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} hint="Needed once, to prove it’s you before your first PIN." />
        )}
        {error && (
          <p role="alert" className="text-sm text-loss">
            {error}
          </p>
        )}
        <p className="text-xs text-muted">Avoid easy PINs like 111111 or 123456; they’re rejected.</p>
        <Button type="submit" disabled={setPin.isPending}>
          <KeyRound aria-hidden="true" /> {setPin.isPending ? 'Saving…' : hasPin ? 'Change PIN' : 'Set PIN'}
        </Button>
      </form>
    </Panel>
  )
}

function SessionsPanel() {
  const sessions = useSessions()
  const revoke = useRevokeSession()
  const others = sessions.data?.filter((s) => !s.current).length ?? 0
  return (
    <Panel
      className="lg:col-span-2"
      eyebrow="Devices"
      title="Where you’re signed in"
      description="Don’t recognise one? Sign it out, then change your password."
      action={
        others > 0 && (
          <Button
            variant="secondary"
            size="sm"
            disabled={revoke.isPending}
            onClick={() =>
              revoke.mutate('others', {
                onSuccess: () => toast.success('Signed out of every other device'),
                onError: (e) => toast.error(errorMessage(e)),
              })
            }
          >
            <LogOut aria-hidden="true" /> Sign out all others
          </Button>
        )
      }
    >
      {sessions.isError ? (
        <PanelError what="your sessions" onRetry={() => void sessions.refetch()} />
      ) : !sessions.data ? (
        <Skeleton className="h-32 w-full" />
      ) : (
        <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {sessions.data.map((s) => {
            const mobile = /iphone|android|ipad|mobile/i.test(s.device)
            const Icon = mobile ? Smartphone : LaptopMinimal
            return (
              <li key={s.id} className="flex items-center gap-3 rounded-2xl bg-surface-2/60 p-3 hairline">
                <span className="grid size-10 place-items-center rounded-xl bg-surface-3 text-muted">
                  <Icon aria-hidden="true" className="size-5" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-2 text-sm font-medium">
                    <span className="truncate">
                      {s.device} · {s.browser}
                    </span>
                    {s.current && (
                      <Badge tone="gain" className="shrink-0">
                        This device
                      </Badge>
                    )}
                  </p>
                  <p className="truncate text-xs text-muted" title={formatAbsolute(s.lastUsedAt)}>
                    {s.ipCity ? `${s.ipCity} · ` : ''}active {formatRelative(s.lastUsedAt)}
                  </p>
                </div>
                {!s.current && (
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={revoke.isPending}
                    onClick={() =>
                      revoke.mutate(s.id, {
                        onSuccess: () => toast.success('Device signed out'),
                        onError: (e) => toast.error(errorMessage(e)),
                      })
                    }
                  >
                    Sign out
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </Panel>
  )
}

// ── Limits & KYC ────────────────────────────────────────────────────────────────────────
function LimitsTab() {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <LimitsPanel />
      <KycPanel />
    </div>
  )
}

function LimitsPanel() {
  const limits = useLimits()
  const save = useSetLimits()
  const [perTransfer, setPerTransfer] = useState('')
  const [daily, setDaily] = useState('')
  const [error, setError] = useState<string | null>(null)
  const l = limits.data

  function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const body: { perTransfer?: string; daily?: string } = {}
    if (perTransfer) body.perTransfer = perTransfer
    if (daily) body.daily = daily
    if (!body.perTransfer && !body.daily) return setError('Enter at least one new limit.')
    for (const v of Object.values(body)) if (!AMOUNT_INPUT_RE.test(v) || toDecimal(v).lt(1)) return setError('Limits must be at least ₹1.')
    if (l && body.perTransfer && toDecimal(body.perTransfer).gt(l.perTransferMax)) return setError(`The per-transfer limit can’t go above ${formatINR(l.perTransferMax)}.`)
    if (l && body.daily && toDecimal(body.daily).gt(l.dailyMax)) return setError(`The daily limit can’t go above ${formatINR(l.dailyMax)}.`)
    save.mutate(body, {
      onSuccess: () => {
        toast.success('Limits updated')
        setPerTransfer('')
        setDaily('')
      },
      onError: (err) => setError(toApiError(err).fieldErrors[0]?.message ?? errorMessage(err)),
    })
  }

  return (
    <Panel eyebrow="Spending safety" title="Transfer limits" description="Lower them any time. You can’t raise them above the bank’s maximum.">
      {limits.isError ? (
        <PanelError what="your limits" onRetry={() => void limits.refetch()} />
      ) : !l ? (
        <Skeleton className="h-56 w-full" />
      ) : (
        <>
          <div className="rounded-2xl bg-surface-2/60 p-4 hairline">
            <div className="flex items-baseline justify-between text-sm">
              <span className="text-muted">Sent to others today</span>
              <span className="num">
                {formatINR(l.usedToday)} <span className="text-muted">/ {formatINR(l.daily)}</span>
              </span>
            </div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-surface-3">
              <div className="h-full rounded-full bg-accent" style={{ width: `${ratio(l.usedToday, l.daily) * 100}%` }} />
            </div>
            <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
              <div>
                <dt className="text-xs text-muted">Per transfer</dt>
                <dd className="num">{formatINR(l.perTransfer)}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted">Per day</dt>
                <dd className="num">{formatINR(l.daily)}</dd>
              </div>
            </dl>
          </div>
          <form onSubmit={submit} className="mt-5 space-y-3" noValidate>
            <AmountInput label="New per-transfer limit" className="h-12 [&_input]:text-lg" value={perTransfer} onValueChange={setPerTransfer} hint={`Max ${formatINR(l.perTransferMax)}`} />
            <AmountInput label="New daily limit" className="h-12 [&_input]:text-lg" value={daily} onValueChange={setDaily} hint={`Max ${formatINR(l.dailyMax)}`} />
            {error && (
              <p role="alert" className="text-sm text-loss">
                {error}
              </p>
            )}
            <Button type="submit" disabled={save.isPending}>
              <ShieldCheck aria-hidden="true" /> {save.isPending ? 'Saving…' : 'Update limits'}
            </Button>
          </form>
        </>
      )}
    </Panel>
  )
}

const PAN_RE = /^[A-Z]{5}\d{4}[A-Z]$/

function KycPanel() {
  const kyc = useKyc()
  const save = useSetKyc()
  const [pan, setPan] = useState('')
  const [dob, setDob] = useState('')
  const [address, setAddress] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const k = kyc.data
  const [editing, setEditing] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    const next: Record<string, string> = {}
    if (!PAN_RE.test(pan)) next.pan = 'PAN looks like ABCDE1234F'
    if (!dob) next.dateOfBirth = 'Enter your date of birth'
    if (address.trim().length < 10) next.address = 'Enter your full address (at least 10 characters)'
    setErrors(next)
    if (Object.keys(next).length) return
    save.mutate(
      { pan, dateOfBirth: dob, address: address.trim() },
      {
        onSuccess: () => {
          toast.success('KYC verified')
          setEditing(false)
          setPan('')
        },
        onError: (err) => {
          const ae = toApiError(err)
          if (ae.fieldErrors.length) setErrors(Object.fromEntries(ae.fieldErrors.map((f) => [f.field, f.message])))
          else toast.error(errorMessage(ae))
        },
      },
    )
  }

  const verified = k?.status === 'VERIFIED'
  return (
    <Panel
      eyebrow="Identity"
      title="KYC profile"
      description="Needed for fixed deposits, loans above ₹2,00,000 and investing with Autopilot. Your PAN is encrypted; we never ask for Aadhaar."
      action={k && <Badge tone={verified ? 'gain' : k.status === 'PENDING' ? 'warn' : 'neutral'} dot>{verified ? 'Verified' : k.status === 'PENDING' ? 'Pending' : 'Not started'}</Badge>}
    >
      {kyc.isError ? (
        <PanelError what="your KYC" onRetry={() => void kyc.refetch()} />
      ) : !k ? (
        <Skeleton className="h-48 w-full" />
      ) : verified && !editing ? (
        <div className="space-y-4">
          <dl className="divide-y divide-border text-sm">
            <KycRow label="PAN">
              <span className="num">{k.panMasked}</span>
            </KycRow>
            <KycRow label="Date of birth">{k.dateOfBirth}</KycRow>
            <KycRow label="Address">{k.address}</KycRow>
            {k.verifiedAt && <KycRow label="Verified">{formatAbsolute(k.verifiedAt)}</KycRow>}
          </dl>
          <Button variant="secondary" size="sm" onClick={() => setEditing(true)}>
            Update details
          </Button>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4" noValidate>
          <FormField label="PAN" value={pan} maxLength={10} autoComplete="off" onChange={(e) => setPan(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))} error={errors.pan} hint="Sandbox: any valid-looking PAN is accepted." />
          <FormField label="Date of birth" type="date" value={dob} onChange={(e) => setDob(e.target.value)} error={errors.dateOfBirth} />
          <FormField label="Address" value={address} maxLength={200} autoComplete="street-address" onChange={(e) => setAddress(e.target.value)} error={errors.address} />
          <div className="flex gap-2">
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? 'Verifying…' : verified ? 'Save changes' : 'Verify'}
            </Button>
            {editing && (
              <Button variant="ghost" onClick={() => setEditing(false)}>
                Cancel
              </Button>
            )}
          </div>
        </form>
      )}
    </Panel>
  )
}

function KycRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-4 py-2.5">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right">{children}</dd>
    </div>
  )
}

// ── Developer ───────────────────────────────────────────────────────────────────────────
function DeveloperTab() {
  const tokens = useTokens()
  const create = useCreateToken()
  const revoke = useRevokeToken()
  const [name, setName] = useState('')
  const [scope, setScope] = useState<TokenScope>('read')
  const [days, setDays] = useState<'7' | '30' | '90'>('30')
  const [created, setCreated] = useState<CreatedTokenDto | null>(null)
  const [copied, setCopied] = useState(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    create.mutate(
      { name: name.trim(), scope, expiresInDays: Number(days) },
      {
        onSuccess: (t) => {
          setCreated(t)
          setName('')
        },
        onError: (err) => toast.error(toApiError(err).fieldErrors[0]?.message ?? errorMessage(err)),
      },
    )
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
      <Panel
        className="lg:col-span-5"
        tone="violet"
        eyebrow="MCP server"
        title="Connect an AI assistant"
        description="Let Claude Desktop or another MCP client read your QuantGen data. With “read + propose” it can suggest payments, which you still approve here."
      >
        <form onSubmit={submit} className="space-y-4">
          <FormField label="Token name" placeholder="Claude Desktop" value={name} maxLength={40} onChange={(e) => setName(e.target.value)} />
          <div className="space-y-1.5">
            <p className="text-sm font-medium">Access</p>
            <Segmented label="Scope" value={scope} onValueChange={setScope} options={[{ value: 'read', label: 'Read only' }, { value: 'read+propose', label: 'Read + propose' }]} />
          </div>
          <div className="space-y-1.5">
            <p className="text-sm font-medium">Expires in</p>
            <Segmented label="Expiry" value={days} onValueChange={setDays} options={[{ value: '7', label: '7 days' }, { value: '30', label: '30 days' }, { value: '90', label: '90 days' }]} />
          </div>
          <Button type="submit" variant="violet" disabled={!name.trim() || create.isPending}>
            <Terminal aria-hidden="true" /> {create.isPending ? 'Creating…' : 'Create token'}
          </Button>
        </form>
      </Panel>
      <Panel className="lg:col-span-7" eyebrow="Active" title="Access tokens">
        {tokens.isError ? (
          <PanelError what="tokens" onRetry={() => void tokens.refetch()} />
        ) : !tokens.data ? (
          <Skeleton className="h-32 w-full" />
        ) : tokens.data.length === 0 ? (
          <EmptyState icon={<Terminal />} title="No tokens">Create one to connect an MCP client.</EmptyState>
        ) : (
          <ul className="divide-y divide-border">
            {tokens.data.map((t) => (
              <li key={t.id} className="flex items-center gap-3 py-3">
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-2 text-sm font-medium">
                    {t.name}
                    <Badge tone={t.scope === 'read' ? 'cyan' : 'violet'}>{t.scope === 'read' ? 'read' : 'read + propose'}</Badge>
                  </p>
                  <p className="text-xs text-muted">
                    Expires {formatRelative(t.expiresAt)} · {t.lastUsedAt ? `used ${formatRelative(t.lastUsedAt)}` : 'never used'}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(t.id, { onSuccess: () => toast.success('Token revoked'), onError: (e) => toast.error(errorMessage(e)) })}
                >
                  Revoke
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <Dialog
        open={created !== null}
        onOpenChange={(o) => {
          if (!o) {
            setCreated(null)
            setCopied(false)
          }
        }}
      >
        <DialogContent title="Copy your token now" description="This is the only time it’s shown. We store only a hash, so we can’t show it again.">
          <div className="num break-all rounded-xl bg-surface-2 p-4 text-sm hairline">{created?.token}</div>
          <div className="mt-5 flex justify-end gap-2">
            <Button
              variant="secondary"
              onClick={() => {
                if (created) void navigator.clipboard.writeText(created.token).then(() => setCopied(true))
              }}
            >
              {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />} {copied ? 'Copied' : 'Copy'}
            </Button>
            <Button
              onClick={() => {
                setCreated(null)
                setCopied(false)
              }}
            >
              Done
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
