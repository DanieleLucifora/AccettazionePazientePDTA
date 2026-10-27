import { expect, test } from '@playwright/test';

test('real Camunda diagnostic reports return independently to both requesting specialists', async ({ page, request }) => {
  test.skip(!process.env.PDTA_REPORT_TASKS, 'Run via DiagnosticReportLiveTests for isolated ended-booking fixtures.');
  test.setTimeout(120000);
  const keys = process.env.PDTA_REPORT_TASKS!.split(',');
  const initialKeys = process.env.PDTA_REPORT_INITIAL_TASKS!.split(',');
  const patient = process.env.PDTA_REPORT_PATIENT!;
  const processKey = process.env.PDTA_REPORT_PROCESS!;
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.getByLabel('Vista operatore').selectOption('LABORATORIO');
  for (const [index, role] of ['CHIRURGHI', 'ONCOLOGI'].entries()) {
    const text = `Referto dimostrativo ${role}.\nEsame completato per ${patient}.`;
    await page.getByRole('button', { name: `Apri richiesta ${keys[index]} per paziente ${patient}` }).click();
    await page.getByLabel('Testo del referto (obbligatorio)').fill(text);
    await page.getByRole('button', { name: 'Rivedi referto' }).click();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    const sending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/report')
      && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Conferma e invia referto' }).dblclick();
    const response = await sending;
    expect(response.ok(), await response.text()).toBe(true);
    await expect(page.getByRole('heading', { name: 'Referto inviato allo specialista' })).toBeVisible();
    const persisted = await request.get(`/api/diagnostics/tasks/${keys[index]}/report`);
    expect(persisted.ok(), await persisted.text()).toBe(true);
    expect((await persisted.json()).report).toMatchObject({ text, status: 'SENT' });
    const duplicate = await request.post(`/api/diagnostics/tasks/${keys[index]}/report`, { data: { text } });
    expect(duplicate.ok(), await duplicate.text()).toBe(true);
    let returnedKey = '';
    await expect.poll(async () => {
      const tasks = await request.get(`/api/tasks?role=${role}`);
      if (!tasks.ok()) return false;
      const body: { taskKey: string; processInstanceKey: string }[] = await tasks.json();
      returnedKey = body.find(task => task.processInstanceKey === processKey && task.taskKey !== initialKeys[index])?.taskKey || '';
      return !!returnedKey;
    }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(true);
    await expect.poll(async () => {
      const detail = await request.get(`/api/tasks/${returnedKey}?role=${role}`);
      return detail.ok() ? (await detail.json()).examResult : null;
    }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(text);
    if (index === 0) {
      await expect.poll(async () => {
        const remaining = await request.get(`/api/diagnostics/tasks/${keys[1]}`);
        if (!remaining.ok()) return null;
        return (await remaining.json()).request?.requesterRole;
      }).toBe('ONCOLOGI');
    }
    await page.getByRole('button', { name: 'Torna alle richieste' }).click();
  }
});
