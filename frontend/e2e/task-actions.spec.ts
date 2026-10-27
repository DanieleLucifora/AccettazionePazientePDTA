import { expect, test } from '@playwright/test';

test('claim, review, approval confirmation and single completion', async ({ page }) => {
  const task = { taskKey: '42', processInstanceKey: '100', name: 'Valutazione Chirurgo',
    assignee: null as string | null, patientID: 'DEMO-ACTION', patientData: 'Paziente fittizio', diagnosiCode: '6401' };
  let completed = false;
  let decisions = 0;
  await page.route('**/api/tasks?role=CHIRURGHI', route => route.fulfill({ json: completed ? [] : [task] }));
  await page.route('**/api/tasks/42?role=CHIRURGHI', route => route.fulfill({
    json: { ...task, state: 'CREATED', role: 'CHIRURGHI', currentOperatorId: 'CH001', examResult: '', note: '', clinicalFileUrl: null },
  }));
  await page.route('**/api/tasks/42/claim?role=CHIRURGHI', async route => {
    task.assignee = 'CH001';
    await route.fulfill({ json: { taskKey: '42', status: 'CLAIMED', assignee: 'CH001' } });
  });
  await page.route('**/api/tasks/42/decision?role=CHIRURGHI', async route => {
    decisions++;
    expect(route.request().postDataJSON()).toEqual({ outcome: 'APPROVE', note: 'Valutazione positiva' });
    completed = true;
    await route.fulfill({ json: { taskKey: '42', status: 'COMPLETED', assignee: 'CH001' } });
  });
  await page.goto('/');
  await page.getByLabel('Vista operatore').selectOption('CHIRURGHI');
  await page.getByRole('button', { name: /Apri dettaglio/ }).click();
  await page.getByRole('button', { name: 'Prendi in carico' }).click();
  await page.getByLabel('Note (facoltative)').fill('Valutazione positiva');
  await page.getByRole('button', { name: 'Rivedi esito' }).click();
  expect(decisions).toBe(0);
  await page.getByRole('button', { name: 'Conferma e invia esito' }).click();
  await expect(page.getByRole('heading', { name: 'Valutazione inviata' })).toBeVisible();
  expect(decisions).toBe(1);
  await page.getByRole('button', { name: "Torna alle attivita'" }).click();
  await expect(page.getByText("Nessuna attivita' in attesa")).toBeVisible();
});
