import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, getTask, type SpecialistRole } from './api';
import { SpecialistTaskActions } from './SpecialistTaskActions';

export function SpecialistTaskDetail({ taskKey, role, onBack }: {
  taskKey: string;
  role: SpecialistRole;
  onBack: () => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const [completed, setCompleted] = useState(false);
  const query = useQuery({
    queryKey: ['task-detail', role, taskKey],
    queryFn: ({ signal }) => getTask(taskKey, role, signal),
    refetchInterval: query => query.state.error instanceof ApiError
      && [404, 409].includes(query.state.error.status) ? false : 5000,
    retry: false,
    gcTime: 0,
    enabled: !completed,
  });
  useEffect(() => { heading.current?.focus(); }, []);
  const task = query.data;
  return (
    <section className="card" aria-labelledby="task-detail-title">
      <div className="section-heading split">
        <div><h2 id="task-detail-title" tabIndex={-1} ref={heading}>Dettaglio attivita'</h2>
          <p>Consultazione del task e dei dati pertinenti al tuo gruppo.</p></div>
        <button className="secondary" onClick={onBack}>Torna alle attivita'</button>
      </div>
      {completed && <div className="notice success" role="status">
        <h3>Valutazione inviata</h3>
        <p>Camunda ha confermato il completamento del task. Non equivale necessariamente alla chiusura dell'intero percorso.</p>
      </div>}
      {!completed && query.isPending && <p role="status">Caricamento del dettaglio...</p>}
      {!completed && query.isError && <div className="notice error" role="alert">
        <p>{query.error.message}</p>
        <p>I dati non sono mostrati finche' non e' possibile verificarli.</p>
        <button className="secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>
          Riprova
        </button>
      </div>}
      {task && !query.isError && !completed && <>
        <div className="task-detail-header"><h3>{task.name}</h3><span className="badge">Aperta</span></div>
        <dl className="review">
          <dt>Gruppo</dt><dd>{task.role === 'CHIRURGHI' ? 'Chirurghi' : 'Oncologi'}</dd>
          <dt>Assegnazione</dt><dd>{task.assignee || 'Da assegnare'}</dd>
          <dt>ID paziente</dt><dd>{task.patientID || 'Non disponibile'}</dd>
          <dt>Dati del paziente</dt><dd>{task.patientData || 'Non disponibili'}</dd>
          <dt>Codice diagnosi</dt><dd>{task.diagnosiCode || 'Non disponibile'}</dd>
          <dt>Cartella clinica</dt><dd>{task.clinicalFileUrl
            ? <a href={task.clinicalFileUrl} target="_blank" rel="noopener noreferrer">Apri cartella clinica (nuova scheda)</a>
            : 'Non disponibile o indirizzo non utilizzabile'}</dd>
          <dt>Percorso</dt><dd className="mono">{task.processInstanceKey}</dd>
          <dt>Task</dt><dd className="mono">{task.taskKey}</dd>
        </dl>
        <section className="detail-section" aria-labelledby="exam-result-title">
          <h3 id="exam-result-title">Referto per {role === 'CHIRURGHI' ? 'chirurgo' : 'oncologo'}</h3>
          <p className="preserve-lines">{task.examResult || 'Nessun referto disponibile.'}</p>
        </section>
        <section className="detail-section" aria-labelledby="specialist-note-title">
          <h3 id="specialist-note-title">Note della valutazione specialistica</h3>
          <p className="preserve-lines">{task.note || 'Nessuna nota disponibile.'}</p>
        </section>
        <SpecialistTaskActions key={taskKey} task={task} role={role} refresh={async () => {
          const result = await query.refetch();
          if (result.error) throw result.error;
        }} onCompleted={() => setCompleted(true)} />
      </>}
    </section>
  );
}
