export type SpecialistRole = 'CHIRURGHI' | 'ONCOLOGI';
export type Role = 'CASE_MANAGER' | SpecialistRole;

export interface RegistrationInput {
  patientID: string;
  patientData: string;
  diagnosiCode: string;
  clinicalFileUrl: string;
}

export interface RegistrationProgress {
  processInstanceKey: string;
  status: 'WAITING' | 'COMPLETED';
}

export interface TaskSummary {
  taskKey: string;
  processInstanceKey: string;
  name: string;
  assignee: string | null;
  patientID: string;
  patientData: string;
  diagnosiCode: string;
}

export interface TaskDetail extends TaskSummary {
  state: string;
  role: SpecialistRole;
  clinicalFileUrl: string | null;
  examResult: string;
  note: string;
  currentOperatorId: string;
}

export interface SpecialistDecision {
  outcome: 'APPROVE' | 'REJECT';
  note: string;
}

export interface TaskActionResult {
  taskKey: string;
  status: 'CLAIMED' | 'COMPLETED';
  assignee: string;
}

export interface ExamOption { examType: string; bodyPart: string }
export interface ExamOptions { combinations: ExamOption[]; fromDate: string; toDate: string }
export interface ExamSlot {
  resourceId: number;
  resourceCode: string;
  resourceName: string;
  protocolId: number;
  examDisplayName: string;
  totalSlotMinutes: number;
  requiresContrast: boolean;
  requiresFasting: boolean;
  notes: string | null;
  startTime: string;
  endTime: string;
}
export interface DiagnosticExamRequest extends ExamOption {
  schedulingPreference: 'AUTO_FIRST_AVAILABLE' | 'MANUAL_CALENDAR';
  resourceId: number | null;
  startTime: string | null;
  note: string;
}

export function getExamOptions(signal: AbortSignal): Promise<ExamOptions> {
  return request('/exams/options', { signal });
}

export function getExamSlots(option: ExamOption, day: string, signal: AbortSignal): Promise<ExamSlot[]> {
  const params = new URLSearchParams({ ...option, day });
  return request(`/exams/slots?${params}`, { signal });
}

export function requestExam(taskKey: string, role: SpecialistRole, exam: DiagnosticExamRequest): Promise<TaskActionResult> {
  return request(`/tasks/${encodeURIComponent(taskKey)}/exam-request?role=${role}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(exam),
  });
}

export function claimTask(taskKey: string, role: SpecialistRole): Promise<TaskActionResult> {
  return request(`/tasks/${encodeURIComponent(taskKey)}/claim?role=${role}`, { method: 'POST' });
}

export function decideTask(taskKey: string, role: SpecialistRole, decision: SpecialistDecision): Promise<TaskActionResult> {
  return request(`/tasks/${encodeURIComponent(taskKey)}/decision?role=${role}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(decision),
  });
}

export function getTask(taskKey: string, role: SpecialistRole, signal: AbortSignal): Promise<TaskDetail> {
  return request(`/tasks/${encodeURIComponent(taskKey)}?role=${role}`, { signal });
}

export class ApiError extends Error {
  constructor(message: string, public readonly status: number) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, options);
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null);
    const detail = body !== null && typeof body === 'object' && 'detail' in body
      && typeof body.detail === 'string' ? body.detail : `Richiesta non riuscita (HTTP ${response.status}).`;
    throw new ApiError(detail, response.status);
  }
  return response.json() as Promise<T>;
}

export function registerPatient(input: RegistrationInput): Promise<RegistrationProgress> {
  return request('/registrations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function completeAccess(key: string): Promise<RegistrationProgress> {
  return request(`/registrations/${encodeURIComponent(key)}/complete-access`, { method: 'POST' });
}

export function findTasks(role: SpecialistRole, signal: AbortSignal): Promise<TaskSummary[]> {
  return request(`/tasks?role=${role}`, { signal });
}
