import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { SpecialistTaskActions } from './SpecialistTaskActions';
import type { TaskDetail } from './api';

afterEach(() => vi.unstubAllGlobals());

const task: TaskDetail = {
  taskKey: '42', processInstanceKey: '100', name: 'Valutazione Chirurgo', state: 'CREATED',
  role: 'CHIRURGHI', currentOperatorId: 'CH001', assignee: 'CH001',
  patientID: '001', patientData: 'Paziente fittizio', diagnosiCode: '6401',
  clinicalFileUrl: null, examResult: '', note: '',
};

it('requires claim before allowing a decision and never overwrites another operator', async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ taskKey: '42', status: 'CLAIMED', assignee: 'CH001' }));
  vi.stubGlobal('fetch', fetchMock);
  const refresh = vi.fn().mockResolvedValue(undefined);
  const view = render(<SpecialistTaskActions task={{ ...task, assignee: null }} role="CHIRURGHI"
    refresh={refresh} onCompleted={vi.fn()} />);
  expect(screen.queryByRole('button', { name: 'Rivedi esito' })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Prendi in carico' }));
  expect(fetchMock).toHaveBeenCalledWith('/api/tasks/42/claim?role=CHIRURGHI', { method: 'POST' });
  expect(refresh).toHaveBeenCalledTimes(1);
  view.rerender(<SpecialistTaskActions task={{ ...task, assignee: 'OTHER' }} role="CHIRURGHI"
    refresh={refresh} onCompleted={vi.fn()} />);
  expect(screen.getByText(/assegnata a un altro operatore/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Prendi in carico' })).not.toBeInTheDocument();
  view.rerender(<SpecialistTaskActions task={{ ...task, assignee: null }} role="CHIRURGHI"
    refresh={refresh} onCompleted={vi.fn()} />);
  expect(screen.getByRole('button', { name: 'Prendi in carico' })).toBeEnabled();
});

it('requires rejection reason and explicit review then sends a single decision', async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ taskKey: '42', status: 'COMPLETED', assignee: 'CH001' }));
  vi.stubGlobal('fetch', fetchMock);
  const completed = vi.fn();
  render(<SpecialistTaskActions task={task} role="CHIRURGHI" refresh={vi.fn()} onCompleted={completed} />);
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText('Esito'), 'REJECT');
  expect(screen.getByRole('button', { name: 'Rivedi esito' })).toBeDisabled();
  await user.type(screen.getByLabelText(/Motivazione del rifiuto/), 'Non idoneo');
  await user.click(screen.getByRole('button', { name: 'Rivedi esito' }));
  expect(fetchMock).not.toHaveBeenCalled();
  expect(screen.getByText('Rifiuto')).toBeInTheDocument();
  await user.dblClick(screen.getByRole('button', { name: 'Conferma e invia esito' }));
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ outcome: 'REJECT', note: 'Non idoneo' });
  expect(completed).toHaveBeenCalledTimes(1);
});

it('allows editing a review and blocks repeated commands after an uncertain error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ detail: "Task gia' gestito" }, { status: 409 })));
  render(<SpecialistTaskActions task={task} role="CHIRURGHI" refresh={vi.fn().mockResolvedValue(undefined)}
    onCompleted={vi.fn()} />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Rivedi esito' }));
  await user.click(screen.getByRole('button', { name: 'Modifica esito' }));
  await user.type(screen.getByLabelText('Note (facoltative)'), 'Nota');
  await user.click(screen.getByRole('button', { name: 'Rivedi esito' }));
  await user.click(screen.getByRole('button', { name: 'Conferma e invia esito' }));
  expect(await screen.findByRole('alert')).toHaveTextContent("Task gia' gestito");
  expect(screen.getByRole('button', { name: 'Conferma e invia esito' })).toBeDisabled();
  await user.click(screen.getByRole('button', { name: "Verifica stato attivita'" }));
  expect(await screen.findByRole('button', { name: 'Rivedi esito' })).toBeEnabled();
});

it('keeps sending blocked when state verification itself fails', async () => {
  const fetchMock = vi.fn().mockRejectedValue(new Error('Connessione interrotta'));
  vi.stubGlobal('fetch', fetchMock);
  const refresh = vi.fn().mockRejectedValue(new Error('Stato non disponibile'));
  render(<SpecialistTaskActions task={{ ...task, assignee: null }} role="CHIRURGHI"
    refresh={refresh} onCompleted={vi.fn()} />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Prendi in carico' }));
  await user.click(screen.getByRole('button', { name: "Verifica stato attivita'" }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Stato non disponibile');
  expect(screen.getByRole('button', { name: 'Prendi in carico' })).toBeDisabled();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
