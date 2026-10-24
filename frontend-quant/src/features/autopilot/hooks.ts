import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { autopilotApi } from '@/api/endpoints.v2'
import type { RuleDsl } from '@/api/types'

export const autopilotKeys = {
  rules: ['autopilot', 'rules'] as const,
  rule: (id: string) => ['autopilot', 'rules', id] as const,
  runs: (id: string, page: number) => ['autopilot', 'runs', id, page] as const,
  templates: ['autopilot', 'templates'] as const,
}

export const useRules = (enabled = true) =>
  useQuery({ queryKey: autopilotKeys.rules, queryFn: autopilotApi.rules, enabled })
export const useRule = (id: string, enabled = true) =>
  useQuery({
    queryKey: autopilotKeys.rule(id),
    queryFn: () => autopilotApi.rule(id),
    enabled: enabled && !!id,
  })
export const useRuleRuns = (id: string, page = 0) =>
  useQuery({
    queryKey: autopilotKeys.runs(id, page),
    queryFn: () => autopilotApi.runs(id, { page, size: 10 }),
    placeholderData: keepPreviousData,
  })
export const useTemplates = () =>
  useQuery({
    queryKey: autopilotKeys.templates,
    queryFn: autopilotApi.templates,
    staleTime: 10 * 60_000,
  })

export function useRuleMutation() {
  const qc = useQueryClient()
  const done = () => qc.invalidateQueries({ queryKey: ['autopilot'] })
  return {
    create: useMutation({
      mutationFn: (b: { name: string; rule: RuleDsl }) => autopilotApi.create(b.name, b.rule),
      onSuccess: done,
    }),
    update: useMutation({
      mutationFn: (b: { id: string; name: string; rule: RuleDsl }) =>
        autopilotApi.update(b.id, b.name, b.rule),
      onSuccess: done,
    }),
    pause: useMutation({ mutationFn: (id: string) => autopilotApi.pause(id), onSuccess: done }),
    resume: useMutation({ mutationFn: (id: string) => autopilotApi.resume(id), onSuccess: done }),
    remove: useMutation({ mutationFn: (id: string) => autopilotApi.remove(id), onSuccess: done }),
  }
}
