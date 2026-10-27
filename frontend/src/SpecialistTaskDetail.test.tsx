import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { SpecialistTasks } from './SpecialistTasks';

afterEach(() => vi.unstubAllGlobals());

const summary = { taskKey: '42', processInstanceKey: '100', name: 'Valutazione Chirurgo',
  assignee: 'CH001', patientID: '001', patientData: 'Paziente fittizio', diagnosiCode: '6401' };
const detail = { ...summary, state: 'CREATED', role: 'CHIRURGHI', currentOperatorId: 'CH001',
  clinicalFileUrl: 'https://example.test/cartella', examResult: 'Referto chirurgico\nRiga due', note: 'Nota chirurgo' };

it('opens detail with report and safe external link and returns to list', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
    Promise.resolve(Response.json(url.includes('/tasks/42?') ? detail : [summary]))));
  const client = new QueryClient();
  const view = render(<QueryClientProvider client={client}><SpecialistTasks role="CHIRURGHI" /></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: /Apri dettaglio/ }));
  expect(await screen.findByText(/Referto chirurgico/)).toBeInTheDocument();
  expect(screen.getByText('Nota chirurgo')).toBeInTheDocument();
  const link = screen.getByRole('link', { name: /Apri cartella clinica/ });
  expect(link).toHaveAttribute('href', 'https://example.test/cartella');
  expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  await user.click(screen.getByRole('button', { name: "Torna alle attivita'" }));
  expect(await screen.findByRole('button', { name: /Apri dettaglio/ })).toBeInTheDocument();
  view.unmount();
  client.clear();
});

it('does not show stale patient details after a conflict on refresh', async () => {
  let closed = false;
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
    Promise.resolve(url.includes('/tasks/42?')
      ? closed ? Response.json({ detail: "L'attivita' non e' piu' aperta" }, { status: 409 }) : Response.json(detail)
      : Response.json([summary]))));
  const client = new QueryClient();
  const view = render(<QueryClientProvider client={client}><SpecialistTasks role="CHIRURGHI" /></QueryClientProvider>);
  await userEvent.click(await screen.findByRole('button', { name: /Apri dettaglio/ }));
  expect(await screen.findByText('Nota chirurgo')).toBeInTheDocument();
  closed = true;
  await client.refetchQueries({ queryKey: ['task-detail', 'CHIRURGHI', '42'] });
  expect(await screen.findByRole('alert')).toHaveTextContent("non e' piu' aperta");
  expect(screen.queryByText('Nota chirurgo')).not.toBeInTheDocument();
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  view.unmount();
  client.clear();
});

it('shows explicit empty fields rather than inventing a report', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(Response.json(
    url.includes('/tasks/42?') ? { ...detail, clinicalFileUrl: null, examResult: '', note: '' } : [summary]))));
  const client = new QueryClient();
  const view = render(<QueryClientProvider client={client}><SpecialistTasks role="CHIRURGHI" /></QueryClientProvider>);
  await userEvent.click(await screen.findByRole('button', { name: /Apri dettaglio/ }));
  expect(await screen.findByText('Nessun referto disponibile.')).toBeInTheDocument();
  expect(screen.queryByRole('link')).not.toBeInTheDocument();
  view.unmount();
  client.clear();
});
