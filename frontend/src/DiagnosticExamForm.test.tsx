import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { SpecialistTaskActions } from './SpecialistTaskActions';
import type { ExamSlot, TaskDetail } from './api';

afterEach(() => vi.unstubAllGlobals());

const task: TaskDetail = {
  taskKey: '42', processInstanceKey: '100', name: 'Valutazione Chirurgo', state: 'CREATED',
  role: 'CHIRURGHI', currentOperatorId: 'CH001', assignee: 'CH001', patientID: 'DEMO-EXAM',
  patientData: 'Paziente fittizio', diagnosiCode: '6401', clinicalFileUrl: null, examResult: '', note: '',
};
const options = { combinations: [
  { examType: 'TAC', bodyPart: 'TORACE' }, { examType: 'TAC', bodyPart: 'ADDOME' },
  { examType: 'RMN', bodyPart: 'TESTA' },
], fromDate: '2026-10-28', toDate: '2026-11-06' };
const slot: ExamSlot = { resourceId: 7, resourceCode: 'TAC-01', resourceName: 'TAC Sala 1',
  protocolId: 9, examDisplayName: 'TAC Torace', totalSlotMinutes: 35, requiresContrast: true,
  requiresFasting: true, notes: 'Preparazione dimostrativa', startTime: '2026-10-28T09:00:00',
  endTime: '2026-10-28T09:35:00' };

function setup(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock);
  const completed = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}>
    <SpecialistTaskActions task={task} role="CHIRURGHI" refresh={vi.fn().mockResolvedValue(undefined)} onCompleted={completed} />
  </QueryClientProvider>);
  return { completed, close: () => { view.unmount(); client.clear(); } };
}

async function selectExam() {
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText('Esito'), 'EXAM');
  await screen.findByRole('option', { name: /^TAC$/ });
  await user.selectOptions(screen.getByLabelText('Tipo esame'), 'TAC');
  await user.selectOptions(screen.getByLabelText('Parte corporea'), 'TORACE');
  return user;
}

it('reviews automatic request before sending once and retains notes when editing', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(Response.json(
    url.includes('/options') ? options : { status: 'COMPLETED', taskKey: '42', assignee: 'CH001' })));
  const view = setup(fetchMock);
  const user = await selectExam();
  await user.type(screen.getByLabelText('Note per il laboratorio (facoltative)'), 'Approfondimento');
  await user.click(screen.getByRole('button', { name: 'Rivedi richiesta esame' }));
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(screen.getByText('Primo slot libero (automatico)', { selector: 'dd' })).toBeVisible();
  await user.click(screen.getByRole('button', { name: 'Modifica esito' }));
  expect(screen.getByLabelText('Note per il laboratorio (facoltative)')).toHaveValue('Approfondimento');
  await user.click(screen.getByRole('button', { name: 'Rivedi richiesta esame' }));
  await user.dblClick(screen.getByRole('button', { name: 'Conferma e richiedi esame' }));
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
    examType: 'TAC', bodyPart: 'TORACE', schedulingPreference: 'AUTO_FIRST_AVAILABLE',
    resourceId: null, startTime: null, note: 'Approfondimento',
  });
  expect(view.completed).toHaveBeenCalledTimes(1);
  view.close();
});

it('manual request requires a slot, shows preparation and clears selection when day or exam changes', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    Promise.resolve(Response.json(url.includes('/options') ? options : [slot])));
  const view = setup(fetchMock);
  const user = await selectExam();
  await user.selectOptions(screen.getByLabelText("Modalita' di schedulazione"), 'MANUAL_CALENDAR');
  const select = await screen.findByLabelText('Fascia oraria');
  expect(screen.getByRole('button', { name: 'Rivedi richiesta esame' })).toBeDisabled();
  await user.selectOptions(select, '7|2026-10-28T09:00:00');
  expect(screen.getByText('Protocollo con digiuno.')).toBeInTheDocument();
  expect(screen.getByText('Protocollo con mezzo di contrasto.')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Rivedi richiesta esame' }));
  expect(screen.getByText(/2026-10-28 09:00/)).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Modifica esito' }));
  await user.selectOptions(screen.getByLabelText('Parte corporea'), 'ADDOME');
  await waitFor(() => expect(screen.getByRole('button', { name: 'Rivedi richiesta esame' })).toBeDisabled());
  await user.selectOptions(screen.getByLabelText('Tipo esame'), 'RMN');
  expect(screen.getByLabelText('Parte corporea')).toHaveValue('');
  expect(screen.queryByRole('option', { name: 'TORACE' })).not.toBeInTheDocument();
  view.close();
});

it('does not turn unavailable calendar or catalog failures into a valid request', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(
    url.includes('/options') ? Response.json(options) : Response.json({ detail: 'Calendario non disponibile' }, { status: 502 })));
  const view = setup(fetchMock);
  const user = await selectExam();
  await user.selectOptions(screen.getByLabelText("Modalita' di schedulazione"), 'MANUAL_CALENDAR');
  expect(await screen.findByRole('alert')).toHaveTextContent('Calendario non disponibile');
  expect(screen.getByRole('button', { name: 'Rivedi richiesta esame' })).toBeDisabled();
  expect(screen.queryByLabelText('Fascia oraria')).not.toBeInTheDocument();
  view.close();
});

it('blocks resend after a server-side slot conflict and requires verification', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(
    url.includes('/options') ? Response.json(options) : url.includes('/slots') ? Response.json([slot])
      : Response.json({ detail: 'Fascia non disponibile' }, { status: 409 })));
  const view = setup(fetchMock);
  const user = await selectExam();
  await user.selectOptions(screen.getByLabelText("Modalita' di schedulazione"), 'MANUAL_CALENDAR');
  await user.selectOptions(await screen.findByLabelText('Fascia oraria'), '7|2026-10-28T09:00:00');
  await user.click(screen.getByRole('button', { name: 'Rivedi richiesta esame' }));
  await user.click(screen.getByRole('button', { name: 'Conferma e richiedi esame' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Fascia non disponibile');
  expect(screen.getByRole('button', { name: 'Conferma e richiedi esame' })).toBeDisabled();
  expect(view.completed).not.toHaveBeenCalled();
  view.close();
});

it('shows catalog errors explicitly and can reload without sending a command', async () => {
  let failed = true;
  const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(failed
    ? Response.json({ detail: 'Catalogo non disponibile' }, { status: 502 }) : Response.json(options)));
  const view = setup(fetchMock);
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText('Esito'), 'EXAM');
  expect(await screen.findByRole('alert')).toHaveTextContent('Catalogo non disponibile');
  expect(screen.getByRole('button', { name: 'Rivedi richiesta esame' })).toBeDisabled();
  failed = false;
  await user.click(screen.getByRole('button', { name: 'Ricarica esami' }));
  await user.selectOptions(screen.getByLabelText('Tipo esame'), 'TAC');
  await user.selectOptions(screen.getByLabelText('Parte corporea'), 'TORACE');
  expect(screen.getByRole('button', { name: 'Rivedi richiesta esame' })).toBeEnabled();
  expect(view.completed).not.toHaveBeenCalled();
  view.close();
});

it('shows no availability as an empty calendar without allowing manual review', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) =>
    Promise.resolve(Response.json(url.includes('/options') ? options : [])));
  const view = setup(fetchMock);
  const user = await selectExam();
  await user.selectOptions(screen.getByLabelText("Modalita' di schedulazione"), 'MANUAL_CALENDAR');
  expect(await screen.findByText(/Nessuna fascia disponibile/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Rivedi richiesta esame' })).toBeDisabled();
  view.close();
});
