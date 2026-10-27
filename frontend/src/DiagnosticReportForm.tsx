import { useEffect, useRef, useState } from 'react';
import { getDiagnosticReport, submitDiagnosticReport, type DiagnosticReport } from './api';

export function DiagnosticReportForm({ taskKey, report, allowed, readOnly, unavailable = false, onSent }: {
  taskKey: string; report: DiagnosticReport | null; allowed: boolean; readOnly: boolean;
  unavailable?: boolean; onSent: () => void;
}) {
  const [text, setText] = useState('');
  const [saved, setSaved] = useState(report);
  const [review, setReview] = useState(false);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState('');
  const [verified, setVerified] = useState(false);
  const lock = useRef(false);
  const sent = useRef(report?.status === 'SENT');
  useEffect(() => {
    if (report) setSaved(report);
  }, [report]);

  async function submit() {
    if (lock.current || sent.current || readOnly || uncertain) return;
    lock.current = true; setBusy(true); setError('');
    try {
      const result = await submitDiagnosticReport(taskKey, saved?.text ?? text.trim());
      setSaved(result);
      if (result.status === 'SENT') { sent.current = true; onSent(); }
      setReview(false);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Invio del referto non confermato.');
      setUncertain(true);
    } finally { lock.current = false; setBusy(false); }
  }

  async function verify() {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError('');
    try {
      const result = await getDiagnosticReport(taskKey);
      setSaved(result.report); setVerified(true); setReview(false); setUncertain(false);
      if (result.report?.status === 'SENT') { sent.current = true; onSent(); }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Stato del referto non verificabile.');
      setUncertain(true);
    } finally { lock.current = false; setBusy(false); }
  }

  const blocked = busy || uncertain || readOnly || sent.current;
  return <section aria-label="Refertazione diagnostica">
    <h3>Referto diagnostico</h3>
    {error && <div role="alert" className="notice error"><p>{error}</p>
      <p>Non reinserire il referto. Verifica prima il salvataggio e l'esito Camunda.</p></div>}
    {saved ? <div className="notice">
      <h4>{saved.status === 'SENT' ? 'Referto inviato allo specialista' : 'Referto salvato, invio da verificare'}</h4>
      <p className="report-text">{saved.text}</p>
      <p>Salvato il {saved.reportedAt.replace('T', ' ')} da LAB001 · Mario Neri.</p>
      {saved.deliveredAt && <p>Invio confermato il {saved.deliveredAt.replace('T', ' ')}.</p>}
      {saved.status === 'PENDING' && <><p>Il testo e' definitivo. Il recupero invia lo stesso referto senza duplicare lo storico.</p>
        {!readOnly && <button disabled={blocked} onClick={() => void submit()}>Riprendi invio referto salvato</button>}</>}
    </div> : !unavailable && !readOnly && <>
      {!allowed ? <p className="notice" role="status">Refertazione disponibile solo dopo la fine della fascia prenotata, con prenotazione attiva.</p>
        : review ? <div className="notice">
          <h4>Conferma referto diagnostico</h4>
          <p className="report-text">{text.trim()}</p>
          <p>Il testo diventera' definitivo; l'esame sara' completato e il referto restituito al richiedente indicato nel dettaglio.</p>
          <div className="form-footer">
            <button className="secondary" disabled={blocked} onClick={() => setReview(false)}>Modifica referto</button>
            <button disabled={blocked} onClick={() => void submit()}>Conferma e invia referto</button>
          </div>
        </div> : <form onSubmit={event => { event.preventDefault(); if (text.trim() && text.trim().length <= 10000) setReview(true); }}>
          <fieldset disabled={blocked}>
            <label className="decision-label">Testo del referto (obbligatorio)
              <textarea required maxLength={10000} rows={7} value={text} onChange={event => setText(event.target.value)} />
            </label>
            <p>Massimo 10000 caratteri. Nessun esito clinico viene generato automaticamente.
              La bozza non e' salvata finche' non confermi l'invio.</p>
            <button disabled={!text.trim()} type="submit">Rivedi referto</button>
          </fieldset>
        </form>}
    </>}
    {unavailable && verified && !saved && <p role="status">Nessun referto salvato per questo task.</p>}
    {(saved || uncertain || unavailable) && <button className="secondary" disabled={busy}
      onClick={() => void verify()}>Verifica stato referto</button>}
  </section>;
}
