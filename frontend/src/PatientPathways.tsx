import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getPathway, getPathways, type SpecialistProgress } from './api';

const stateText = (state: string) => ({ ACTIVE: 'In corso', COMPLETED: 'Completato', TERMINATED: 'Annullato',
  CREATED: 'Aperta', CANCELED: 'Annullata' })[state] ?? state;
const dateText = (value: string) => value ? new Date(value).toLocaleString('it-IT') : 'Non disponibile';

export function PatientPathways() {
  const [patient, setPatient] = useState('');
  const [state, setState] = useState('');
  const [filter, setFilter] = useState({ patient: '', state: '', offset: 0 });
  const [selected, setSelected] = useState<string | null>(null);
  const query = useQuery({ queryKey: ['pathways', filter],
    queryFn: ({ signal }) => getPathways(filter.patient, filter.state, filter.offset, signal),
    retry: false, gcTime: 0 });
  if (selected) return <PathwayDetail key={selected} pathwayKey={selected} onBack={() => { setSelected(null); void query.refetch(); }} />;
  return <section className="card">
    <div className="section-heading split"><div><h2>Percorsi paziente</h2>
      <p>Consultazione dei percorsi conservati in Camunda, inclusi quelli precedenti. Nessuna modifica al workflow.</p></div>
      <button className="secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Aggiorna percorsi</button></div>
    <form onSubmit={event => { event.preventDefault(); setFilter({ patient: patient.trim(), state, offset: 0 }); }}>
      <div className="form-grid">
        <label>Cerca ID paziente
          <input value={patient} onChange={event => setPatient(event.target.value)} maxLength={64} autoComplete="off"
            placeholder="ID esatto, inclusi gli zeri iniziali" />
        </label>
        <label>Stato percorso<select value={state} onChange={event => setState(event.target.value)}>
          <option value="">Tutti gli stati</option><option value="ACTIVE">In corso</option>
          <option value="COMPLETED">Completati</option><option value="TERMINATED">Annullati</option>
        </select></label>
      </div>
      <div className="form-footer"><span>Ricerca esatta per ID paziente; ordinamento per avvio piu' recente.</span>
        <button type="submit" disabled={query.isFetching}>Cerca percorsi</button></div>
    </form>
    {query.isPending && <p role="status">Caricamento percorsi...</p>}
    {query.isError && <p role="alert" className="notice error">{query.error.message}</p>}
    {!query.isError && query.data && <>
      <p role="status">{query.data.total} percorsi trovati. Pagina {Math.floor(filter.offset / 20) + 1}.</p>
      {!query.data.items.length ? <p>Nessun percorso corrispondente ai filtri.</p> : <div className="table-scroll"><table>
        <caption className="sr-only">Percorsi PDTA</caption>
        <thead><tr><th>Paziente</th><th>Percorso</th><th>Avvio</th><th>Stato</th><th>Dettaglio</th></tr></thead>
        <tbody>{query.data.items.map(item => <tr key={item.key}>
          <td><strong>{item.patientID || 'ID non disponibile'}</strong><span className="cell-detail">{item.patientData || 'Anagrafica non disponibile'}</span></td>
          <td className="mono">{item.key}</td><td>{dateText(item.startDate)}</td>
          <td>{stateText(item.state)}{item.hasIncident && <span className="cell-detail">Incidente da verificare</span>}</td>
          <td><button className="secondary" aria-label={`Apri percorso ${item.key} per paziente ${item.patientID}`}
            onClick={() => setSelected(item.key)}>Apri percorso</button></td>
        </tr>)}</tbody>
      </table></div>}
      <div className="form-footer">
        <button className="secondary" disabled={!filter.offset || query.isFetching}
          onClick={() => setFilter({ ...filter, offset: Math.max(0, filter.offset - 20) })}>Pagina precedente</button>
        <button className="secondary" disabled={!query.data.hasNext || query.isFetching}
          onClick={() => setFilter({ ...filter, offset: filter.offset + 20 })}>Pagina successiva</button>
      </div>
    </>}
    <p>Camunda aggiorna gli indici in modo asincrono: dopo un comando usa Aggiorna percorsi.
      La chiusura del processo non indica da sola l'accettazione clinica del paziente.</p>
  </section>;
}

function SpecialistSummary({ item }: { item: SpecialistProgress }) {
  return <div className="notice"><h4>{item.role === 'CHIRURGHI' ? 'Chirurgo' : 'Oncologo'}</h4>
    <p>Ultima approvazione: {item.approved === null ? 'Non disponibile' : item.approved ? 'Si' : 'No'}.
      Richiesta esami: {item.examRequired === null ? 'Non disponibile' : item.examRequired ? 'Pendente' : 'Non pendente'}.</p>
    <p>Questi flag possono derivare da una richiesta di esami: non equivalgono da soli a un rifiuto clinico.</p>
    {item.note && <p className="report-text">Note: {item.note}</p>}
    {item.examResult && <p className="report-text">Ultimo esito diagnostico: {item.examResult}</p>}
  </div>;
}

function PathwayDetail({ pathwayKey, onBack }: { pathwayKey: string; onBack: () => void }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const query = useQuery({ queryKey: ['pathway', pathwayKey], queryFn: ({ signal }) => getPathway(pathwayKey, signal),
    retry: false, gcTime: 0 });
  useEffect(() => { heading.current?.focus(); }, []);
  const data = query.isError ? null : query.data;
  return <section className="card">
    <div className="section-heading split"><h2 ref={heading} tabIndex={-1}>Dettaglio percorso paziente</h2>
      <button className="secondary" onClick={onBack}>Torna ai percorsi</button></div>
    <button className="secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Aggiorna dettaglio percorso</button>
    {query.isPending && <p role="status">Caricamento percorso...</p>}
    {query.isError && <p role="alert" className="notice error">{query.error.message}</p>}
    {data && <>
      <dl className="review"><dt>Percorso</dt><dd className="mono">{data.pathway.key}</dd>
        <dt>ID paziente</dt><dd>{data.pathway.patientID || 'Non disponibile'}</dd>
        <dt>Anagrafica</dt><dd>{data.pathway.patientData || 'Non disponibile'}</dd>
        {data.pathway.firstName && <><dt>Nome</dt><dd>{data.pathway.firstName}</dd></>}
        {data.pathway.lastName && <><dt>Cognome</dt><dd>{data.pathway.lastName}</dd></>}
        <dt>Data di nascita</dt><dd>{data.pathway.birthDate || 'Non disponibile: percorso con anagrafica precedente'}</dd>
        <dt>Codice diagnosi</dt><dd>{data.pathway.diagnosiCode || 'Non disponibile'}</dd>
        <dt>Stato Camunda</dt><dd>{stateText(data.pathway.state)}</dd>
        <dt>Versione BPMN</dt><dd>{data.pathway.version}</dd>
        <dt>Avvio</dt><dd>{dateText(data.pathway.startDate)}</dd><dt>Fine</dt><dd>{dateText(data.pathway.endDate)}</dd>
      </dl>
      {data.pathway.hasIncident && <p role="alert" className="notice error">Il percorso ha un incidente Camunda. Verifica su Operate.</p>}
      <p className="notice">{data.outcome === 'PDTA_COMPLETED' ? 'Raggiunta la conclusione del percorso PDTA.'
        : data.outcome === 'BRANCH_REJECTED' ? "Raggiunto l'evento di esclusione in almeno un ramo. Altri rami possono essere ancora attivi."
          : 'Esito clinico conclusivo non determinabile dagli eventi disponibili.'}</p>
      <h3>Situazione specialisti</h3>
      <SpecialistSummary item={data.surgeon} /><SpecialistSummary item={data.oncologist} />
      <h3>Attivita' e assegnazioni</h3>
      {!data.tasks.length ? <p>Nessuna attivita' utente disponibile.</p> : <div className="table-scroll"><table>
        <thead><tr><th>Attivita'</th><th>Stato</th><th>Assegnazione</th><th>Data creazione</th></tr></thead>
        <tbody>{data.tasks.map(task => <tr key={task.key}><td>{task.name}<span className="cell-detail mono">{task.key}</span></td>
          <td>{stateText(task.state)}</td><td>{task.assignee || 'Non assegnata'}<span className="cell-detail">{task.groups.join(', ')}</span></td>
          <td>{dateText(task.createdAt)}{task.completedAt && <span className="cell-detail">Conclusa: {dateText(task.completedAt)}</span>}</td></tr>)}</tbody>
      </table></div>}
      <h3>Esami, prenotazioni e referti salvati</h3>
      <p>I referti precedenti possono apparire negli esiti degli specialisti. Il rifiuto di un esame non e' un referto clinico.</p>
      {!data.exams.length && <p>Nessuna decisione diagnostica collegata al percorso.</p>}
      {data.exams.map(exam => <div key={exam.taskKey} className="notice">
        <h4>{exam.requesterRole} · task {exam.taskKey}</h4>
        <p>Decisione: {exam.decision.action === 'REJECT' ? 'Rifiuto' : exam.decision.action === 'MODIFY' ? 'Fascia modificata' : 'Conferma'}.
          {exam.decision.reason && ` Motivazione: ${exam.decision.reason}`}</p>
        <p>Decisione salvata: {dateText(exam.decision.decidedAt)}</p>
        {exam.decision.slot && <p>{exam.decision.slot.examDisplayName} · {exam.decision.slot.resourceCode} ·
          {exam.decision.slot.startTime.replace('T', ' ')} — {exam.decision.slot.endTime.replace('T', ' ')} · {exam.decision.slotStatus}</p>}
        {exam.report && <><p>Referto: {exam.report.status === 'SENT' ? 'Invio confermato' : 'Salvato, invio da verificare'}.</p>
          <p>Salvato: {dateText(exam.report.reportedAt)}.
            {exam.report.deliveredAt && ` Conferma invio: ${dateText(exam.report.deliveredAt)}.`}</p>
          <p className="report-text">{exam.report.text}</p></>}
      </div>)}
      {!!data.historicalSlots.length && <><h4>Prenotazioni precedenti senza collegamento al task</h4>
        <p>Impossibile associare con certezza queste prenotazioni a uno specifico referto.</p>
        {data.historicalSlots.map(item => <p key={item.id} className="notice">
          Prenotazione {item.id} · {item.role} · {item.slot.examDisplayName} · {item.slot.resourceCode} ·
          {item.slot.startTime.replace('T', ' ')} — {item.slot.endTime.replace('T', ' ')} · {item.status}
        </p>)}</>}
      <h3>Eventi del percorso</h3>
      <p>Storico tecnico Camunda, non una certificazione clinica. I dati riflettono l'ultima lettura degli indici.</p>
      <ol className="pathway-history">{data.elements.map(element => <li key={element.key}>
        <strong>{element.name || element.elementId}</strong> · {stateText(element.state)} · {dateText(element.startDate)}
        {element.endDate && ` · Fine: ${dateText(element.endDate)}`}
        {element.hasIncident && ' · Incidente'}
      </li>)}</ol>
    </>}
  </section>;
}
