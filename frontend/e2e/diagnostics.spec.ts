import { expect, test } from '@playwright/test';

for (const action of ['CONFIRM', 'MODIFY', 'REJECT']) {
  test(`diagnostic request: claim, review, ${action}, read back`, async ({ page }) => {
    if (action === 'MODIFY') await page.setViewportSize({ width: 390, height: 844 });
    const slot = { resourceId: 7, resourceCode: 'TAC-01', resourceName: 'Sala TAC', protocolId: 9,
      examDisplayName: 'TAC Torace', startTime: '2026-10-28T09:00:00', endTime: '2026-10-28T09:35:00',
      totalSlotMinutes: 35, requiresContrast: true, requiresFasting: true, notes: 'Preparazione' };
    const alternative = { ...slot, startTime: '2026-10-28T10:00:00', endTime: '2026-10-28T10:35:00' };
    const task = { taskKey: '42', processInstanceKey: '100', assignee: null as string | null,
      patientID: 'DEMO-LAB', patientData: 'Paziente fittizio', diagnosiCode: '6401', contextError: null,
      request: { requesterRole: 'ONCOLOGI', requesterId: 'ON001', requesterName: 'Luigi Rossi',
        examType: 'TAC', bodyPart: 'TORACE', schedulingPreference: 'AUTO_FIRST_AVAILABLE',
        note: 'Approfondimento', proposedStart: '', proposedResourceId: '' }, proposal: slot, proposalMessage: null,
      decision: null as null | { action: string; reason: string; slotId: string | null; slot: typeof slot | null; slotStatus: string | null; decidedAt: string } };
    let completed = false;
    let sends = 0;
    await page.route('**/api/diagnostics/tasks', route => route.fulfill({ json: completed ? [] : [task] }));
    await page.route('**/api/diagnostics/tasks/42', route => route.fulfill({ json: task }));
    await page.route('**/api/diagnostics/tasks/42/claim', route => {
      task.assignee = 'LAB001';
      return route.fulfill({ json: { status: 'CLAIMED', taskKey: '42', assignee: 'LAB001' } });
    });
    await page.route('**/api/exams/options', route => route.fulfill({ json: {
      combinations: [{ examType: 'TAC', bodyPart: 'TORACE' }], fromDate: '2026-10-28', toDate: '2026-11-06',
    } }));
    await page.route('**/api/exams/slots?*', route => {
      const params = new URL(route.request().url()).searchParams;
      expect([...params.keys()].sort()).toEqual(['bodyPart', 'day', 'examType']);
      return route.fulfill({ json: [alternative] });
    });
    await page.route('**/api/diagnostics/tasks/42/booking', async route => {
      sends++;
      expect(route.request().postDataJSON()).toEqual({ action, reason: action === 'CONFIRM' ? '' : 'Motivazione demo',
        resourceId: action === 'REJECT' ? null : 7,
        startTime: action === 'REJECT' ? null : action === 'MODIFY' ? alternative.startTime : slot.startTime });
      task.decision = { action, reason: action === 'CONFIRM' ? '' : 'Motivazione demo',
        slotId: action === 'REJECT' ? null : '91', slot: action === 'REJECT' ? null : action === 'MODIFY' ? alternative : slot,
        slotStatus: action === 'REJECT' ? null : 'BOOKED', decidedAt: '2026-10-27T18:00:00' };
      completed = action === 'REJECT';
      await route.fulfill({ json: { status: completed ? 'COMPLETED' : 'BOOKED', decision: task.decision } });
    });
    await page.goto('/');
    await page.getByLabel('Vista operatore').selectOption('LABORATORIO');
    await page.getByRole('button', { name: /Apri richiesta/ }).click();
    await expect(page.getByText('Approfondimento', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Decisione prenotazione')).toHaveCount(0);
    await page.getByRole('button', { name: 'Prendi in carico richiesta' }).click();
    await page.getByLabel('Decisione prenotazione').selectOption(action);
    if (action !== 'CONFIRM') {
      await expect(page.getByRole('button', { name: 'Rivedi decisione diagnostica' })).toBeDisabled();
      await page.getByLabel('Motivazione obbligatoria').fill('Motivazione demo');
    }
    if (action === 'MODIFY') {
      await page.getByLabel('Fascia alternativa').selectOption('7|2026-10-28T10:00:00');
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    }
    await page.getByRole('button', { name: 'Rivedi decisione diagnostica' }).click();
    expect(sends).toBe(0);
    await page.getByRole('button', { name: 'Conferma decisione diagnostica' }).dblclick();
    await expect(page.getByRole('heading', { name: completed ? 'Rifiuto restituito allo specialista' : 'Prenotazione salvata' })).toBeVisible();
    expect(sends).toBe(1);
    await page.getByRole('button', { name: 'Torna alle richieste' }).click();
    if (completed) await expect(page.getByText('Nessuna richiesta diagnostica in attesa.')).toBeVisible();
    else {
      await page.getByRole('button', { name: /Apri richiesta/ }).click();
      await expect(page.getByRole('heading', { name: 'Prenotazione salvata' })).toBeVisible();
      await expect(page.getByLabel('Decisione prenotazione')).toHaveCount(0);
      expect(sends).toBe(1);
    }
  });
}
