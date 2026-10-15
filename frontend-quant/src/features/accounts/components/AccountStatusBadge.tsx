import type { AccountDto } from '@/api/types'
import { Badge } from '@/components/ui/badge'

export function AccountStatusBadge({ status }: { status: AccountDto['status'] }) {
  if (status === 'ACTIVE') return <Badge tone="gain">Active</Badge>
  if (status === 'FROZEN') return <Badge tone="warn">Frozen</Badge>
  return <Badge>Closed</Badge>
}
