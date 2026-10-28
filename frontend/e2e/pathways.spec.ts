import { expect, test } from '@playwright/test';

const summary = { key: '2251799816330408', state: 'ACTIVE', version: 6, hasIncident: false, startDate: '2026-01-01T10:00:00Z',
  endDate: '', patientID: '0001', patientData: 'Paziente fittizio precedente', firstName: '', lastName: '', birthDate: '', diagnosiCode: '6401' };
test.beforeEach(async ({ page }) => {
  await page.route('**/api/registrations/options', route => route.fulfill({
    json: { diagnosisCodes: ['1001', '2609', '3202', '5201', '6401', '7001'], today: '2026-10-28' },
  }));
});

test('Case Manager can search, inspect old pathways and recover from a failed refresh without stale clinical data', async ({ page }) => {
  let fails = false;
  await page.route('**/api/pathways?*', route => {
    const query = new URL(route.request().url()).searchParams;
    expect(['', '0001']).toContain(query.get('patientId'));
    return route.fulfill({ json: { items: [summary], offset: 0, total: 1, hasNext: false } });
  });
  await page.route(`**/api/pathways/${summary.key}`, route => fails
    ? route.fulfill({ status: 502, json: { detail: 'Storico temporaneamente non disponibile' } })
    : route.fulfill({ json: { pathway: summary, outcome: 'BRANCH_REJECTED', tasks: [], elements: [], exams: [], historicalSlots: [],
      surgeon: { role: 'CHIRURGHI', approved: false, examRequired: false, note: 'Valutazione fittizia', examResult: '' },
      oncologist: { role: 'ONCOLOGI', approved: null, examRequired: null, note: '', examResult: '' } } }));
  await page.goto('/');
  await page.getByRole('button', { name: 'Percorsi paziente', exact: true }).click();
  await page.getByLabel('Cerca ID paziente').fill('0001');
  await page.getByRole('button', { name: 'Cerca percorsi' }).click();
  await page.getByRole('button', { name: /Apri percorso/ }).click();
  await expect(page.getByText('Non disponibile: percorso con anagrafica precedente')).toBeVisible();
  await expect(page.getByText(/Altri rami possono essere ancora attivi/)).toBeVisible();
  await expect(page.getByText('In corso', { exact: true })).toBeVisible();
  fails = true;
  await page.getByRole('button', { name: 'Aggiorna dettaglio percorso' }).click();
  await expect(page.getByRole('alert')).toHaveText('Storico temporaneamente non disponibile');
  await expect(page.getByText('Note: Valutazione fittizia')).toHaveCount(0);
  await page.getByLabel('Vista operatore').selectOption('ONCOLOGI');
  await expect(page.getByRole('heading', { name: 'Dettaglio percorso paziente' })).toHaveCount(0);
});

test('mobile Case Manager navigation remains usable and both new names and birth date are required', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route('**/api/pathways?*', route => route.fulfill({ json: { items: [], offset: 0, total: 0, hasNext: false } }));
  await page.goto('/');
  await page.getByLabel('ID paziente').fill('0001');
  await page.getByLabel('Codice diagnosi').selectOption('6401');
  await page.getByRole('button', { name: 'Avvia percorso PDTA' }).click();
  await expect(page.getByLabel(/^Nome/)).toBeFocused();
  await page.getByLabel(/^Nome/).fill('Chloé');
  await page.getByLabel(/^Cognome/).fill("D'Amico");
  await page.getByRole('button', { name: 'Avvia percorso PDTA' }).click();
  await expect(page.getByLabel('Data di nascita')).toBeFocused();
  await page.getByLabel('Data di nascita').fill('1980-01-02');
  await page.getByRole('button', { name: 'Avvia percorso PDTA' }).click();
  await expect(page.getByText("D'Amico", { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Percorsi paziente', exact: true }).click();
  await expect(page.getByText('Nessun percorso corrispondente ai filtri.')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
