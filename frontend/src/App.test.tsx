import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => vi.unstubAllGlobals());

it('changes specialist view without showing the previous role patient data', async () => {
  const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(Response.json([
    { taskKey: url.includes('CHIRURGHI') ? '1' : '2', processInstanceKey: '42',
      name: 'Valutazione', assignee: null, patientID: '001', diagnosiCode: '6401',
      patientData: url.includes('CHIRURGHI') ? 'Paziente chirurgia' : 'Paziente oncologia' },
  ])));
  vi.stubGlobal('fetch', fetchMock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
  expect(screen.getByText(/nessun login reale/)).toBeInTheDocument();
  const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText('Vista operatore'), 'CHIRURGHI');
  expect(await screen.findByText('Paziente chirurgia')).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Vista operatore'), 'ONCOLOGI');
  expect(await screen.findByText('Paziente oncologia')).toBeInTheDocument();
  expect(screen.queryByText('Paziente chirurgia')).not.toBeInTheDocument();
  view.unmount();
  client.clear();
});

it('shows a task retrieval error rather than an empty successful task list', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ detail: 'Errore Camunda' }, { status: 502 })));
  const client = new QueryClient();
  const view = render(<QueryClientProvider client={client}><App /></QueryClientProvider>);
  await userEvent.selectOptions(screen.getByLabelText('Vista operatore'), 'ONCOLOGI');
  expect(await screen.findByRole('alert')).toHaveTextContent('Errore Camunda');
  expect(screen.queryByText("Nessuna attivita' in attesa")).not.toBeInTheDocument();
  view.unmount();
  client.clear();
});
