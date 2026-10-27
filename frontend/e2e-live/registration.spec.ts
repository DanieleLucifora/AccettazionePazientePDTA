import { expect, test } from '@playwright/test';

test('real Camunda: registration, access retries and both specialist views', async ({ page, request }) => {
  const patientID = `GUI-TEST-${Date.now()}`;
  const patientData = `Paziente fittizio verifica GUI "${patientID}"`;
  const camunda = process.env.PDTA_CAMUNDA_URL || 'http://127.0.0.1:8080';
  let processKey: string | undefined;
  let creations = 0;
  page.on('response', async response => {
    if (new URL(response.url()).pathname === '/api/registrations'
        && response.request().method() === 'POST' && response.status() === 201) {
      creations++;
      const body = await response.json();
      processKey = body.processInstanceKey;
    }
  });

  try {
    const invalid = await request.post('/api/registrations', {
      data: { patientID: '', patientData: 'Dati fittizi', diagnosiCode: '6401' },
    });
    expect(invalid.status()).toBe(400);
    expect((await request.get('/api/tasks?role=ADMIN')).status()).toBe(400);

    await page.goto('/');
    await expect(page.getByText('DEMO LOCALE')).toBeVisible();
    await page.getByLabel('ID paziente').fill(patientID);
    await page.getByLabel('Codice diagnosi').fill('6401');
    await page.getByLabel('Dati del paziente').fill(patientData);
    await page.getByRole('button', { name: 'Avvia percorso PDTA' }).click();
    await expect(page.getByText("Conferma i dati prima dell'avvio")).toBeVisible();
    expect(creations).toBe(0);
    const createdResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/registrations'
      && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Conferma e avvia' }).click();
    const created = await createdResponse;
    expect(created.status(), await created.text()).toBe(201);
    processKey = (await created.json()).processInstanceKey;
    expect(processKey).toMatch(/^[1-9][0-9]+$/);

    await expect.poll(async () => {
      if (await page.getByText('Raccolta dati completata', { exact: true }).isVisible()) return true;
      const retry = page.getByRole('button', { name: 'Verifica e completa accesso' });
      if (await retry.isVisible() && await retry.isEnabled()) await retry.click();
      return false;
    }, { intervals: [1000, 2000, 3000], timeout: 30000 }).toBe(true);

    // Repeating access completion must not start another process.
    const repeated = await request.post(`/api/registrations/${processKey}/complete-access`);
    expect.soft(repeated.status(), `Immediate access retry: ${await repeated.text()}`).toBe(200);
    if (repeated.status() === 200) {
      expect(['WAITING', 'COMPLETED']).toContain((await repeated.json()).status);
    }

    for (const [role, taskName] of [
      ['CHIRURGHI', 'Valutazione Chirurgo'],
      ['ONCOLOGI', 'Valutazione Oncologo'],
    ]) {
      await page.getByLabel('Vista operatore').selectOption(role);
      const row = page.getByRole('row').filter({ hasText: patientID });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText(patientData);
      await expect(row).toContainText(taskName);
      await expect(row).toContainText(processKey!);
      await page.reload();
      await page.getByLabel('Vista operatore').selectOption(role);
      await expect(page.getByRole('row').filter({ hasText: patientID })).toHaveCount(1);
    }
    const settledRetry = await request.post(`/api/registrations/${processKey}/complete-access`);
    expect(settledRetry.status()).toBe(200);
    expect((await settledRetry.json()).status).toBe('COMPLETED');
    expect(creations).toBe(1);
    console.log(JSON.stringify({ patientID, processKey, creations,
      specialistViews: 'PASS', immediateRetryHttp: repeated.status(), settledRetryHttp: settledRetry.status() }));
  } finally {
    if (processKey) {
      const cancel = await request.post(`${camunda}/v2/process-instances/${processKey}/cancellation`, { data: {} });
      expect(cancel.ok(), `Cleanup of synthetic instance ${processKey}: HTTP ${cancel.status()} ${await cancel.text()}`).toBe(true);
      console.log(`Canceled synthetic instance ${processKey}; historical trace remains in Camunda.`);
    }
  }
});
