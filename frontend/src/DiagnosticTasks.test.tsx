import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { DiagnosticTasks } from './DiagnosticTasks';
import type { DiagnosticTask } from './api';

afterEach(() => vi.unstubAllGlobals());

const task: DiagnosticTask = { taskKey: '42', processInstanceKey: '100', assignee: 'LAB002',
  patientID: 'DEMO-LAB', patientData: 'Paziente fittizio', diagnosiCode: '6401',
  contextError: null, proposal: null, proposalMessage: null, decision: null,
  request: { requesterRole: 'CHIRURGHI', requesterId: 'CH001', requesterName: 'Marco Bianchi', examType: 'TAC',
    bodyPart: 'TORACE', schedulingPreference: 'AUTO_FIRST_AVAILABLE', note: '', proposedStart: '', proposedResourceId: '' } };

function setup(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><DiagnosticTasks /></QueryClientProvider>);
  return () => { view.unmount(); client.clear(); };
}

it('shows foreign assignments read-only and never requests a booking', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    Promise.resolve(Response.json(url.endsWith('/42') ? task : [task])));
  const close = setup(fetchMock);
  await userEvent.click(await screen.findByRole('button', { name: /Apri richiesta/ }));
  expect(await screen.findByText("Attivita' assegnata a un altro operatore: LAB002.")).toBeVisible();
  expect(screen.queryByLabelText('Decisione prenotazione')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Prendi in carico richiesta' })).not.toBeInTheDocument();
  expect(fetchMock.mock.calls.every(([, options]) => !options.method)).toBe(true);
  close();
});

it('displays retrieval failures rather than an empty laboratory queue', async () => {
  const close = setup(vi.fn().mockResolvedValue(Response.json({ detail: 'Camunda non raggiungibile' }, { status: 502 })));
  expect(await screen.findByRole('alert')).toHaveTextContent('Camunda non raggiungibile');
  expect(screen.queryByText('Nessuna richiesta diagnostica in attesa.')).not.toBeInTheDocument();
  close();
});

it('blocks repeat rejection after an uncertain command and recovers the saved refusal', async () => {
  let saved = false;
  let sends = 0;
  const own = { ...task, assignee: 'LAB001' };
  const fetchMock = vi.fn().mockImplementation((url: string, options: RequestInit) => {
    if (options.method === 'POST') {
      sends++;
      if (!saved) { saved = true; return Promise.resolve(Response.json({ detail: 'Timeout Camunda' }, { status: 502 })); }
      return Promise.resolve(Response.json({ status: 'COMPLETED', decision: null }));
    }
    const detail = { ...own, decision: saved ? { action: 'REJECT', reason: 'Esame non eseguibile', slot: null,
      slotId: null, slotStatus: null, decidedAt: '2026-10-27T18:00:00' } : null };
    return Promise.resolve(Response.json(url.endsWith('/42') ? detail : [detail]));
  });
  const close = setup(fetchMock);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: /Apri richiesta/ }));
  await user.selectOptions(await screen.findByLabelText('Decisione prenotazione'), 'REJECT');
  await user.type(screen.getByLabelText('Motivazione obbligatoria'), 'Esame non eseguibile');
  await user.click(screen.getByRole('button', { name: 'Rivedi decisione diagnostica' }));
  await user.dblClick(screen.getByRole('button', { name: 'Conferma decisione diagnostica' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Timeout Camunda');
  expect(screen.getByRole('button', { name: 'Conferma decisione diagnostica' })).toBeDisabled();
  expect(sends).toBe(1);
  await user.click(screen.getByRole('button', { name: 'Verifica stato prenotazione' }));
  await user.dblClick(await screen.findByRole('button', { name: 'Restituisci rifiuto salvato' }));
  expect(await screen.findByRole('heading', { name: 'Rifiuto restituito allo specialista' })).toBeVisible();
  expect(sends).toBe(2);
  close();
});
