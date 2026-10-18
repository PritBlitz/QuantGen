import type { RangePreset } from '@/lib/range'
import { Segmented } from './Segmented'

export function RangePicker({
  value,
  onChange,
  className,
}: {
  value: RangePreset
  onChange: (v: RangePreset) => void
  className?: string
}) {
  return (
    <Segmented
      label="Period"
      value={value}
      onValueChange={onChange}
      className={className}
      options={(['7D', '30D', '90D', '12M'] as const).map((v) => ({ value: v, label: v }))}
    />
  )
}
