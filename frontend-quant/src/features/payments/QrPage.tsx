import { Camera, ImageUp, QrCode, ScanLine } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { PageHeader } from '@/components/PageHeader'
import { Panel } from '@/components/Panel'
import { Button } from '@/components/ui/button'
import { useMe } from '@/features/security/hooks'
import { AMOUNT_INPUT_RE } from '@/lib/money'
import { buildPayLink, parsePayLink } from '@/lib/payLink'
import { PayTabs, QrCanvas } from './components/PayBits'

interface Detector {
  detect: (source: CanvasImageSource | ImageBitmap) => Promise<Array<{ rawValue: string }>>
}
type DetectorCtor = new (opts: { formats: string[] }) => Detector
const BarcodeDetectorCtor = (globalThis as unknown as { BarcodeDetector?: DetectorCtor })
  .BarcodeDetector

/** Show your QR (optionally with an amount) and scan someone else's (F-PAY-7). */
export function QrPage() {
  const me = useMe()
  const [amount, setAmount] = useState('')
  const handle = me.data?.handle
  const link = handle
    ? buildPayLink(window.location.origin, {
        handle,
        ...(AMOUNT_INPUT_RE.test(amount) && amount ? { amount } : {}),
      })
    : null

  return (
    <div>
      <PageHeader
        eyebrow="Pay"
        title="QR"
        description="Show your code to get paid, or scan one to pay. Scanning only pre-fills the send screen; you always confirm."
      />
      <PayTabs />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Panel eyebrow="Get paid" title="Your code" tone="violet">
          {handle && link ? (
            <div className="flex flex-col items-center gap-5 text-center">
              <div className="rounded-[10px] bg-surface-solid p-5 shadow-[var(--shadow-pop)] hairline">
                <QrCanvas value={link} size={240} />
              </div>
              <div>
                <p className="num text-lg font-medium">{handle}</p>
                {AMOUNT_INPUT_RE.test(amount) && amount && (
                  <p className="text-sm text-muted">
                    asking for <Money value={amount} className="text-text" />
                  </p>
                )}
              </div>
              <div className="w-full max-w-xs text-left">
                <AmountInput
                  label="Amount (optional)"
                  className="h-12 [&_input]:text-lg"
                  value={amount}
                  onValueChange={setAmount}
                />
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted">
              Claim your handle on the Pay overview to get a QR code.
            </p>
          )}
        </Panel>
        <Scanner />
      </div>
    </div>
  )
}

function Scanner() {
  const navigate = useNavigate()
  const video = useRef<HTMLVideoElement>(null)
  const [active, setActive] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [manual, setManual] = useState('')

  function open(text: string) {
    const link = parsePayLink(text, window.location.origin)
    if (!link) {
      setError('That isn’t a QuantGen payment code.')
      return false
    }
    const q = new URLSearchParams({
      to: link.handle,
      ...(link.amount ? { am: link.amount } : {}),
      ...(link.note ? { tn: link.note } : {}),
    })
    void navigate(`/transfer?${q.toString()}`)
    return true
  }

  useEffect(() => {
    if (!active || !BarcodeDetectorCtor) return
    let stream: MediaStream | null = null
    let raf = 0
    let stopped = false
    const detector = new BarcodeDetectorCtor({ formats: ['qr_code'] })
    void navigator.mediaDevices
      .getUserMedia({ video: { facingMode: 'environment' } })
      .then(async (s) => {
        stream = s
        if (!video.current) return
        video.current.srcObject = s
        await video.current.play()
        const tick = async () => {
          if (stopped || !video.current) return
          const codes = await detector.detect(video.current).catch(() => [])
          if (codes[0] && open(codes[0].rawValue)) return
          raf = requestAnimationFrame(() => void tick())
        }
        void tick()
      })
      .catch(() => {
        setError('Camera access was blocked. Upload a photo of the code instead.')
        setActive(false)
      })
    return () => {
      stopped = true
      cancelAnimationFrame(raf)
      stream?.getTracks().forEach((t) => t.stop())
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- open() only navigates
  }, [active])

  async function fromFile(file: File) {
    setError(null)
    if (!BarcodeDetectorCtor)
      return setError('This browser can’t read QR images. Paste the link below.')
    const bmp = await createImageBitmap(file)
    const codes = await new BarcodeDetectorCtor({ formats: ['qr_code'] })
      .detect(bmp)
      .catch(() => [])
    if (!codes[0]) setError('No QR code found in that image.')
    else open(codes[0].rawValue)
  }

  return (
    <Panel eyebrow="Pay someone" title="Scan a code" tone="cyan">
      <div className="space-y-4">
        <div className="relative grid aspect-square w-full max-w-sm place-items-center overflow-hidden rounded-[10px] bg-surface-2 hairline sm:mx-auto">
          {active ? (
            <>
              <video
                ref={video}
                className="size-full object-cover"
                muted
                playsInline
                aria-label="Camera preview"
              />
              <span
                aria-hidden="true"
                className="absolute inset-10 rounded-3xl border-2 border-accent/80"
              />
              <ScanLine aria-hidden="true" className="absolute size-10 animate-pulse text-accent" />
            </>
          ) : (
            <QrCode aria-hidden="true" className="size-16 text-muted" />
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          {BarcodeDetectorCtor && (
            <Button onClick={() => setActive((a) => !a)} variant={active ? 'secondary' : 'primary'}>
              <Camera aria-hidden="true" /> {active ? 'Stop camera' : 'Use camera'}
            </Button>
          )}
          <Button variant="secondary" asChild>
            <label className="cursor-pointer">
              <ImageUp aria-hidden="true" /> Upload image
              <input
                type="file"
                accept="image/*"
                className="sr-only"
                onChange={(e) => e.target.files?.[0] && void fromFile(e.target.files[0])}
              />
            </label>
          </Button>
        </div>
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (!open(manual)) toast.error('That link isn’t a QuantGen payment link.')
          }}
        >
          <input
            aria-label="Paste a payment link"
            placeholder="…or paste a payment link"
            value={manual}
            onChange={(e) => setManual(e.target.value)}
            className="h-10 min-w-0 flex-1 rounded-[var(--radius-control)] bg-surface-2 px-3 text-sm hairline outline-none focus:border-accent"
          />
          <Button type="submit" variant="secondary">
            Open
          </Button>
        </form>
        {error && (
          <p role="alert" className="text-sm text-warn">
            {error}
          </p>
        )}
      </div>
    </Panel>
  )
}
