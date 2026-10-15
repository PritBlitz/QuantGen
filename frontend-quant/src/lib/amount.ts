/** Keep only digits and one dot, at most 2 decimals and 12 integer digits. String in, string out. */
export function sanitizeAmount(raw: string): string {
  let s = raw.replace(/[^\d.]/g, '')
  const dot = s.indexOf('.')
  if (dot !== -1) s = s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '')
  const [int = '', frac] = s.split('.')
  const intPart = int.replace(/^0+(?=\d)/, '').slice(0, 12)
  return frac === undefined ? intPart : `${intPart}.${frac.slice(0, 2)}`
}
