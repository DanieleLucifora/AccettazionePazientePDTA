import { useQuery } from '@tanstack/react-query';
import { findTasks, type SpecialistRole } from './api';

export function SpecialistTasks({ role }: { role: SpecialistRole }) {
  const query = useQuery({
    queryKey: ['tasks', role],
    queryFn: ({ signal }) => findTasks(role, signal),
    refetchInterval: 5000,
    retry: false,
    gcTime: 0,
  });

  return (
    <section className="card" aria-labelledby="tasks-title">
      <div className="section-heading split">
        <div><h2 id="tasks-title">Attivita' di valutazione</h2>
          <p>Aggiornamento automatico ogni 5 secondi. In questo incremento la vista e' di sola consultazione.</p></div>
        <button className="secondary" disabled={query.isFetching} onClick={() => void query.refetch()}>
          {query.isFetching ? 'Aggiornamento...' : 'Aggiorna'}
        </button>
      </div>
      {query.isPending && <p role="status">Caricamento delle attivita'...</p>}
      {query.isError && <div className="notice error" role="alert">
        {query.error.message} Eventuali dati precedenti non sono aggiornati.
      </div>}
      {query.data && query.data.length === 0 && !query.isError && <div className="empty-state">
        <span className="empty-symbol" aria-hidden="true">✓</span>
        <h3>Nessuna attivita' in attesa</h3>
        <p>Le nuove valutazioni del tuo gruppo compariranno qui.</p>
      </div>}
      {query.data && query.data.length > 0 && <div className="table-scroll">
        <table>
          <caption className="sr-only">Task del gruppo {role}</caption>
          <thead><tr><th>Paziente</th><th>Diagnosi</th><th>Attivita'</th><th>Assegnazione</th><th>Percorso</th></tr></thead>
          <tbody>{query.data.map(task => <tr key={task.taskKey}>
            <td><strong>{task.patientID || 'ID non disponibile'}</strong><span className="cell-detail">{task.patientData || 'Dati non disponibili'}</span></td>
            <td>{task.diagnosiCode || 'Non disponibile'}</td>
            <td><span className="badge">{task.name}</span></td>
            <td>{task.assignee || 'Da assegnare'}</td>
            <td className="mono">{task.processInstanceKey}</td>
          </tr>)}</tbody>
        </table>
      </div>}
    </section>
  );
}
