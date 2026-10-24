import { Download, FileText, Loader2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { isUnavailable } from '@/api/availability'
import { tradeApi } from '@/api/endpoints.v2'
import { errorMessage } from '@/api/errors'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useTaxPnl } from '@/features/markets/tradingHooks'
import { downloadBlob } from '@/lib/download'
import { financialYear, formatDate, todayIST } from '@/lib/time'

const previousFy = (fy: string) => financialYear(`${Number.parseInt(fy.slice(0, 4), 10) - 1}-06-01`)

/** `/portfolio/tax` (F-TRD-11): realized gains per financial year, short vs long term, CSV. */
export function TaxReportPage() {
  const current = financialYear(todayIST())
  const years = [current, previousFy(current), previousFy(previousFy(current))]
  const [fy, setFy] = useState(current)
  const report = useTaxPnl(fy)
  const [downloading, setDownloading] = useState(false)

  async function download() {
    setDownloading(true)
    try {
      downloadBlob(await tradeApi.taxPnlCsv(fy), `quantgen-capital-gains-FY${fy}.csv`)
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div>
      <PageHeader
        title="Capital gains report"
        back={{ to: '/portfolio', label: 'Portfolio' }}
        description="Realized gains from shares you sold, by financial year (April to March). Holdings kept over 12 months count as long term."
        actions={
          report.data && report.data.rows.length > 0 ? (
            <Button variant="secondary" onClick={() => void download()} disabled={downloading}>
              {downloading ? (
                <Loader2 className="animate-spin" aria-hidden="true" />
              ) : (
                <Download aria-hidden="true" />
              )}
              Download CSV
            </Button>
          ) : undefined
        }
      />
      <Segmented<string>
        label="Financial year"
        value={fy}
        onValueChange={setFy}
        options={years.map((y) => ({ value: y, label: `FY ${y}` }))}
        className="mb-4"
      />
      {report.isError ? (
        <Card>
          {isUnavailable(report.error) ? (
            <EmptyState icon={<FileText />} title="Tax reports aren’t available yet">
              They arrive with Trading 2.0.
            </EmptyState>
          ) : (
            <PanelError what="the report" onRetry={() => void report.refetch()} />
          )}
        </Card>
      ) : !report.data ? (
        <Skeleton className="h-80" />
      ) : (
        <div className="space-y-4">
          <dl className="grid gap-3 sm:grid-cols-3">
            <Total label="Short-term gains" value={report.data.shortTermGains} />
            <Total label="Long-term gains" value={report.data.longTermGains} />
            <Total label="Total realized" value={report.data.total} strong />
          </dl>
          <Panel title="Sales" eyebrow={`FY ${report.data.fy}`}>
            {report.data.rows.length === 0 ? (
              <p className="text-sm text-muted">
                You didn’t sell any shares in this financial year.
              </p>
            ) : (
              <div
                className="scroll-thin -mx-1 overflow-x-auto rounded-md px-1 focus-visible:outline-2 focus-visible:outline-accent"
                tabIndex={0}
                role="region"
                aria-label="Realized gains by sale"
              >
                <table className="w-full min-w-[40rem] text-sm">
                  <caption className="sr-only">Realized gains by sale</caption>
                  <thead>
                    <tr className="border-b border-border text-left text-xs text-muted">
                      <th scope="col" className="py-2 pr-3 font-normal">
                        Symbol
                      </th>
                      <th scope="col" className="py-2 pr-3 font-normal">
                        Bought
                      </th>
                      <th scope="col" className="py-2 pr-3 font-normal">
                        Sold
                      </th>
                      <th scope="col" className="py-2 pr-3 text-right font-normal">
                        Qty
                      </th>
                      <th scope="col" className="py-2 pr-3 text-right font-normal">
                        Cost
                      </th>
                      <th scope="col" className="py-2 pr-3 text-right font-normal">
                        Proceeds
                      </th>
                      <th scope="col" className="py-2 pr-3 text-right font-normal">
                        Gain
                      </th>
                      <th scope="col" className="py-2 text-right font-normal">
                        Term
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.data.rows.map((r, i) => (
                      <tr
                        key={`${r.symbol}-${r.sellDate}-${i}`}
                        className="border-b border-border last:border-0"
                      >
                        <td className="py-2.5 pr-3 font-semibold">{r.symbol}</td>
                        <td className="py-2.5 pr-3 text-muted">{formatDate(r.buyDate)}</td>
                        <td className="py-2.5 pr-3 text-muted">{formatDate(r.sellDate)}</td>
                        <td className="num py-2.5 pr-3 text-right">{r.quantity}</td>
                        <td className="py-2.5 pr-3 text-right">
                          <Money value={r.cost} />
                        </td>
                        <td className="py-2.5 pr-3 text-right">
                          <Money value={r.proceeds} />
                        </td>
                        <td className="py-2.5 pr-3 text-right">
                          <Money value={r.gain} tone="pnl" />
                        </td>
                        <td className="py-2.5 text-right">
                          <Badge tone={r.term === 'LONG' ? 'cyan' : 'neutral'}>
                            {r.term === 'LONG' ? 'Long' : 'Short'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
          <p className="text-xs text-muted">
            For your records only, not tax advice. Figures are based on simulated prices and average
            cost.
          </p>
        </div>
      )}
    </div>
  )
}

function Total({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <Card className="p-5 sm:p-5">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className={strong ? 'mt-1 text-2xl font-medium' : 'mt-1 text-xl'}>
        <Money value={value} tone="pnl" />
      </dd>
    </Card>
  )
}
