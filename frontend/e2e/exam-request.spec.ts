import { expect, test } from '@playwright/test';

for (const mode of ['AUTO_FIRST_AVAILABLE', 'MANUAL_CALENDAR']) {
  test(`specialist requests diagnostic exam: ${mode}`, async ({ page }) => {
    if (mode === 'MANUAL_CALENDAR') await page.setViewportSize({ width: 390, height: 844 });
    const task = { taskKey: '42', processInstanceKey: '100', name: 'Valutazione Chirurgo', assignee: 'CH001',
      patientID: 'DEMO-EXAM', patientData: 'Paziente fittizio', diagnosiCode: '6401' };
    const slot = { resourceId: 7, resourceCode: 'TAC-01', resourceName: 'Sala TAC', protocolId: 9,
      examDisplayName: 'TAC Torace', startTime: '2026-10-28T09:00:00', endTime: '2026-10-28T09:35:00',
      totalSlotMinutes: 35, requiresContrast: true, requiresFasting: true, notes: 'Preparazione' };
    let completed = false;
    let sends = 0;
    await page.route('**/api/tasks?role=CHIRURGHI', route => route.fulfill({ json: completed ? [] : [task] }));
    await page.route('**/api/tasks/42?role=CHIRURGHI', route => route.fulfill({
      json: { ...task, role: 'CHIRURGHI', state: 'CREATED', currentOperatorId: 'CH001', note: '', examResult: '', clinicalFileUrl: null },
    }));
    await page.route('**/api/exams/options', route => route.fulfill({ json: {
      combinations: [{ examType: 'TAC', bodyPart: 'TORACE' }], fromDate: '2026-10-28', toDate: '2026-11-06',
    } }));
    await page.route('**/api/exams/slots?*', route => route.fulfill({ json: [slot] }));
    await page.route('**/api/tasks/42/exam-request?role=CHIRURGHI', async route => {
      sends++;
      expect(route.request().postDataJSON()).toEqual({
        examType: 'TAC', bodyPart: 'TORACE', schedulingPreference: mode,
        resourceId: mode === 'MANUAL_CALENDAR' ? 7 : null,
        startTime: mode === 'MANUAL_CALENDAR' ? slot.startTime : null, note: 'Approfondimento',
      });
      completed = true;
      await route.fulfill({ json: { status: 'COMPLETED', taskKey: '42', assignee: 'CH001' } });
    });
    await page.goto('/');
    await page.getByLabel('Vista operatore').selectOption('CHIRURGHI');
    await page.getByRole('button', { name: /Apri dettaglio/ }).click();
    await page.getByRole('combobox', { name: 'Esito', exact: true }).selectOption('EXAM');
    await page.getByLabel('Tipo esame').selectOption('TAC');
    await page.getByLabel('Parte corporea').selectOption('TORACE');
    await page.getByLabel("Modalita' di schedulazione").selectOption(mode);
    if (mode === 'MANUAL_CALENDAR') {
      await page.getByLabel('Fascia oraria').selectOption('7|2026-10-28T09:00:00');
      await expect(page.getByText('Protocollo con digiuno.')).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    }
    await page.getByLabel('Note per il laboratorio (facoltative)').fill('Approfondimento');
    await page.getByRole('button', { name: 'Rivedi richiesta esame' }).click();
    expect(sends).toBe(0);
    await page.getByRole('button', { name: 'Conferma e richiedi esame' }).click();
    await expect(page.getByRole('heading', { name: 'Valutazione inviata' })).toBeVisible();
    expect(sends).toBe(1);
  });
}
