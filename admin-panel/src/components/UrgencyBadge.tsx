import { useI18n } from '../i18n/I18nContext'
import type { Urgency } from '../api/types'

/** Renders the semantic urgency code as a localized, color-coded badge. */
export function UrgencyBadge({ urgency }: { urgency: Urgency }) {
  const { tCode } = useI18n()
  return (
    <span className={`badge urgency-${urgency}`} data-urgency={urgency}>
      {tCode('urgency', urgency)}
    </span>
  )
}
