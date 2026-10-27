import { expect, test } from '@playwright/test';

test('detail navigation, role switch and closed task error', async ({ page }) => {
  const task = { taskKey: '42', processInstanceKey: '100', name: 'Valutazione Chirurgo',
    assignee: null, patientID: 'DEMO-DETAIL', patientData: 'Paziente fittizio', diagnosiCode: '6401' };
  await page.route('**/api/tasks?role=*', route => route.fulfill({ json: [task] }));
  let closed = false;
  await page.route('**/api/tasks/42?role=*', route => route.fulfill(closed
    ? { status: 409, json: { detail: "L'attivita' non e' piu' aperta." } }
    : { json: { ...task, role: 'CHIRURGHI', state: 'CREATED', note: '', examResult: 'Referto dimostrativo',
      clinicalFileUrl: 'https://example.test/cartella' } }));
  await page.goto('/');
  await page.getByLabel('Vista operatore').selectOption('CHIRURGHI');
  await page.getByRole('button', { name: /Apri dettaglio/ }).click();
  await expect(page.getByRole('heading', { name: "Dettaglio attivita'" })).toBeFocused();
  await expect(page.getByText('Referto dimostrativo')).toBeVisible();
  await expect(page.getByRole('link', { name: /Apri cartella clinica/ })).toHaveAttribute('target', '_blank');
  await page.getByRole('button', { name: "Torna alle attivita'" }).click();
  closed = true;
  await page.getByRole('button', { name: /Apri dettaglio/ }).click();
  await expect(page.getByRole('alert')).toContainText("non e' piu' aperta");
  await expect(page.getByText('Referto dimostrativo')).toHaveCount(0);
  await page.getByLabel('Vista operatore').selectOption('ONCOLOGI');
  await expect(page.getByRole('heading', { name: "Attivita' di valutazione" })).toBeVisible();
  await expect(page.getByRole('heading', { name: "Dettaglio attivita'" })).toHaveCount(0);
});
