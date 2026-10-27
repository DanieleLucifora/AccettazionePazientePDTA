import { useEffect, useRef, useState, type FormEvent } from 'react';
import { claimTask, decideTask, requestExam, type SpecialistDecision, type SpecialistRole, type TaskDetail } from './api';
import { DiagnosticExamForm, type ExamDraft } from './DiagnosticExamForm';

export function SpecialistTaskActions({ task, role, refresh, onCompleted }: {
  task: TaskDetail;
  role: SpecialistRole;
  refresh: () => Promise<unknown>;
  onCompleted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const locked = useRef(false);
  const [error, setError] = useState('');
  const [needsVerification, setNeedsVerification] = useState(false);
  const [claimed, setClaimed] = useState(false);
  const [sent, setSent] = useState(false);
  const [outcome, setOutcome] = useState<SpecialistDecision['outcome'] | 'EXAM'>('APPROVE');
  const [note, setNote] = useState('');
  const [review, setReview] = useState<({ kind: 'decision' } & SpecialistDecision) | ExamDraft | null>(null);
  const mine = task.assignee === task.currentOperatorId;
  const other = Boolean(task.assignee) && !mine;

  useEffect(() => {
    if (task.assignee) setClaimed(false);
  }, [task.assignee]);

  async function act(kind: 'claim' | 'decide') {
    if (locked.current) return;
    locked.current = true;
    let completed = false;
    setBusy(true);
    setError('');
    try {
      if (kind === 'claim') {
        await claimTask(task.taskKey, role);
        setClaimed(true);
        await refresh();
      } else if (review) {
        if (review.kind === 'exam') await requestExam(task.taskKey, role, review.request);
        else await decideTask(task.taskKey, role, { outcome: review.outcome, note: review.note });
        completed = true;
        setSent(true);
        onCompleted();
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Impossibile confermare l'operazione.");
      setNeedsVerification(true);
    } finally {
      if (!completed) locked.current = false;
      setBusy(false);
    }
  }

  async function verify() {
    setBusy(true);
    try {
      await refresh();
      setNeedsVerification(false);
      setReview(null);
      setError('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Impossibile verificare lo stato.");
      setNeedsVerification(true);
    } finally {
      setBusy(false);
    }
  }

  function prepare(event: FormEvent) {
    event.preventDefault();
    if (outcome !== 'EXAM') setReview({ kind: 'decision', outcome, note: note.trim() });
  }

  return (
    <section className="detail-section" aria-labelledby="decision-title">
      <h3 id="decision-title">Gestisci la valutazione</h3>
      {error && <div className="notice error" role="alert">
        <p>{error}</p><p>L'esito non e' confermato. Verifica lo stato prima di un nuovo invio.</p>
        <button className="secondary" disabled={busy} onClick={() => void verify()}>Verifica stato attivita'</button>
      </div>}
      {other ? <p className="notice">Attivita' assegnata a un altro operatore: {task.assignee}. Non puoi inviare un esito.</p>
        : !mine ? <>
          <p>Prendi in carico l'attivita' come operatore simulato <strong>{task.currentOperatorId}</strong>.</p>
          {claimed && <p role="status">Presa in carico ricevuta. Attendo la visibilita' dell'assegnazione.</p>}
          <button disabled={busy || needsVerification || claimed} onClick={() => void act('claim')}>
            {busy ? 'Presa in carico...' : 'Prendi in carico'}
          </button>
        </> : <>
        {review && <div>
          <h4>Conferma esito della valutazione</h4>
          <dl className="review">
            <dt>Paziente</dt><dd>{task.patientID}</dd>
            <dt>Esito</dt><dd>{review.kind === 'exam' ? 'Richiesta esami' : review.outcome === 'APPROVE' ? 'Approvazione' : 'Rifiuto'}</dd>
            {review.kind === 'exam' ? <>
              <dt>Esame</dt><dd>{review.request.examType} · {review.request.bodyPart.replaceAll('_', ' ')}</dd>
              <dt>Schedulazione</dt><dd>{review.slot
                ? `${review.slot.startTime.replace('T', ' ')} - ${review.slot.endTime.slice(11, 16)} · ${review.slot.resourceCode}`
                : 'Primo slot libero (automatico)'}</dd>
              <dt>Note per laboratorio</dt><dd>{review.request.note || 'Nessuna nota'}</dd>
            </> : <>
              <dt>{review.outcome === 'REJECT' ? 'Motivazione' : 'Note'}</dt><dd>{review.note || 'Nessuna nota'}</dd>
            </>}
          </dl>
          <p>Confermando completi il task. Il percorso proseguira' secondo le regole Camunda.</p>
          <div className="form-footer">
            <button className="secondary" disabled={busy || needsVerification || sent} onClick={() => setReview(null)}>Modifica esito</button>
            <button disabled={busy || needsVerification || sent} className={review.kind === 'decision' && review.outcome === 'REJECT' ? 'danger' : ''}
              onClick={() => void act('decide')}>{busy ? 'Invio in corso...' : review.kind === 'exam' ? 'Conferma e richiedi esame' : 'Conferma e invia esito'}</button>
          </div>
        </div>}
        <div hidden={Boolean(review)}>
          <label className="decision-label">Esito
            <select disabled={busy || needsVerification || sent} value={outcome} onChange={event => {
              const value = event.target.value;
              if (value === 'APPROVE' || value === 'REJECT' || value === 'EXAM') setOutcome(value);
            }}>
              <option value="APPROVE">Approva</option><option value="REJECT">Rifiuta</option>
              <option value="EXAM">Richiedi esami diagnostici</option>
            </select>
          </label>
          {outcome === 'EXAM' ? <DiagnosticExamForm disabled={busy || needsVerification || sent} onReview={setReview} />
            : <form onSubmit={prepare}>
          <fieldset disabled={busy || needsVerification}>
            <label className="decision-label">{outcome === 'REJECT' ? 'Motivazione del rifiuto (obbligatoria)' : 'Note (facoltative)'}
              <textarea value={note} onChange={event => setNote(event.target.value)} maxLength={2000}
                required={outcome === 'REJECT'} rows={3} />
            </label>
            <button type="submit" disabled={outcome === 'REJECT' && !note.trim()}>Rivedi esito</button>
          </fieldset>
        </form>}
        </div>
        </>}
    </section>
  );
}
