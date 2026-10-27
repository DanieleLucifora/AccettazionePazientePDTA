import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { DiagnosticReportForm } from './DiagnosticReportForm';
import type { DiagnosticReport } from './api';

afterEach(() => vi.unstubAllGlobals());
const report: DiagnosticReport = { taskKey: '42', text: 'Referto dimostrativo\nSeconda riga',
  reportedAt: '2026-10-28T10:00:00', deliveredAt: null, status: 'PENDING' };

it('requires nonblank text, reviews and preserves draft before a single confirmed submission', async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ ...report, status: 'SENT' }));
  vi.stubGlobal('fetch', fetchMock);
  const sent = vi.fn();
  render(<DiagnosticReportForm taskKey="42" report={null} allowed readOnly={false} onSent={sent} />);
  const user = userEvent.setup();
  expect(screen.getByRole('button', { name: 'Rivedi referto' })).toBeDisabled();
  await user.type(screen.getByLabelText('Testo del referto (obbligatorio)'), '   ');
  expect(screen.getByRole('button', { name: 'Rivedi referto' })).toBeDisabled();
  await user.clear(screen.getByLabelText('Testo del referto (obbligatorio)'));
  await user.type(screen.getByLabelText('Testo del referto (obbligatorio)'), report.text);
  await user.click(screen.getByRole('button', { name: 'Rivedi referto' }));
  expect(fetchMock).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Modifica referto' }));
  expect(screen.getByLabelText('Testo del referto (obbligatorio)')).toHaveValue(report.text);
  expect(screen.getByLabelText('Testo del referto (obbligatorio)')).toHaveAttribute('maxlength', '10000');
  await user.click(screen.getByRole('button', { name: 'Rivedi referto' }));
  await user.dblClick(screen.getByRole('button', { name: 'Conferma e invia referto' }));
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ text: report.text });
  expect(sent).toHaveBeenCalledTimes(1);
});

it('does not expose reporting before booking end or on another operator task', () => {
  const view = render(<DiagnosticReportForm taskKey="42" report={null} allowed={false} readOnly={false} onSent={vi.fn()} />);
  expect(screen.getByRole('status')).toHaveTextContent('solo dopo la fine della fascia prenotata');
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  view.rerender(<DiagnosticReportForm taskKey="42" report={null} allowed readOnly onSent={vi.fn()} />);
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
});

it('verifies an uncertain result and recovers only the immutable saved text', async () => {
  let sends = 0;
  const fetchMock = vi.fn().mockImplementation((_url: string, options?: RequestInit) => {
    if (options?.method === 'POST') {
      sends++;
      return Promise.resolve(sends === 1 ? Response.json({ detail: 'Esito Camunda incerto' }, { status: 502 })
        : Response.json({ ...report, status: 'SENT' }));
    }
    return Promise.resolve(Response.json({ report }));
  });
  vi.stubGlobal('fetch', fetchMock);
  const sent = vi.fn();
  render(<DiagnosticReportForm taskKey="42" report={null} allowed readOnly={false} onSent={sent} />);
  const user = userEvent.setup();
  await user.type(screen.getByRole('textbox'), report.text);
  await user.click(screen.getByRole('button', { name: 'Rivedi referto' }));
  await user.dblClick(screen.getByRole('button', { name: 'Conferma e invia referto' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Esito Camunda incerto');
  expect(screen.getByRole('button', { name: 'Conferma e invia referto' })).toBeDisabled();
  await user.click(screen.getByRole('button', { name: 'Verifica stato referto' }));
  expect(await screen.findByRole('heading', { name: 'Referto salvato, invio da verificare' })).toBeVisible();
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  await user.dblClick(screen.getByRole('button', { name: 'Riprendi invio referto salvato' }));
  expect(sends).toBe(2);
  expect(sent).toHaveBeenCalledTimes(1);
  expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ text: report.text });
});

it('can verify a delivered report even when the closed task detail is unavailable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ report: { ...report, status: 'SENT' } })));
  const sent = vi.fn();
  render(<DiagnosticReportForm taskKey="42" report={null} allowed={false} readOnly unavailable onSent={sent} />);
  await userEvent.click(screen.getByRole('button', { name: 'Verifica stato referto' }));
  expect(await screen.findByRole('heading', { name: 'Referto inviato allo specialista' })).toBeVisible();
  expect(sent).toHaveBeenCalledTimes(1);
});
