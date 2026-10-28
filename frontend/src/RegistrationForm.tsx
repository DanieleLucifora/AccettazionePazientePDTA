import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, completeAccess, registerPatient, getRegistrationOptions, type RegistrationOptions, type RegistrationInput, type RegistrationProgress } from './api';

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
  const [options, setOptions] = useState<RegistrationOptions | null>(null);
  const [catalogError, setCatalogError] = useState('');
  const [catalogAttempt, setCatalogAttempt] = useState(0);
  const lock = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    setCatalogError('');
    void getRegistrationOptions(controller.signal).then(result => { if (!controller.signal.aborted) setOptions(result); })
      .catch(cause => {
        if (!controller.signal.aborted) setCatalogError(cause instanceof Error ? cause.message : 'Catalogo diagnosi non disponibile.');
      });
    return () => controller.abort();
  }, [catalogAttempt]);

  async function advance(key: string) {
    const next = await completeAccess(key);
    setProgress(next);
    if (next.status === 'COMPLETED') sessionStorage.removeItem(pendingKey);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (lock.current || !options) return;
    const data = new FormData(event.currentTarget);
    if (!draft) {
      setDraft({
        patientID: String(data.get('patientID')).trim(),
        firstName: String(data.get('firstName')).trim().replace(/\s+/g, ' '),
        lastName: String(data.get('lastName')).trim().replace(/\s+/g, ' '),
        birthDate: String(data.get('birthDate')),
        diagnosiCode: String(data.get('diagnosiCode')).trim(),
        clinicalFileUrl: String(data.get('clinicalFileUrl')).trim(),
      });
      return;
    }
    lock.current = true; setBusy(true);
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
      lock.current = false;
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
      {!progress && catalogError && <div className="notice error" role="alert"><p>{catalogError}</p>
        <button className="secondary" onClick={() => setCatalogAttempt(value => value + 1)}>Ricarica codici diagnosi</button></div>}
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
          <fieldset disabled={busy || uncertain || !options || !!catalogError} hidden={draft !== null}>
            <div className="form-grid">
              <label>ID paziente <span aria-hidden="true">*</span>
                <input name="patientID" required maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9._\-]{0,63}" autoComplete="off" placeholder="Es. DEMO-001" />
                <small>Identificativo interno, non il codice fiscale. Lettere, numeri, punto, trattino e underscore; zeri iniziali conservati.</small>
              </label>
              <label>Codice diagnosi <span aria-hidden="true">*</span>
                <select name="diagnosiCode" required defaultValue="">
                  <option value="">{options ? 'Seleziona codice' : 'Caricamento codici...'}</option>
                  {options?.diagnosisCodes.map(code => <option key={code} value={code}>{code}</option>)}
                </select>
                <small>Codici previsti dalla DMN corrente; nessuna descrizione clinica disponibile nel modello.</small>
              </label>
              <label>Nome <span aria-hidden="true">*</span>
                <input name="firstName" required maxLength={100} pattern=".*\p{L}.*" autoComplete="off" />
              </label>
              <label>Cognome <span aria-hidden="true">*</span>
                <input name="lastName" required maxLength={100} pattern=".*\p{L}.*" autoComplete="off" />
              </label>
              <label>Data di nascita <span aria-hidden="true">*</span>
                <input name="birthDate" type="date" required max={options?.today} autoComplete="off" />
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
              <dt>Nome</dt><dd>{draft.firstName}</dd>
              <dt>Cognome</dt><dd>{draft.lastName}</dd>
              <dt>Data di nascita</dt><dd>{draft.birthDate}</dd>
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
