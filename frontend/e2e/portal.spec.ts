import { expect, test } from '@playwright/test';

test('registration survives reload and does not start a second process', async ({ page }) => {
  let creations = 0;
  let completions = 0;
  await page.route('**/api/registrations', async route => {
    creations++;
    expect(route.request().postDataJSON()).toMatchObject({ patientID: '001', diagnosiCode: '6401' });
    await route.fulfill({ status: 201, json: { processInstanceKey: '42', status: 'WAITING' } });
  });
  await page.route('**/api/registrations/42/complete-access', async route => {
    completions++;
    await route.fulfill({ json: { processInstanceKey: '42', status: completions > 1 ? 'COMPLETED' : 'WAITING' } });
  });
  await page.goto('/');
  await page.getByLabel('ID paziente').fill('001');
  await page.getByLabel('Codice diagnosi').fill('6401');
  await page.getByLabel('Dati del paziente').fill('Paziente dimostrativo');
  await page.getByRole('button', { name: 'Avvia percorso PDTA' }).click();
  await expect(page.getByText('Conferma i dati prima dell\'avvio')).toBeVisible();
  expect(creations).toBe(0);
  await page.getByRole('button', { name: 'Conferma e avvia' }).click();
  await expect(page.getByText('Processo creato, accesso da completare')).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Verifica e completa accesso' }).click();
  await expect(page.getByText('Raccolta dati completata')).toBeVisible();
  expect(creations).toBe(1);
  await page.getByRole('button', { name: 'Registra un altro paziente' }).click();
  await expect(page.getByLabel('ID paziente')).toHaveValue('');
});

test('specialist views remain distinct and failed retrieval is not shown as an empty list', async ({ page }) => {
  await page.route('**/api/tasks?role=CHIRURGHI', route => route.fulfill({
    json: [{ taskKey: '1', processInstanceKey: '42', name: 'Valutazione Chirurgo', patientID: '001',
      patientData: 'Paziente dimostrativo chirurgia', diagnosiCode: '6401', assignee: null }],
  }));
  await page.route('**/api/tasks?role=ONCOLOGI', route => route.fulfill({
    status: 502, json: { detail: 'Camunda temporaneamente non disponibile' },
  }));
  await page.goto('/');
  await page.getByLabel('Vista operatore').selectOption('CHIRURGHI');
  await expect(page.getByText('Paziente dimostrativo chirurgia')).toBeVisible();
  await page.getByLabel('Vista operatore').selectOption('ONCOLOGI');
  await expect(page.getByRole('alert')).toContainText('Camunda temporaneamente non disponibile');
  await expect(page.getByText('Paziente dimostrativo chirurgia')).toHaveCount(0);
  await expect(page.getByText("Nessuna attivita' in attesa")).toHaveCount(0);
});

test('mobile registration is accessible and has no page-level horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByText('DEMO LOCALE')).toBeVisible();
  await page.getByRole('button', { name: 'Avvia percorso PDTA' }).click();
  await expect(page.getByLabel('ID paziente')).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
