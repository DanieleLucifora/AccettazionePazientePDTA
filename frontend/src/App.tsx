import { useState } from 'react';
import type { Role } from './api';
import { RegistrationForm } from './RegistrationForm';
import { SpecialistTasks } from './SpecialistTasks';
import { DiagnosticTasks } from './DiagnosticTasks';
import { PatientPathways } from './PatientPathways';

const operators: Record<Role, { label: string; name: string; initials: string }> = {
  CASE_MANAGER: { label: 'Case Manager', name: 'Giulia Verdi', initials: 'GV' },
  CHIRURGHI: { label: 'Chirurgo', name: 'Marco Bianchi', initials: 'MB' },
  ONCOLOGI: { label: 'Oncologo', name: 'Luigi Rossi', initials: 'LR' },
  LABORATORIO: { label: 'Diagnostica', name: 'Mario Neri', initials: 'MN' },
};

export default function App() {
  const [role, setRole] = useState<Role>('CASE_MANAGER');
  const [caseView, setCaseView] = useState<'registration' | 'pathways'>('registration');
  const operator = operators[role];
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/"><span className="brand-mark">+</span><span>PDTA<small>Portale operatori</small></span></a>
        <div className="nav-label">AREA OPERATIVA</div>
        <nav aria-label="Area operativa">
          {role === 'CASE_MANAGER' ? <>
            <button className={`nav-item ${caseView === 'registration' ? 'active' : ''}`} aria-current={caseView === 'registration' ? 'page' : undefined}
              onClick={() => setCaseView('registration')}>Registrazione paziente</button>
            <button className={`nav-item ${caseView === 'pathways' ? 'active' : ''}`} aria-current={caseView === 'pathways' ? 'page' : undefined}
              onClick={() => setCaseView('pathways')}>Percorsi paziente</button>
          </> : <span className="nav-item active" aria-current="page">{role === 'LABORATORIO' ? 'Richieste diagnostiche' : 'Attivita\' specialistiche'}</span>
          }
        </nav>
        <div className="sidebar-note"><strong>Un percorso, piu' competenze</strong>
          <p>Accettazione e valutazione multidisciplinare del paziente.</p></div>
        <div className="version">PRIMO INCREMENTO · 0.1</div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <span className="breadcrumb">Presa in carico <span>/</span> {operator.label}</span>
          <div className="operator"><span className="avatar">{operator.initials}</span>
            <div><strong>{operator.name}</strong><small>{operator.label} simulato</small></div></div>
        </header>
        <main id="main-content">
          <div className="demo-banner"><span className="demo-label">DEMO LOCALE</span>
            <span>Identita' simulate, nessun login reale. Usa solo dati fittizi.</span>
            <label>Vista operatore
              <select value={role} onChange={event => {
                const value = event.target.value;
                if (value === 'CASE_MANAGER' || value === 'CHIRURGHI' || value === 'ONCOLOGI' || value === 'LABORATORIO') { setRole(value); setCaseView('registration'); }
              }}>
                {Object.entries(operators).map(([value, item]) => <option key={value} value={value}>{item.label}</option>)}
              </select>
            </label>
          </div>
          <div className="page-heading"><div className="eyebrow">WORKFLOW CLINICO</div>
            <h1>{role === 'CASE_MANAGER' ? caseView === 'pathways' ? 'Il percorso del paziente, in un solo sguardo.' : 'Ogni percorso inizia dalla presa in carico.'
              : role === 'LABORATORIO' ? 'Organizza gli approfondimenti diagnostici.' : 'Le valutazioni del tuo gruppo.'}</h1>
            <p>{role === 'CASE_MANAGER'
              ? caseView === 'pathways' ? 'Consulta stato, attivita\', esami e referti senza modificare il percorso.'
                : 'Avvia il percorso del paziente. Camunda coordina i passaggi successivi.'
              : role === 'LABORATORIO' ? 'Gestisci richieste, prenotazioni e referti del laboratorio.'
                : 'Consulta i pazienti in attesa della valutazione specialistica.'}</p></div>
          {role === 'CASE_MANAGER' ? caseView === 'registration' ? <RegistrationForm /> : <PatientPathways /> : role === 'LABORATORIO' ? <DiagnosticTasks />
            : <SpecialistTasks key={role} role={role} />}
          <footer className="page-footer">PDTA · Workflow di accettazione paziente
            <span>Diagnostica: prenotazioni e referti nel portale.</span></footer>
        </main>
      </div>
    </div>
  );
}
