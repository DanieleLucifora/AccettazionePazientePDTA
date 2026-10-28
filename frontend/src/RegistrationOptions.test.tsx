import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { RegistrationForm } from './RegistrationForm';

afterEach(() => vi.unstubAllGlobals());
it('blocks admission on catalog failure and supports an explicit reload', async () => {
  let fails = true;
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(fails
    ? Response.json({ detail: 'Catalogo non disponibile' }, { status: 502 })
    : Response.json({ diagnosisCodes: ['1001', '6401'], today: '2026-10-28' }))));
  render(<RegistrationForm />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Catalogo non disponibile');
  expect(screen.getByLabelText(/^Nome/)).toBeDisabled();
  fails = false;
  await userEvent.click(screen.getByRole('button', { name: 'Ricarica codici diagnosi' }));
  expect(await screen.findByRole('option', { name: '6401' })).toBeInTheDocument();
  expect(screen.getByLabelText(/^Nome/)).toBeEnabled();
  expect(screen.getByLabelText(/Data di nascita/)).toHaveAttribute('max', '2026-10-28');
});
