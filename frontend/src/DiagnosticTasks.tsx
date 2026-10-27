import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ApiError, getDiagnosticTasks, getDiagnosticTask, claimDiagnosticTask, bookDiagnosticTask,
  getExamOptions, getExamSlots, type DiagnosticTask, type DiagnosticBookingCommand, type ExamSlot,
} from './api';

const slotText = (slot: ExamSlot) =>
  `${slot.startTime.replace('T', ' ')} - ${slot.endTime.slice(11, 16)} | ${slot.resourceCode}`;

export function DiagnosticTasks() {
  const [selected, setSelected] = useState<string | null>(null);
  const query = useQuery({ queryKey: ['diagnostic-tasks'], queryFn: ({ signal }) => getDiagnosticTasks(signal),
    refetchInterval: 5000, retry: false, gcTime: 0 });
  if (selected) return <DiagnosticDetail key={selected} taskKey={selected} onBack={() => {
    setSelected(null); void query.refetch();
  }} />;
  return <section className="card">
    <div className="section-heading split"><div><h2>Richieste diagnostiche</h2>
      <p>Aggiornamento ogni 5 secondi. Prenotazione e rifiuto delle richieste; refertazione nella CLI.</p></div>
      <button className="secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>Aggiorna richieste</button>
    </div>
    {query.isPending && <p role="status">Caricamento richieste...</p>}
    {query.isError && <p className="notice error" role="alert">{query.error.message}</p>}
    {!query.isError && query.data?.length === 0 && <p role="status">Nessuna richiesta diagnostica in attesa.</p>}
    {!query.isError && query.data && query.data.length > 0 && <div className="table-scroll"><table>
      <caption className="sr-only">Richieste del laboratorio</caption>
      <thead><tr><th>Paziente</th><th>Richiedente</th><th>Esame</th><th>Stato</th><th>Dettaglio</th></tr></thead>
      <tbody>{query.data.map(task => <tr key={task.taskKey}>
        <td><strong>{task.patientID}</strong><span className="cell-detail">{task.patientData}</span></td>
        <td>{task.request?.requesterName || 'Non identificabile'}<span className="cell-detail">{task.request?.requesterRole}</span></td>
        <td>{task.request ? `${task.request.examType} / ${task.request.bodyPart}` : task.contextError}</td>
        <td>{task.decision ? task.decision.action === 'REJECT' ? 'Rifiuto da restituire'
          : task.decision.slotStatus === 'BOOKED' ? 'Prenotato, attesa referto' : `Prenotazione ${task.decision.slotStatus}: verificare`
          : task.assignee ? `Assegnata a ${task.assignee}` : 'Da assegnare'}</td>
        <td><button className="secondary" aria-label={`Apri richiesta ${task.taskKey} per paziente ${task.patientID}`}
          onClick={() => setSelected(task.taskKey)}>Apri richiesta</button></td>
      </tr>)}</tbody>
    </table></div>}
  </section>;
}

function DiagnosticDetail({ taskKey, onBack }: { taskKey: string; onBack: () => void }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const [closed, setClosed] = useState(false);
  const query = useQuery({ queryKey: ['diagnostic-detail', taskKey],
    queryFn: ({ signal }) => getDiagnosticTask(taskKey, signal), retry: false, gcTime: 0, enabled: !closed,
    refetchInterval: query => query.state.error instanceof ApiError
      && [404, 409].includes(query.state.error.status) ? false : 5000 });
  useEffect(() => { heading.current?.focus(); }, []);
  return <section className="card">
    <div className="section-heading split"><div><h2 ref={heading} tabIndex={-1}>Dettaglio richiesta diagnostica</h2>
      <p>Operatore dimostrativo LAB001 · Mario Neri</p></div>
      <button className="secondary" onClick={onBack}>Torna alle richieste</button></div>
    {closed ? <div role="status" className="notice success"><h3>Rifiuto restituito allo specialista</h3>
      <p>Camunda ha confermato il completamento del task diagnostico.</p></div>
      : query.isError ? <div className="notice error" role="alert"><p>{query.error.message}</p>
        <button disabled={query.isFetching} onClick={() => void query.refetch()}>Verifica richiesta</button></div>
      : query.data ? <DiagnosticDetailContent task={query.data} refresh={async () => {
        const result = await query.refetch();
        if (result.error) throw result.error;
      }} onClosed={() => setClosed(true)} /> : <p role="status">Caricamento dettaglio...</p>}
  </section>;
}

function DiagnosticDetailContent({ task, refresh, onClosed }: {
  task: DiagnosticTask; refresh: () => Promise<void>; onClosed: () => void;
}) {
  const [action, setAction] = useState<DiagnosticBookingCommand['action']>('CONFIRM');
  const [reason, setReason] = useState('');
  const [day, setDay] = useState('');
  const [selected, setSelected] = useState<ExamSlot | null>(null);
  const [review, setReview] = useState<{ command: DiagnosticBookingCommand; slot: ExamSlot | null } | null>(null);
  const [error, setError] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const [busy, setBusy] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const lock = useRef(false);
  const completedCommand = useRef(false);
  const mine = task.assignee === 'LAB001';
  const other = Boolean(task.assignee) && !mine;
  const context = task.request;
  const options = useQuery({ queryKey: ['exam-options'], queryFn: ({ signal }) => getExamOptions(signal),
    retry: false, enabled: mine && action === 'MODIFY' && !task.decision });
  const selectedDay = day || options.data?.fromDate || '';
  const slots = useQuery({ queryKey: ['diagnostic-slots', task.taskKey, context?.examType, context?.bodyPart, selectedDay],
    queryFn: ({ signal }) => {
      if (!context) throw new Error('Richiesta non identificabile.');
      return getExamSlots(context, selectedDay, signal);
    }, enabled: mine && action === 'MODIFY' && !!context && !!selectedDay && !options.isError && !task.decision,
    retry: false, gcTime: 0 });
  const availableSelection = selected && !slots.isError && !slots.isFetching
    && slots.data?.some(slot => slot.resourceId === selected.resourceId && slot.startTime === selected.startTime);
  const chosen = action === 'CONFIRM' ? task.proposal : action === 'MODIFY' && availableSelection ? selected : null;
  const valid = action === 'REJECT' ? !!reason.trim() : !!chosen && (action !== 'MODIFY' || !!reason.trim());
  const blocked = busy || uncertain || acknowledged;

  async function mutate(command?: DiagnosticBookingCommand) {
    if (lock.current || completedCommand.current) return;
    lock.current = true; setBusy(true); setError('');
    try {
      if (!command) await claimDiagnosticTask(task.taskKey);
      else {
        const result = await bookDiagnosticTask(task.taskKey, command);
        completedCommand.current = true;
        setAcknowledged(true);
        if (result.status === 'COMPLETED') { onClosed(); return; }
        setReview(null);
      }
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Operazione non confermata.');
      setUncertain(true);
    } finally { lock.current = false; setBusy(false); }
  }

  async function verify() {
    setBusy(true);
    try { await refresh(); setError(''); setUncertain(false); setReview(null); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Stato non verificabile.'); }
    finally { setBusy(false); }
  }

  return <>
    <dl className="review"><dt>ID paziente</dt><dd>{task.patientID}</dd>
      <dt>Dati paziente</dt><dd>{task.patientData}</dd><dt>Diagnosi</dt><dd>{task.diagnosiCode}</dd>
      <dt>Task</dt><dd className="mono">{task.taskKey}</dd><dt>Percorso</dt><dd className="mono">{task.processInstanceKey}</dd>
      <dt>Assegnazione</dt><dd>{task.assignee || 'Da assegnare'}</dd>
      {context && <><dt>Richiedente</dt><dd>{context.requesterName} ({context.requesterId || 'ID non disponibile'}) · {context.requesterRole}</dd>
        <dt>Esame</dt><dd>{context.examType} / {context.bodyPart.replaceAll('_', ' ')}</dd>
        <dt>Note specialista</dt><dd>{context.note || 'Nessuna nota'}</dd>
        <dt>Modalita'</dt><dd>{context.schedulingPreference === 'MANUAL_CALENDAR' ? 'Calendario manuale' : 'Primo slot libero'}</dd>
        {context.proposedStart && <><dt>Fascia richiesta</dt><dd>{context.proposedStart.replace('T', ' ')}</dd></>}
      </>}
    </dl>
    {task.contextError && <p role="alert" className="notice error">{task.contextError}</p>}
    {error && <div role="alert" className="notice error"><p>{error}</p>
      <p>Non ripetere l'operazione senza verificare lo stato.</p>
      <button disabled={busy} onClick={() => void verify()}>Verifica stato prenotazione</button></div>}
    {task.decision ? <div className="notice success" role="status">
      <h3>{task.decision.action === 'REJECT' ? 'Rifiuto salvato' : 'Prenotazione salvata'}</h3>
      {task.decision.slot && <p>{slotText(task.decision.slot)} · ID {task.decision.slotId} · {task.decision.slotStatus}</p>}
      {task.decision.reason && <p>Motivazione: {task.decision.reason}</p>}
      {task.decision.action === 'REJECT'
        ? <><p>Verifica il completamento Camunda. Se il task e' ancora aperto, puoi restituire il rifiuto salvato senza modificarlo.</p>
          {mine && !task.contextError && <button disabled={blocked} onClick={() => void mutate({
            action: 'REJECT', reason: task.decision!.reason, resourceId: null, startTime: null,
          })}>Restituisci rifiuto salvato</button>}</>
        : <p>Il task resta aperto per il referto, disponibile nella CLI. Non viene creata un'altra prenotazione.</p>}
    </div> : context && !task.contextError && <>
      {other ? <p className="notice">Attivita' assegnata a un altro operatore: {task.assignee}.</p>
        : !mine ? <button disabled={blocked} onClick={() => void mutate()}>Prendi in carico richiesta</button>
        : <>
          {acknowledged && <p className="notice" role="status">Decisione confermata. Aggiorna il dettaglio per verificarla.</p>}
          {task.proposal && <div className="notice"><h3>Proposta prenotabile</h3><p>{slotText(task.proposal)}</p>
            <p>{task.proposal.examDisplayName} · {task.proposal.totalSlotMinutes} minuti</p>
            {task.proposal.requiresContrast && <p>Protocollo con mezzo di contrasto.</p>}
            {task.proposal.requiresFasting && <p>Protocollo con digiuno.</p>}
            {task.proposal.notes && <p>{task.proposal.notes}</p>}
          </div>}
          {task.proposalMessage && <p role="status" className="notice">{task.proposalMessage}</p>}
          {review ? <div className="notice">
            <h3>Conferma decisione diagnostica</h3>
            <p>Paziente: {task.patientID} · {context.requesterRole} · {context.examType} / {context.bodyPart}</p>
            <p>{review.command.action === 'REJECT' ? 'Rifiuta richiesta' : review.command.action === 'MODIFY' ? 'Prenota fascia alternativa' : 'Conferma proposta'}</p>
            {review.slot && <p>{slotText(review.slot)}</p>}
            <p>Motivazione: {review.command.reason || 'Nessuna'}</p>
            <p>{review.command.action === 'REJECT' ? "Il rifiuto sara' restituito allo specialista."
              : "Confermando occupi realmente la risorsa. Il task restera' aperto per il referto."}</p>
            <div className="form-footer"><button className="secondary" disabled={blocked} onClick={() => setReview(null)}>Modifica decisione</button>
              <button disabled={blocked} onClick={() => void mutate(review.command)}>Conferma decisione diagnostica</button></div>
          </div> : <form onSubmit={event => {
            event.preventDefault();
            if (valid) setReview({ command: { action, reason: reason.trim(),
              resourceId: chosen?.resourceId ?? null, startTime: chosen?.startTime ?? null }, slot: chosen });
          }}>
            <fieldset disabled={blocked}>
              <label className="decision-label">Decisione prenotazione
                <select value={action} onChange={event => {
                  const value = event.target.value;
                  if (value === 'CONFIRM' || value === 'MODIFY' || value === 'REJECT') { setAction(value); setSelected(null); }
                }}>
                  <option value="CONFIRM">Conferma proposta</option><option value="MODIFY">Modifica data o fascia</option>
                  <option value="REJECT">Rifiuta richiesta</option>
                </select>
              </label>
              {action === 'MODIFY' && <>
                {options.isError && <p className="notice error" role="alert">{options.error.message}</p>}
                <label className="decision-label">Giorno prenotazione
                  <input type="date" required min={options.data?.fromDate} max={options.data?.toDate} value={selectedDay}
                    onChange={event => { setDay(event.target.value); setSelected(null); }} />
                </label>
                {slots.isFetching && <p role="status">Verifica disponibilita'...</p>}
                {slots.isError && <p role="alert" className="notice error">{slots.error.message}</p>}
                {slots.isSuccess && !slots.isFetching && <>
                  {slots.data.length === 0 && <p role="status">Nessuna fascia disponibile in questo giorno.</p>}
                  <label className="decision-label">Fascia alternativa
                    <select required value={availableSelection ? `${selected.resourceId}|${selected.startTime}` : ''}
                      onChange={event => setSelected(slots.data.find(slot =>
                        `${slot.resourceId}|${slot.startTime}` === event.target.value) ?? null)}>
                      <option value="">Seleziona fascia</option>
                      {slots.data.map(slot => <option key={`${slot.resourceId}|${slot.startTime}`}
                        value={`${slot.resourceId}|${slot.startTime}`}>{slotText(slot)}</option>)}
                    </select>
                  </label>
                </>}
                <button type="button" className="secondary" disabled={slots.isFetching || !selectedDay}
                  onClick={() => { setSelected(null); void slots.refetch(); }}>Aggiorna fasce</button>
              </>}
              {action !== 'CONFIRM' && <label className="decision-label">Motivazione obbligatoria
                <textarea required maxLength={2000} rows={3} value={reason} onChange={event => setReason(event.target.value)} />
              </label>}
              <button type="submit" disabled={!valid}>Rivedi decisione diagnostica</button>
            </fieldset>
          </form>}
        </>}
    </>}
  </>;
}
