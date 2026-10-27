import { expect, test, type APIResponse } from '@playwright/test';
import type { ExamSlot } from '../src/api';

const camunda = process.env.PDTA_CAMUNDA_URL || 'http://127.0.0.1:8080';
interface EngineTask { userTaskKey: string; elementId: string; candidateGroups: string[]; state: string }

for (const [mode, role, prefix] of [
  ['AUTO_FIRST_AVAILABLE', 'CHIRURGHI', 'chirurgo'],
  ['MANUAL_CALENDAR', 'ONCOLOGI', 'oncologo'],
] as const) {
  test(`real diagnostic request: ${mode} ${role}`, async ({ page, request }) => {
    const patientID = `GUI-EXAM-${Date.now()}`;
    let processKey: string | undefined;
    try {
      await page.goto('/');
      await page.getByLabel('ID paziente').fill(patientID);
      await page.getByLabel('Codice diagnosi').fill('6401');
      await page.getByLabel('Dati del paziente').fill('Paziente fittizio verifica richiesta esami');
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
      }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(true);
      await page.getByLabel('Vista operatore').selectOption(role);
      await page.getByRole('row').filter({ hasText: patientID }).getByRole('button', { name: /Apri dettaglio/ }).click();
      await page.getByRole('button', { name: 'Prendi in carico' }).click();
      await page.getByRole('combobox', { name: 'Esito', exact: true }).selectOption('EXAM');
      await page.getByLabel('Tipo esame').selectOption('TAC');
      await page.getByLabel('Parte corporea').selectOption('TORACE');
      await page.getByLabel("Modalita' di schedulazione").selectOption(mode);
      let selection: ExamSlot | undefined;
      if (mode === 'MANUAL_CALENDAR') {
        const optionsResponse = await request.get('/api/exams/options');
        expect(optionsResponse.ok()).toBe(true);
        const options: { toDate: string } = await optionsResponse.json();
        const day = options.toDate;
        const response = await request.get(`/api/exams/slots?examType=TAC&bodyPart=TORACE&day=${day}`);
        expect(response.ok()).toBe(true);
        const slots: ExamSlot[] = await response.json();
        selection = slots[0];
        expect(selection).toBeDefined();
        await page.getByLabel('Giorno esame').fill(day);
        await page.getByLabel('Fascia oraria').selectOption(`${selection.resourceId}|${selection.startTime}`);
      }
      await page.getByLabel('Note per il laboratorio (facoltative)').fill(`Richiesta dimostrativa ${mode}`);
      await page.getByRole('button', { name: 'Rivedi richiesta esame' }).click();
      const sending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/exam-request'));
      await page.getByRole('button', { name: 'Conferma e richiedi esame' }).click();
      const sent = await sending;
      expect(sent.status(), await sent.text()).toBe(200);
      await expect(page.getByRole('heading', { name: 'Valutazione inviata' })).toBeVisible();
      const repeat = await request.post(sent.url(), {
        data: { examType: 'TAC', bodyPart: 'TORACE', schedulingPreference: 'AUTO_FIRST_AVAILABLE', note: '' },
      });
      expect(repeat.status()).toBe(409);

      let laboratory: EngineTask | undefined;
      await expect.poll(async () => {
        const response = await request.post(`${camunda}/v2/user-tasks/search`, {
          data: { filter: { processInstanceKey: processKey, state: 'CREATED' }, page: { limit: 100 } },
        });
        expect(response.ok()).toBe(true);
        const body: { items: EngineTask[] } = await response.json();
        laboratory = body.items.find(task => task.candidateGroups?.includes('LABORATORIO'));
        return Boolean(laboratory);
      }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(true);
      let variablesResponse: APIResponse | undefined;
      await expect.poll(async () => {
        variablesResponse = await request.post(`${camunda}/v2/user-tasks/${laboratory!.userTaskKey}/variables/search`,
          { data: { page: { limit: 100 } } });
        if (!variablesResponse.ok()) console.warn(`Laboratory variables indexing ${variablesResponse.status()}: ${await variablesResponse.text()}`);
        return variablesResponse.ok();
      }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe(true);
      const body: { items: { name: string; value: string }[]; page: { hasMoreTotalItems: boolean } }
        = await variablesResponse!.json();
      expect(body.page.hasMoreTotalItems).toBe(false);
      const variables = new Map<string, unknown>(body.items.map(item => [item.name, JSON.parse(item.value)]));
      expect(variables.get(`${prefix}RequiresExam`)).toBe(true);
      expect(variables.get(`${prefix}ExamType`)).toBe('TAC');
      expect(variables.get(`${prefix}BodyPart`)).toBe('TORACE');
      expect(variables.get(`${prefix}SchedulingPreference`)).toBe(mode);
      expect(variables.get(`${prefix}RequesterName`)).toBe(role === 'CHIRURGHI' ? 'Marco Bianchi' : 'Luigi Rossi');
      expect(variables.get(`${prefix}RequesterId`)).toBe(role === 'CHIRURGHI' ? 'CH001' : 'ON001');
      expect(variables.get('examQueue')).toBe(role);
      expect(variables.get(`${prefix}Note`)).toBe(`Richiesta dimostrativa ${mode}`);
      expect(variables.get(`${prefix}SelectedResourceId`)).toBe(selection ? String(selection.resourceId) : '');
      expect(variables.get(`${prefix}SelectedProtocolId`)).toBe(selection ? String(selection.protocolId) : '');
      if (selection) {
        expect(variables.get(`${prefix}SelectedSlotStart`)).toBe(selection.startTime.replace(/:00$/, ''));
        expect(variables.get(`${prefix}SelectedSlotEnd`)).toBe(selection.endTime.replace(/:00$/, ''));
      }
      const elements = await request.post(`${camunda}/v2/element-instances/search`, {
        data: { filter: { processInstanceKey: processKey }, page: { limit: 100 } },
      });
      expect(elements.ok()).toBe(true);
      const elementBody: { items: { hasIncident: boolean }[] } = await elements.json();
      expect(elementBody.items.some(item => item.hasIncident)).toBe(false);
      console.log(JSON.stringify({ processKey, role, mode, laboratoryTaskKey: laboratory!.userTaskKey, result: 'PASS' }));
    } finally {
      if (processKey) {
        const canceled = await request.post(`${camunda}/v2/process-instances/${processKey}/cancellation`, { data: {} });
        expect(canceled.ok(), await canceled.text()).toBe(true);
        await expect.poll(async () => {
          const response = await request.get(`${camunda}/v2/process-instances/${processKey}`);
          expect(response.ok()).toBe(true);
          return (await response.json()).state;
        }, { timeout: 30000, intervals: [1000, 2000, 3000] }).toBe('TERMINATED');
        console.log(`Canceled synthetic ${processKey}; historical workflow and request audit remain.`);
      }
    }
  });
}
