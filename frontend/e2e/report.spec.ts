import { expect, test } from '@playwright/test';

test('diagnostic report review, uncertain delivery, reload and immutable recovery', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const slot = { resourceId: 7, resourceCode: 'TAC-01', resourceName: 'Sala TAC', protocolId: 9,
    examDisplayName: 'TAC Torace', startTime: '2026-10-26T09:00:00', endTime: '2026-10-26T09:35:00',
    totalSlotMinutes: 35, requiresContrast: false, requiresFasting: false, notes: '' };
  const text = 'Referto dimostrativo completo.\nSeconda riga del referto.';
  let saved = false;
  let sends = 0;
  const task = { taskKey: '42', processInstanceKey: '100', assignee: 'LAB001', patientID: 'DEMO-REPORT',
    patientData: 'Paziente fittizio', diagnosiCode: '6401', contextError: null,
    request: { requesterRole: 'CHIRURGHI', requesterId: 'CH001', requesterName: 'Marco Bianchi', examType: 'TAC',
      bodyPart: 'TORACE', schedulingPreference: 'AUTO_FIRST_AVAILABLE', note: '', proposedStart: '', proposedResourceId: '' },
    proposal: null, proposalMessage: null, decision: { action: 'CONFIRM', reason: '', slotId: '91', slot,
      slotStatus: 'BOOKED', decidedAt: '2026-10-25T18:00:00' }, reportAllowed: true };
  const report = { taskKey: '42', text, reportedAt: '2026-10-26T10:00:00', deliveredAt: null, status: 'PENDING' };
  await page.route('**/api/diagnostics/tasks', route => route.fulfill({ json: [{ ...task, report: saved ? report : null }] }));
  await page.route('**/api/diagnostics/tasks/42', route => route.fulfill({ json: { ...task, report: saved ? report : null } }));
  await page.route('**/api/diagnostics/tasks/42/report', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: { report: saved ? report : null } });
    sends++;
    expect(route.request().postDataJSON()).toEqual({ text });
    if (!saved) { saved = true; return route.fulfill({ status: 502, json: { detail: 'Risposta Camunda non ricevuta' } }); }
    return route.fulfill({ json: { ...report, status: 'SENT', deliveredAt: '2026-10-26T10:00:01' } });
  });
  await page.goto('/');
  await page.getByLabel('Vista operatore').selectOption('LABORATORIO');
  await page.getByRole('button', { name: /Apri richiesta/ }).click();
  await expect(page.getByRole('button', { name: 'Rivedi referto' })).toBeDisabled();
  await page.getByLabel('Testo del referto (obbligatorio)').fill(text);
  await page.getByRole('button', { name: 'Rivedi referto' }).click();
  expect(sends).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await page.getByRole('button', { name: 'Conferma e invia referto' }).dblclick();
  await expect(page.getByRole('alert')).toContainText('Risposta Camunda non ricevuta');
  await expect(page.getByRole('button', { name: 'Conferma e invia referto' })).toBeDisabled();
  expect(sends).toBe(1);
  await page.reload();
  await page.getByLabel('Vista operatore').selectOption('LABORATORIO');
  await page.getByRole('button', { name: /Apri richiesta/ }).click();
  await expect(page.getByRole('heading', { name: 'Referto salvato, invio da verificare' })).toBeVisible();
  await expect(page.getByRole('textbox')).toHaveCount(0);
  await page.getByRole('button', { name: 'Riprendi invio referto salvato' }).dblclick();
  await expect(page.getByRole('heading', { name: 'Referto inviato allo specialista' })).toBeVisible();
  expect(sends).toBe(2);
});
