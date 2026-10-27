import { expect, test, type APIRequestContext } from '@playwright/test';

const camunda = process.env.PDTA_CAMUNDA_URL || 'http://127.0.0.1:8080';

interface ElementInstance { elementId: string; state: string; hasIncident: boolean }

async function elements(request: APIRequestContext, key: string): Promise<ElementInstance[]> {
  const response = await request.post(`${camunda}/v2/element-instances/search`, {
    data: { filter: { processInstanceKey: key }, page: { limit: 100 } },
  });
  expect(response.ok()).toBe(true);
  return (await response.json()).items;
}

async function processState(request: APIRequestContext, key: string): Promise<string> {
  const response = await request.get(`${camunda}/v2/process-instances/${key}`);
  expect(response.ok()).toBe(true);
  return (await response.json()).state;
}

for (const scenario of ['approve-both', 'reject-surgeon'] as const) {
  test(`real Camunda decisions: ${scenario}`, async ({ page, request }) => {
    const patientID = `GUI-ACTION-${Date.now()}`;
    let processKey: string | undefined;
    try {
      await page.goto('/');
      await page.getByLabel('ID paziente').fill(patientID);
      await page.getByLabel('Codice diagnosi').fill('6401');
      await page.getByLabel('Dati del paziente').fill('Paziente fittizio test decisioni');
      await page.getByRole('button', { name: 'Avvia percorso PDTA' }).click();
      const creating = page.waitForResponse(response => new URL(response.url()).pathname === '/api/registrations'
        && response.request().method() === 'POST');
      await page.getByRole('button', { name: 'Conferma e avvia' }).click();
      const created = await creating;
      expect(created.status()).toBe(201);
      processKey = (await created.json()).processInstanceKey;
      await expect.poll(async () => {
        if (await page.getByText('Raccolta dati completata', { exact: true }).isVisible()) return true;
        const retry = page.getByRole('button', { name: 'Verifica e completa accesso' });
        if (await retry.isVisible() && await retry.isEnabled()) await retry.click();
        return false;
      }, { intervals: [1000, 2000, 3000], timeout: 30000 }).toBe(true);

      const roles = scenario === 'approve-both' ? ['CHIRURGHI', 'ONCOLOGI'] : ['CHIRURGHI'];
      for (const role of roles) {
        await page.getByLabel('Vista operatore').selectOption(role);
        const row = page.getByRole('row').filter({ hasText: patientID });
        await expect(row).toHaveCount(1);
        await row.getByRole('button', { name: /Apri dettaglio/ }).click();
        const claiming = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/claim'));
        await page.getByRole('button', { name: 'Prendi in carico' }).click();
        const claimed = await claiming;
        expect(claimed.status(), await claimed.text()).toBe(200);
        const outcome = page.getByRole('combobox', { name: 'Esito', exact: true });
        await expect(outcome).toBeVisible();
        const rejecting = scenario === 'reject-surgeon';
        await outcome.selectOption(rejecting ? 'REJECT' : 'APPROVE');
        if (rejecting) {
          await expect(page.getByRole('button', { name: 'Rivedi esito' })).toBeDisabled();
          await page.getByLabel(/Motivazione del rifiuto/).fill('Rifiuto dimostrativo motivato');
        } else {
          await page.getByLabel('Note (facoltative)').fill(`Approvazione dimostrativa ${role}`);
        }
        await page.getByRole('button', { name: 'Rivedi esito' }).click();
        const deciding = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/decision'));
        await page.getByRole('button', { name: 'Conferma e invia esito' }).click();
        const decided = await deciding;
        expect(decided.status(), await decided.text()).toBe(200);
        await expect(page.getByRole('heading', { name: 'Valutazione inviata' })).toBeVisible();

        // A replay of this same task must be rejected, never report another success.
        const replay = await request.post(decided.url(), {
          data: { outcome: rejecting ? 'REJECT' : 'APPROVE', note: 'Invio ripetuto dimostrativo' },
        });
        expect(replay.status()).toBe(409);
        await page.getByRole('button', { name: "Torna alle attivita'" }).click();
      }

      const expectedEnd = scenario === 'approve-both' ? 'Event_1xfnruz' : 'Event_0fff0ni';
      await expect.poll(async () => {
        const items = await elements(request, processKey!);
        expect(items.some(item => item.hasIncident)).toBe(false);
        return items.some(item => item.elementId === expectedEnd && item.state === 'COMPLETED');
      }, { intervals: [1000, 2000, 3000], timeout: 30000 }).toBe(true);
      const variablesResponse = await request.post(`${camunda}/v2/variables/search`, {
        data: { filter: { processInstanceKey: processKey, scopeKey: processKey }, page: { limit: 100 } },
      });
      expect(variablesResponse.ok()).toBe(true);
      const variablesBody: { items: { name: string; value: string }[]; page: { hasMoreTotalItems: boolean } }
        = await variablesResponse.json();
      expect(variablesBody.page.hasMoreTotalItems).toBe(false);
      const variables = new Map<string, unknown>(variablesBody.items.map(item => [item.name, JSON.parse(item.value)]));
      expect(variables.get('chirurgoApproved')).toBe(scenario === 'approve-both');
      expect(variables.get('chirurgoNote')).toBe(scenario === 'approve-both'
        ? 'Approvazione dimostrativa CHIRURGHI' : 'Rifiuto dimostrativo motivato');
      if (scenario === 'approve-both') {
        expect(variables.get('oncologoApproved')).toBe(true);
        expect(variables.get('chirurgoSigned')).toBe(true);
        expect(variables.get('oncologoSigned')).toBe(true);
        expect(variables.get('chirurgoSignature')).toEqual(expect.stringMatching(/^SIGNED_CHIRURGO_GUI-ACTION-\d+_\d+$/));
        expect(variables.get('oncologoSignature')).toEqual(expect.stringMatching(/^SIGNED_ONCOLOGO_GUI-ACTION-\d+_\d+$/));
        expect(variables.get('pdtaDocumentUrl')).toEqual(
          expect.stringMatching(new RegExp(`^/documents/pdta/PDTA-${patientID}-\\d{8}-\\d{6}\\.pdf$`)));
        const items = await elements(request, processKey!);
        for (const id of ['Activity_12b0j95', 'Activity_1q8grl2', 'Activity_1q4rmtc']) {
          expect(items.some(item => item.elementId === id && item.state === 'COMPLETED')).toBe(true);
        }
      }
      console.log(JSON.stringify({ scenario, processKey, expectedEnd, result: 'PASS' }));
    } finally {
      if (processKey) {
        const state = await processState(request, processKey);
        expect(['ACTIVE', 'COMPLETED', 'TERMINATED']).toContain(state);
        if (state === 'ACTIVE') {
          const canceled = await request.post(`${camunda}/v2/process-instances/${processKey}/cancellation`, { data: {} });
          // A completion can reach the broker before its state reaches the search index.
          expect(canceled.ok() || canceled.status() === 404,
            `Cleanup synthetic ${processKey}: ${await canceled.text()}`).toBe(true);
        }
        await expect.poll(() => processState(request, processKey!),
          { intervals: [1000, 2000, 3000], timeout: 30000 }).toMatch(/^(COMPLETED|TERMINATED)$/);
        console.log(`Synthetic case ${processKey}: no active test instance retained; history remains.`);
      }
    }
  });
}
