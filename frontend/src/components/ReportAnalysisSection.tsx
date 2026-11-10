import { useAnalysis } from '../api/queries'
import type { AnalysisPoint, ReportAnalysis } from '../api/types'
import { formatDate, formatDateTime } from '../format'
import { Term } from './Term'

/**
 * Ce que dit le rapport annuel, lu par le modèle de langage local : ce que fait la société, ses
 * avantages, ses risques. Chaque point montre la phrase du rapport qui l'appuie (vérifiée mot pour
 * mot par le backend). Affiché à côté du score, jamais pris en compte dedans.
 */
export function ReportAnalysisSection({ isin }: { isin: string }) {
  const { data, error } = useAnalysis(isin)
  const analysis = data?.analysis ?? null
  const busy = data?.state === 'QUEUED' || data?.state === 'RUNNING'
  return (
    <section className="card report-analysis">
      <h2>Ce que dit le rapport annuel <span className="muted small">(<Term id="REPORT_ANALYSIS">lu par une IA locale</Term>)</span></h2>
      {error && <p className="muted">Lecture indisponible.</p>}
      {data?.state === 'NONE' && <p className="muted">Le dernier rapport annuel de cette société n'a pas encore été lu.</p>}
      {busy && (
        <p className="notice notice-warn" role="status">
          {data?.state === 'QUEUED' ? 'Lecture du rapport en attente' : 'Lecture du rapport en cours'} : quelques minutes,
          la page se mettra à jour toute seule.
        </p>
      )}
      {data?.state === 'FAILED' && (
        <p className="notice notice-bad">La dernière lecture a échoué : {data.error}</p>
      )}
      {analysis && <AnalysisBody analysis={analysis} />}
    </section>
  )
}

function AnalysisBody({ analysis }: { analysis: ReportAnalysis }) {
  return (
    <>
      <p className="muted small">
        Rédigé par le modèle <strong>{analysis.model}</strong> à partir du rapport de l'exercice clos le{' '}
        {formatDate(analysis.periodEnd)}. Chaque point s'appuie sur une phrase du rapport,{' '}
        <Term id="VERIFIED_QUOTE">vérifiée mot pour mot</Term> ; l'interprétation, elle, peut être fausse ou
        incomplète. Ce texte n'entre pas dans le score.
        {analysis.rankedAt && <> Dans chaque thème, les points vont du plus important au moins important, selon le modèle.</>}
      </p>
      <div className="analysis-grid">
        {analysis.themes.map(theme => (
          <div key={theme.key}>
            <h3>{theme.title}</h3>
            {theme.points.length === 0 ? (
              <p className="muted small">Rien d'appuyé par le rapport sur ce thème.</p>
            ) : (
              <ol className="analysis-points">
                {theme.points.map((p, i) => <Point key={i} point={p} />)}
              </ol>
            )}
          </div>
        ))}
      </div>
      <p className="muted small">
        Lu le {formatDateTime(analysis.createdAt)} en {Math.round(analysis.durationMs / 60000)} min
        ({analysis.passageCount} passages de texte).
        {analysis.rejected > 0 && <> {analysis.rejected} point{analysis.rejected > 1 ? 's' : ''} proposé{analysis.rejected > 1 ? 's' : ''} par
          le modèle {analysis.rejected > 1 ? 'ont été écartés' : 'a été écarté'} : leur citation était introuvable dans le rapport.</>}
      </p>
    </>
  )
}

function Point({ point }: { point: AnalysisPoint }) {
  const at = point.passage.indexOf(point.quote)
  return (
    <li>
      <strong>{point.title}</strong>
      {point.explanation && <> : {point.explanation}</>}
      <details>
        <summary>Ce que dit le rapport</summary>
        <blockquote>« {point.quote} »</blockquote>
        <p className="muted small">
          Le passage complet :{' '}
          {at >= 0 ? (
            <>{point.passage.slice(0, at)}<mark>{point.quote}</mark>{point.passage.slice(at + point.quote.length)}</>
          ) : point.passage}
        </p>
      </details>
    </li>
  )
}
