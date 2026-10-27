import { expect, test } from '@playwright/test';
import type { DiagnosticTask, TaskSummary } from '../src/api';

const camunda = process.env.PDTA_CAMUNDA_URL || 'http://127.0.0.1:8080';

test('real parallel diagnostic requests retain their requester and refusals return to the correct specialist', async ({ page, request }) => {
  test.setTimeout(150000);
  const patientID = `GUI-LAB-${Date.now()}`;
  let processKey: string | undefined;
  const laboratoryKeys: string[] = [];
  const initialSpecialistKeys = new Map<string, string>();
  try {
    const creation = await request.post('/api/registrations', {
      data: { patientID, patientData: 'Paziente fittizio diagnostica parallela', diagnosiCode: '6401' },
    });
    expect(creation.status(), await creation.text()).toBe(201);
    processKey = (await creation.json()).processInstanceKey;
    await expect.poll(async () => {
      const response = await request.post(`/api/registrations/${processKey}/complete-access`);
      expect(response.ok(), await response.text()).toBe(true);
      return (await response.json()).status;
    }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe('COMPLETED');

    for (const [role, examType, bodyPart] of [['CHIRURGHI', 'TAC', 'TORACE'], ['ONCOLOGI', 'RMN', 'TESTA']] as const) {
      let specialist: TaskSummary | undefined;
      await expect.poll(async () => {
        const response = await request.get(`/api/tasks?role=${role}`);
        if (!response.ok()) {
          console.warn(`Specialist indexing ${response.status()}: ${await response.text()}`);
          return false;
        }
        specialist = ((await response.json()) as TaskSummary[]).find(task => task.processInstanceKey === processKey);
        return Boolean(specialist);
      }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(true);
      const claim = await request.post(`/api/tasks/${specialist!.taskKey}/claim?role=${role}`);
      expect(claim.ok(), await claim.text()).toBe(true);
      await expect.poll(async () => {
        const response = await request.get(`/api/tasks/${specialist!.taskKey}?role=${role}`);
        return response.ok() ? (await response.json()).assignee : null;
      }).toBe(role === 'CHIRURGHI' ? 'CH001' : 'ON001');
      initialSpecialistKeys.set(role, specialist!.taskKey);
      const exam = await request.post(`/api/tasks/${specialist!.taskKey}/exam-request?role=${role}`, {
        data: { examType, bodyPart, schedulingPreference: 'AUTO_FIRST_AVAILABLE', resourceId: null,
          startTime: null, note: `Richiesta parallela ${role}` },
      });
      expect(exam.ok(), await exam.text()).toBe(true);
    }

    let laboratories: DiagnosticTask[] = [];
    await expect.poll(async () => {
      const response = await request.get('/api/diagnostics/tasks');
      if (response.status() === 502) {
        console.warn(`Diagnostic indexing ${response.status()}: ${await response.text()}`);
        return 0;
      }
      expect(response.ok(), await response.text()).toBe(true);
      laboratories = ((await response.json()) as DiagnosticTask[]).filter(task => task.processInstanceKey === processKey);
      return laboratories.length;
    }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(2);
    expect(laboratories.every(task => !task.contextError)).toBe(true);
    expect(laboratories.map(task => task.request?.requesterRole).sort()).toEqual(['CHIRURGHI', 'ONCOLOGI']);
    laboratoryKeys.push(...laboratories.map(task => task.taskKey));

    await page.goto('/');
    await page.getByLabel('Vista operatore').selectOption('LABORATORIO');
    for (const [role, examType, otherRole] of [['CHIRURGHI', 'TAC', 'ONCOLOGI'], ['ONCOLOGI', 'RMN', 'CHIRURGHI']] as const) {
      const laboratory = laboratories.find(task => task.request?.requesterRole === role)!;
      expect(laboratory.request?.examType).toBe(examType);
      const reason = `Rifiuto dimostrativo ${role}`;
      await page.getByRole('button', { name: `Apri richiesta ${laboratory.taskKey} per paziente ${patientID}` }).click();
      await expect(page.getByText(`Richiesta parallela ${role}`, { exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Prendi in carico richiesta' }).click();
      await page.getByLabel('Decisione prenotazione').selectOption('REJECT');
      await page.getByLabel('Motivazione obbligatoria').fill(reason);
      await page.getByRole('button', { name: 'Rivedi decisione diagnostica' }).click();
      const sending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/booking'));
      await page.getByRole('button', { name: 'Conferma decisione diagnostica' }).click();
      const sent = await sending;
      expect(sent.ok(), await sent.text()).toBe(true);
      expect((await sent.json()).status).toBe('COMPLETED');
      await expect(page.getByRole('heading', { name: 'Rifiuto restituito allo specialista' })).toBeVisible();
      const repeat = await request.post(`/api/diagnostics/tasks/${laboratory.taskKey}/booking`, {
        data: { action: 'REJECT', reason, resourceId: null, startTime: null },
      });
      expect(repeat.status()).toBe(409);

      let returned: TaskSummary | undefined;
      await expect.poll(async () => {
        const response = await request.get(`/api/tasks?role=${role}`);
        if (!response.ok()) {
          console.warn(`Specialist return indexing ${response.status()}: ${await response.text()}`);
          return false;
        }
        returned = ((await response.json()) as TaskSummary[]).find(task => task.processInstanceKey === processKey
          && task.taskKey !== initialSpecialistKeys.get(role));
        return Boolean(returned);
      }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(true);
      await expect.poll(async () => {
        const detail = await request.get(`/api/tasks/${returned!.taskKey}?role=${role}`);
        if (detail.status() === 502) {
          console.warn(`Specialist result indexing ${detail.status()}: ${await detail.text()}`);
          return null;
        }
        expect(detail.ok(), await detail.text()).toBe(true);
        return (await detail.json()).examResult;
      }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(`Esame rifiutato dalla diagnostica: ${reason}`);
      if (role === 'CHIRURGHI') {
        const pending = await request.get('/api/diagnostics/tasks');
        expect(pending.ok()).toBe(true);
        const ownPending = ((await pending.json()) as DiagnosticTask[]).filter(task => task.processInstanceKey === processKey);
        expect(ownPending).toHaveLength(1);
        expect(ownPending[0].request?.requesterRole).toBe(otherRole);
        expect(ownPending[0].contextError).toBeNull();
      }
      await page.getByRole('button', { name: 'Torna alle richieste' }).click();
    }
    const elements = await request.post(`${camunda}/v2/element-instances/search`, {
      data: { filter: { processInstanceKey: processKey }, page: { limit: 100 } },
    });
    expect(elements.ok()).toBe(true);
    const body: { items: { hasIncident: boolean }[] } = await elements.json();
    expect(body.items.some(item => item.hasIncident)).toBe(false);
    console.log(JSON.stringify({ processKey, laboratoryKeys, result: 'PASS', bookingsCreated: 0 }));
  } finally {
    if (processKey) {
      const canceled = await request.post(`${camunda}/v2/process-instances/${processKey}/cancellation`, { data: {} });
      expect(canceled.ok(), await canceled.text()).toBe(true);
      await expect.poll(async () => {
        const response = await request.get(`${camunda}/v2/process-instances/${processKey}`);
        expect(response.ok()).toBe(true);
        return (await response.json()).state;
      }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe('TERMINATED');
      console.log(`Canceled synthetic ${processKey}; historical workflow and diagnostic refusal decisions remain.`);
    }
  }
});
