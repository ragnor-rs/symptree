import { useState, type ReactNode } from 'react'
import { useI18n } from '../i18n/I18nContext'

interface ConfirmDialogProps {
  message: string
  /** Optional labelled free-text field (e.g. a cancellation reason). */
  withReason?: boolean
  reasonLabel?: string
  onConfirm: (reason: string) => void
  onCancel: () => void
  children?: ReactNode
}

/** A modal confirm prompt; optionally collects a reason (booking cancel — AC-XKH348.6). */
export function ConfirmDialog({
  message,
  withReason,
  reasonLabel,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const { t } = useI18n()
  const [reason, setReason] = useState('')

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <div className="modal">
        <p>{message}</p>
        {withReason && (
          <label className="field">
            <span>{reasonLabel}</span>
            <input value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
        )}
        <div className="modal-actions">
          <button type="button" className="secondary" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="button" className="danger" onClick={() => onConfirm(reason)}>
            {t('common.confirm')}
          </button>
        </div>
      </div>
    </div>
  )
}
