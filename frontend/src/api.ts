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
