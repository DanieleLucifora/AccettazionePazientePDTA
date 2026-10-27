export type SpecialistRole = 'CHIRURGHI' | 'ONCOLOGI';
export type Role = 'CASE_MANAGER' | SpecialistRole | 'LABORATORIO';

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
export interface DiagnosticRequest {
  requesterRole: SpecialistRole;
  requesterId: string;
  requesterName: string;
  examType: string;
  bodyPart: string;
  schedulingPreference: string;
  note: string;
  proposedStart: string;
  proposedResourceId: string;
}
export interface DiagnosticDecision {
  action: 'CONFIRM' | 'MODIFY' | 'REJECT';
  reason: string;
  slotId: string | null;
  slot: ExamSlot | null;
  slotStatus: string | null;
  decidedAt: string;
}
export interface DiagnosticTask {
  taskKey: string;
  processInstanceKey: string;
  assignee: string | null;
  patientID: string;
  patientData: string;
  diagnosiCode: string;
  request: DiagnosticRequest | null;
  contextError: string | null;
  proposal: ExamSlot | null;
  proposalMessage: string | null;
  decision: DiagnosticDecision | null;
}
export interface DiagnosticBookingCommand {
  action: DiagnosticDecision['action'];
  resourceId: number | null;
  startTime: string | null;
  reason: string;
}
export const getDiagnosticTasks = (signal: AbortSignal): Promise<DiagnosticTask[]> =>
  request('/diagnostics/tasks', { signal });
export const getDiagnosticTask = (key: string, signal: AbortSignal): Promise<DiagnosticTask> =>
  request(`/diagnostics/tasks/${encodeURIComponent(key)}`, { signal });
export const claimDiagnosticTask = (key: string): Promise<TaskActionResult> =>
  request(`/diagnostics/tasks/${encodeURIComponent(key)}/claim`, { method: 'POST' });
export const bookDiagnosticTask = (key: string, command: DiagnosticBookingCommand):
  Promise<{ status: 'BOOKED' | 'COMPLETED'; decision: DiagnosticDecision }> =>
  request(`/diagnostics/tasks/${encodeURIComponent(key)}/booking`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command),
  });
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
  const params = new URLSearchParams({ examType: option.examType, bodyPart: option.bodyPart, day });
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
