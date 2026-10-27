import { useState, type FormEvent } from 'react';
import { ApiError, completeAccess, registerPatient, type RegistrationInput, type RegistrationProgress } from './api';

const pendingKey = 'pdta.pending-registration';

function savedProgress(): RegistrationProgress | null {
  const key = sessionStorage.getItem(pendingKey);
  return key && /^[1-9][0-9]{0,18}$/.test(key) ? { processInstanceKey: key, status: 'WAITING' } : null;
}

export function RegistrationForm() {
  const [progress, setProgress] = useState<RegistrationProgress | null>(savedProgress);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const [draft, setDraft] = useState<RegistrationInput | null>(null);

  async function advance(key: string) {
    const next = await completeAccess(key);
    setProgress(next);
    if (next.status === 'COMPLETED') sessionStorage.removeItem(pendingKey);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    if (!draft) {
      setDraft({
        patientID: String(data.get('patientID')).trim(),
        patientData: String(data.get('patientData')).trim(),
        diagnosiCode: String(data.get('diagnosiCode')).trim(),
        clinicalFileUrl: String(data.get('clinicalFileUrl')).trim(),
      });
      return;
    }
    setBusy(true);
    setError('');
    let created = false;
    try {
      const next = await registerPatient(draft);
      created = true;
      setProgress(next);
      sessionStorage.setItem(pendingKey, next.processInstanceKey);
      await advance(next.processInstanceKey);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Errore inatteso durante la registrazione.');
      setUncertain(!created && !(cause instanceof ApiError && cause.status >= 400 && cause.status < 500));
    } finally {
      setBusy(false);
    }
  }

  async function retryAccess() {
    if (!progress) return;
    setBusy(true);
    setError('');
    try {
      await advance(progress.processInstanceKey);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Impossibile verificare il task.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" aria-labelledby="registration-title">
      <div className="section-heading">
        <span className="step-number">01</span>
        <div><h2 id="registration-title">Nuovo accesso al percorso</h2>
          <p>Inserisci i dati del paziente per avviare la presa in carico.</p></div>
      </div>
      {error && <div className="notice error" role="alert">{error}</div>}
      {progress ? (
        <div aria-live="polite">
          <div className={`notice ${progress.status === 'COMPLETED' ? 'success' : ''}`}>
            <h3>{progress.status === 'COMPLETED' ? 'Raccolta dati completata' : 'Processo creato, accesso da completare'}</h3>
            <p>Identificativo percorso: <strong className="mono">{progress.processInstanceKey}</strong></p>
            <p>{progress.status === 'COMPLETED'
              ? 'Il workflow prosegue con la verifica automatica dei criteri di inclusione.'
              : 'Il task potrebbe non essere ancora visibile. Verifica di nuovo senza creare un altro processo.'}</p>
          </div>
          {progress.status === 'WAITING' ? (
            <button disabled={busy} onClick={() => void retryAccess()}>
              {busy ? 'Verifica in corso...' : 'Verifica e completa accesso'}
            </button>
          ) : (
            <button disabled={busy} onClick={() => { setProgress(null); setDraft(null); setError(''); setUncertain(false); }}>
              Registra un altro paziente
            </button>
          )}
        </div>
      ) : (
        <form onSubmit={event => void submit(event)}>
          <fieldset disabled={busy || uncertain} hidden={draft !== null}>
            <div className="form-grid">
              <label>ID paziente <span aria-hidden="true">*</span>
                <input name="patientID" required maxLength={64} pattern=".*\S.*" autoComplete="off" placeholder="Es. 070686" />
              </label>
              <label>Codice diagnosi <span aria-hidden="true">*</span>
                <input name="diagnosiCode" required maxLength={64} pattern=".*\S.*" autoComplete="off" placeholder="Es. 6401" />
              </label>
              <label className="full-width">Dati del paziente <span aria-hidden="true">*</span>
                <textarea name="patientData" required maxLength={2000} rows={3} placeholder="Nome, cognome e riferimenti anagrafici" />
              </label>
              <label className="full-width">Cartella clinica <small>Facoltativo</small>
                <input name="clinicalFileUrl" type="url" pattern="https?://.*" maxLength={2048} placeholder="https://..." />
              </label>
            </div>
            <div className="form-footer"><span>I campi con * sono obbligatori.</span>
              <button type="submit">{busy ? 'Avvio in corso...' : 'Avvia percorso PDTA'}</button></div>
          </fieldset>
          {draft && <div>
            <h3>Conferma i dati prima dell'avvio</h3>
            <dl className="review">
              <dt>ID paziente</dt><dd>{draft.patientID}</dd>
              <dt>Dati del paziente</dt><dd>{draft.patientData}</dd>
              <dt>Codice diagnosi</dt><dd>{draft.diagnosiCode}</dd>
              <dt>Cartella clinica</dt><dd>{draft.clinicalFileUrl || 'Non fornita'}</dd>
            </dl>
            <div className="form-footer">
              <button type="button" className="secondary" disabled={busy || uncertain} onClick={() => setDraft(null)}>Modifica i dati</button>
              <button type="submit" disabled={busy || uncertain}>{busy ? 'Avvio in corso...' : 'Conferma e avvia'}</button>
            </div>
          </div>}
          {uncertain && <div className="notice">
            <p>L'esito non e' confermato. Verifica su Camunda Operate prima di ripetere l'inserimento.</p>
            <button type="button" className="secondary" onClick={() => setUncertain(false)}>
              Ho verificato: consenti un nuovo tentativo
            </button>
          </div>}
        </form>
      )}
    </section>
  );
}
