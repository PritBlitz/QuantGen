import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { errorMessage } from '@/api/errors'
import { notificationsApi } from '@/api/endpoints'

/** Mark-read actions refetch the list and the header badge from the server afterwards. */
function useRefreshNotifications() {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['notifications'] })
}

export function useMarkRead() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: notificationsApi.markRead,
    onSettled: refresh,
    onError: (e) => toast.error(errorMessage(e)),
  })
}

export function useMarkAllRead() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: notificationsApi.markAllRead,
    onSettled: refresh,
    onError: (e) => toast.error(errorMessage(e)),
  })
}
