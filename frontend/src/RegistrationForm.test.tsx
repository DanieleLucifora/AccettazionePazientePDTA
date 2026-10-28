import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { RegistrationForm } from './RegistrationForm';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  getRegistrationOptions: async () => ({ diagnosisCodes: ['6401', '1001'], today: '2026-10-28' }),
}));

afterEach(() => vi.unstubAllGlobals());

it('registers once and retries only the access task when indexing is delayed', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json({ processInstanceKey: '42', status: 'WAITING' }, { status: 201 }))
    .mockResolvedValueOnce(Response.json({ processInstanceKey: '42', status: 'WAITING' }))
    .mockResolvedValueOnce(Response.json({ processInstanceKey: '42', status: 'COMPLETED' }));
  vi.stubGlobal('fetch', fetchMock);
  const user = userEvent.setup();
  render(<RegistrationForm />);
  await user.type(screen.getByLabelText(/Codice fiscale/), 'RSSMRA80A01H501U');
  await user.selectOptions(screen.getByLabelText(/Codice diagnosi/), '6401');
  await user.type(screen.getByLabelText(/^Nome/), 'Mario');
  await user.type(screen.getByLabelText(/^Cognome/), 'Rossi');
  await user.type(screen.getByLabelText(/Data di nascita/), '1980-01-02');
  await user.click(screen.getByRole('button', { name: 'Avvia percorso PDTA' }));
  expect(fetchMock).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Conferma e avvia' }));
  expect(await screen.findByText('Processo creato, accesso da completare')).toBeInTheDocument();
  expect(sessionStorage.getItem('pdta.pending-registration')).toBe('42');
  await user.click(screen.getByRole('button', { name: 'Verifica e completa accesso' }));
  expect(await screen.findByText('Raccolta dati completata')).toBeInTheDocument();
  expect(fetchMock.mock.calls.map(call => call[0])).toEqual([
    '/api/registrations', '/api/registrations/42/complete-access', '/api/registrations/42/complete-access',
  ]);
  expect(sessionStorage.getItem('pdta.pending-registration')).toBeNull();
});

it('recovers an unfinished registration after remount without creating another instance', async () => {
  sessionStorage.setItem('pdta.pending-registration', '42');
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ processInstanceKey: '42', status: 'COMPLETED' }));
  vi.stubGlobal('fetch', fetchMock);
  render(<RegistrationForm />);
  await userEvent.click(screen.getByRole('button', { name: 'Verifica e completa accesso' }));
  expect(await screen.findByText('Raccolta dati completata')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('shows upstream failures and blocks automatic repetition of an uncertain creation', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ detail: 'Camunda non disponibile' }, { status: 502 })));
  const user = userEvent.setup();
  render(<RegistrationForm />);
  await user.type(screen.getByLabelText(/Codice fiscale/), 'RSSMRA80A01H501U');
  await user.selectOptions(screen.getByLabelText(/Codice diagnosi/), '6401');
  await user.type(screen.getByLabelText(/^Nome/), 'Mario');
  await user.type(screen.getByLabelText(/^Cognome/), 'Rossi');
  await user.type(screen.getByLabelText(/Data di nascita/), '1980-01-02');
  await user.click(screen.getByRole('button', { name: 'Avvia percorso PDTA' }));
  await user.click(screen.getByRole('button', { name: 'Conferma e avvia' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Camunda non disponibile');
  expect(screen.getByRole('button', { name: 'Conferma e avvia' })).toBeDisabled();
  expect(screen.queryByText('Raccolta dati completata')).not.toBeInTheDocument();
});

it('allows correcting a rejected request without treating it as an uncertain creation', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ detail: 'Dati non validi' }, { status: 400 })));
  const user = userEvent.setup();
  render(<RegistrationForm />);
  await user.type(screen.getByLabelText(/Codice fiscale/), 'RSSMRA80A01H501U');
  await user.selectOptions(screen.getByLabelText(/Codice diagnosi/), '6401');
  await user.type(screen.getByLabelText(/^Nome/), 'Mario');
  await user.type(screen.getByLabelText(/^Cognome/), 'Rossi');
  await user.type(screen.getByLabelText(/Data di nascita/), '1980-01-02');
  await user.click(screen.getByRole('button', { name: 'Avvia percorso PDTA' }));
  await user.click(screen.getByRole('button', { name: 'Conferma e avvia' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Dati non validi');
  await user.click(screen.getByRole('button', { name: 'Modifica i dati' }));
  expect(screen.getByRole('button', { name: 'Avvia percorso PDTA' })).toBeEnabled();
});
