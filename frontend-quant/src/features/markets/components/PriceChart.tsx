import {
  AreaSeries,
  ColorType,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import { useEffect, useRef } from 'react'
import type { InstrumentDto, PricePointDto } from '@/api/types'
import { formatINR, toDecimal } from '@/lib/money'
import { useTheme } from '@/lib/theme'

const toTime = (iso: string) => Math.floor(Date.parse(iso) / 1000) as UTCTimestamp

/** Prices become numbers only for plotting (display), never for money math. */
function toSeries(points: PricePointDto[]) {
  const seen = new Set<number>()
  const out: Array<{ time: UTCTimestamp; value: number }> = []
  for (const p of [...points].sort((a, b) => a.t.localeCompare(b.t))) {
    const time = toTime(p.t)
    if (seen.has(time)) continue
    seen.add(time)
    out.push({ time, value: toDecimal(p.price).toNumber() })
  }
  return out
}

function cssVar(name: string) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

export function PriceChart({
  points,
  live,
  label,
}: {
  points: PricePointDto[]
  /** Latest tick, appended to the series as it arrives. */
  live?: InstrumentDto
  label: string
}) {
  const el = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const series = useRef<ISeriesApi<'Area'> | null>(null)
  const lastTime = useRef<number>(0)
  const theme = useTheme((s) => s.theme)

  // Create once.
  useEffect(() => {
    if (!el.current) return
    const c = createChart(el.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        attributionLogo: false,
      },
      grid: { vertLines: { visible: false }, horzLines: { visible: false } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false, timeVisible: true, secondsVisible: false },
      crosshair: { horzLine: { labelVisible: true }, vertLine: { labelVisible: true } },
      localization: {
        priceFormatter: (v: number) => formatINR(toDecimal(v.toFixed(2))),
      },
      handleScale: { mouseWheel: false },
    })
    chart.current = c
    series.current = c.addSeries(AreaSeries, { lineWidth: 2, priceLineVisible: false })
    return () => {
      c.remove()
      chart.current = null
      series.current = null
    }
  }, [])

  // Theme-aware colours from the CSS tokens.
  useEffect(() => {
    const c = chart.current
    const s = series.current
    if (!c || !s) return
    const text = cssVar('--text-muted')
    const border = cssVar('--border')
    c.applyOptions({
      layout: { textColor: text, fontFamily: 'Geist Mono Variable, ui-monospace, monospace' },
      crosshair: {
        horzLine: { color: border, labelBackgroundColor: cssVar('--surface-2') },
        vertLine: { color: border, labelBackgroundColor: cssVar('--surface-2') },
      },
    })
  }, [theme])

  // Data + trend colour.
  useEffect(() => {
    const s = series.current
    if (!s) return
    const data = toSeries(points)
    const first = data[0]
    const last = data[data.length - 1]
    const up = !first || !last || last.value >= first.value
    const color = cssVar(up ? '--gain' : '--loss')
    s.applyOptions({
      lineColor: color,
      topColor: `color-mix(in oklab, ${color} 28%, transparent)`,
      bottomColor: `color-mix(in oklab, ${color} 0%, transparent)`,
    })
    s.setData(data)
    lastTime.current = last?.time ?? 0
    chart.current?.timeScale().fitContent()
  }, [points, theme])

  // Live ticks.
  useEffect(() => {
    const s = series.current
    if (!s || !live) return
    const time = toTime(live.updatedAt)
    if (time < lastTime.current) return
    s.update({ time, value: toDecimal(live.price).toNumber() })
    lastTime.current = time
  }, [live])

  return <div ref={el} role="img" aria-label={label} className="h-72 w-full sm:h-96" />
}
