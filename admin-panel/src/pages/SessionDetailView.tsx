import { useI18n } from '../i18n/I18nContext'
import { UrgencyBadge } from '../components/UrgencyBadge'
import type { Conclusion, SessionDetail } from '../api/types'

/**
 * Read-only renderer for a symptomizer session (AC-XKH348.5): the walked Q→A
 * path followed by the semantic conclusion. All algorithm-authored text
 * (diagnosis/action/tests) is rendered verbatim — the backend already localized
 * it to the session language; only chrome + semantic codes are localized here.
 */
export function SessionDetailView({ detail }: { detail: SessionDetail }) {
  const { t } = useI18n()
  return (
    <section className="session-detail">
      <div className="page-head">
        <h1>{detail.patient_ref}</h1>
        <span className="tag">{detail.status}</span>
      </div>

      <h2>{t('sessions.history')}</h2>
      <ol className="walk">
        {detail.history.map((turn, idx) => (
          <li key={idx} className="walk-turn">
            {turn.node_id && <span className="node-ref">{turn.node_id}</span>}
            {(turn.user_text || turn.picked_option) && (
              <span className="answer">
                {t('sessions.answer')}: {turn.user_text ?? turn.picked_option}
              </span>
            )}
          </li>
        ))}
      </ol>

      {detail.conclusion ? (
        <ConclusionBlock conclusion={detail.conclusion} />
      ) : (
        <p className="empty">{t('sessions.no_conclusion')}</p>
      )}
    </section>
  )
}

function ConclusionBlock({ conclusion }: { conclusion: Conclusion }) {
  const { t, tCode } = useI18n()
  return (
    <div className="conclusion">
      <h2>{t('conclusion.title')}</h2>
      <dl>
        <dt>{t('conclusion.urgency')}</dt>
        <dd>
          <UrgencyBadge urgency={conclusion.urgency} />
        </dd>

        {conclusion.specialist && (
          <>
            <dt>{t('conclusion.specialist')}</dt>
            <dd>{tCode('specialist', conclusion.specialist)}</dd>
          </>
        )}

        <dt>{t('conclusion.diagnosis')}</dt>
        <dd>{conclusion.diagnosis}</dd>

        <dt>{t('conclusion.action')}</dt>
        <dd>{conclusion.action}</dd>

        {conclusion.tests.length > 0 && (
          <>
            <dt>{t('conclusion.tests')}</dt>
            <dd>
              <ul className="tests">
                {conclusion.tests.map((test) => (
                  <li key={test.name}>
                    {test.name}
                    {test.prep && <span className="prep"> · {t('conclusion.prep')}: {test.prep}</span>}
                  </li>
                ))}
              </ul>
            </dd>
          </>
        )}

        <dt>{t('conclusion.clinic_action')}</dt>
        <dd>{tCode('clinic_action', conclusion.clinic_action)}</dd>

        {conclusion.follow_up_days != null && (
          <>
            <dt>{t('conclusion.follow_up_days')}</dt>
            <dd>{conclusion.follow_up_days}</dd>
          </>
        )}
      </dl>
    </div>
  )
}
