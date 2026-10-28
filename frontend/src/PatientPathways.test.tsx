import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { PatientPathways } from './PatientPathways';
import type { PathwayDetail, PathwaySummary } from './api';

afterEach(() => vi.unstubAllGlobals());
const summary: PathwaySummary = { key: '2251799816330408', state: 'ACTIVE', hasIncident: false, version: 6,
  startDate: '2026-01-01T10:00:00Z', endDate: '', patientID: '0001', patientData: 'Paziente precedente',
  firstName: '', lastName: '', birthDate: '', diagnosiCode: '6401' };
const detail: PathwayDetail = { pathway: summary, outcome: 'BRANCH_REJECTED', tasks: [{ key: '84',
  name: 'Valutazione Oncologo', elementId: 'Activity_0vww6jw', state: 'CREATED', assignee: 'ON001', groups: ['ONCOLOGI'],
  createdAt: '2026-01-01T10:00:00Z', completedAt: '' }], elements: [], exams: [], historicalSlots: [],
  surgeon: { role: 'CHIRURGHI', approved: false, examRequired: false, note: 'Non incluso nel ramo', examResult: 'Esame rifiutato' },
  oncologist: { role: 'ONCOLOGI', approved: null, examRequired: null, note: '', examResult: '' } };
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><PatientPathways /></QueryClientProvider>);
}

it('searches exact IDs and pages without carrying a previous-page offset into a new filter', async () => {
  const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(Response.json({
    items: [summary], offset: 0, total: 25, hasNext: true,
  })));
  vi.stubGlobal('fetch', fetchMock);
  mount();
  const user = userEvent.setup();
  await screen.findByText('Paziente precedente');
  await user.click(screen.getByRole('button', { name: 'Pagina successiva' }));
  expect(fetchMock).toHaveBeenLastCalledWith('/api/pathways?patientId=&state=&offset=20', expect.anything());
  await user.type(screen.getByLabelText('Cerca ID paziente'), '0001');
  await user.selectOptions(screen.getByLabelText('Stato percorso'), 'ACTIVE');
  await user.click(screen.getByRole('button', { name: 'Cerca percorsi' }));
  expect(fetchMock).toHaveBeenLastCalledWith('/api/pathways?patientId=0001&state=ACTIVE&offset=0', expect.anything());
});

it('shows old demographics honestly and separates a rejected branch from an active process', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(Response.json(
    url.includes('/pathways?') ? { items: [summary], offset: 0, total: 1, hasNext: false } : detail))));
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: /Apri percorso/ }));
  expect(await screen.findByText('Non disponibile: percorso con anagrafica precedente')).toBeInTheDocument();
  expect(screen.getByText('In corso')).toBeInTheDocument();
  expect(screen.getByText(/Altri rami possono essere ancora attivi/)).toBeInTheDocument();
  expect(screen.getByText('ON001')).toBeInTheDocument();
  expect(screen.getByText('Ultimo esito diagnostico: Esame rifiutato')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Conferma|Prenota|Invia referto|Approva/ })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Torna ai percorsi' }));
  expect(await screen.findByText('Paziente precedente')).toBeInTheDocument();
});

it('hides stale details when a refresh fails rather than displaying a successful empty or old pathway', async () => {
  let failed = false;
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(
    failed ? Response.json({ detail: 'Lettura Camunda fallita' }, { status: 502 })
      : Response.json(url.includes('/pathways?') ? { items: [summary], offset: 0, total: 1, hasNext: false } : detail))));
  mount();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: /Apri percorso/ }));
  await screen.findByText('ON001');
  failed = true;
  await user.click(screen.getByRole('button', { name: 'Aggiorna dettaglio percorso' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Lettura Camunda fallita');
  expect(screen.queryByText('ON001')).not.toBeInTheDocument();
});

it('keeps unsent reports distinct from legacy outcomes and shows historical slots separately', async () => {
  const slot = { resourceId: 1, resourceCode: 'TAC01', resourceName: 'TAC', protocolId: 1, examDisplayName: 'TAC TORACE',
    totalSlotMinutes: 45, requiresContrast: false, requiresFasting: false, notes: '', startTime: '2026-01-01T10:00:00',
    endTime: '2026-01-01T10:45:00' };
  const withReport: PathwayDetail = { ...detail, exams: [{ taskKey: '81', requesterRole: 'CHIRURGHI',
    decision: { action: 'CONFIRM', reason: '', slotId: '1', slot, slotStatus: 'COMPLETED', decidedAt: '2026-01-01T09:00:00' },
    report: { taskKey: '81', text: 'Referto persistente', reportedAt: '2026-01-01T11:00:00', deliveredAt: null, status: 'PENDING' } }],
    historicalSlots: [{ id: '2', role: 'ONCOLOGI', slot, status: 'COMPLETED' }] };
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(Response.json(
    url.includes('/pathways?') ? { items: [summary], offset: 0, total: 1, hasNext: false } : withReport))));
  mount();
  await userEvent.click(await screen.findByRole('button', { name: /Apri percorso/ }));
  expect(await screen.findByText('Referto persistente')).toBeInTheDocument();
  expect(screen.getByText(/Salvato, invio da verificare/)).toBeInTheDocument();
  const heading = screen.getByRole('heading', { name: 'Prenotazioni precedenti senza collegamento al task' });
  expect(heading).toBeInTheDocument();
  expect(within(heading.parentElement!).getByText(/Prenotazione 2/)).toBeInTheDocument();
});
