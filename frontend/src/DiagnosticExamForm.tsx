import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getExamOptions, getExamSlots, type DiagnosticExamRequest, type ExamSlot } from './api';

export interface ExamDraft {
  kind: 'exam';
  request: DiagnosticExamRequest;
  slot: ExamSlot | null;
}

export function DiagnosticExamForm({ disabled, onReview }: {
  disabled: boolean;
  onReview: (draft: ExamDraft) => void;
}) {
  const [examType, setExamType] = useState('');
  const [bodyPart, setBodyPart] = useState('');
  const [mode, setMode] = useState<DiagnosticExamRequest['schedulingPreference']>('AUTO_FIRST_AVAILABLE');
  const [day, setDay] = useState('');
  const [selection, setSelection] = useState<ExamSlot | null>(null);
  const [note, setNote] = useState('');
  const options = useQuery({ queryKey: ['exam-options'], queryFn: ({ signal }) => getExamOptions(signal), retry: false });
  const combinations = options.data?.combinations ?? [];
  const validPair = combinations.some(option => option.examType === examType && option.bodyPart === bodyPart);
  const selectedDay = day || options.data?.fromDate || '';
  const slots = useQuery({
    queryKey: ['exam-slots', examType, bodyPart, selectedDay],
    queryFn: ({ signal }) => getExamSlots({ examType, bodyPart }, selectedDay, signal),
    enabled: validPair && mode === 'MANUAL_CALENDAR' && Boolean(selectedDay) && !options.isError,
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const availableSelection = selection && !slots.isError && !slots.isFetching
    && slots.data?.some(slot => slot.resourceId === selection.resourceId && slot.startTime === selection.startTime);
  const canReview = validPair && !options.isError && (mode === 'AUTO_FIRST_AVAILABLE' || availableSelection);

  function prepare(event: FormEvent) {
    event.preventDefault();
    if (!canReview) return;
    const slot = mode === 'MANUAL_CALENDAR' ? selection : null;
    onReview({ kind: 'exam', slot, request: {
      examType, bodyPart, schedulingPreference: mode, note: note.trim(),
      resourceId: slot?.resourceId ?? null, startTime: slot?.startTime ?? null,
    } });
  }

  return <form onSubmit={prepare}>
    {options.isPending && <p role="status">Caricamento esami disponibili...</p>}
    {options.isError && <div role="alert" className="notice error">
      <p>{options.error.message}</p>
      <button type="button" disabled={options.isFetching || disabled} onClick={() => void options.refetch()}>Ricarica esami</button>
    </div>}
    {options.isSuccess && combinations.length === 0 && <p role="status">Nessun protocollo esame attivo.</p>}
    <fieldset disabled={disabled || !options.isSuccess || options.isError}>
      <label className="decision-label">Tipo esame
        <select required value={examType} onChange={event => {
          setExamType(event.target.value); setBodyPart(''); setSelection(null);
        }}>
          <option value="">Seleziona esame</option>
          {[...new Set(combinations.map(option => option.examType))].map(type => <option key={type}>{type}</option>)}
        </select>
      </label>
      <label className="decision-label">Parte corporea
        <select required value={bodyPart} onChange={event => { setBodyPart(event.target.value); setSelection(null); }}>
          <option value="">Seleziona parte corporea</option>
          {combinations.filter(option => option.examType === examType).map(option =>
            <option key={option.bodyPart} value={option.bodyPart}>{option.bodyPart.replaceAll('_', ' ')}</option>)}
        </select>
      </label>
      <label className="decision-label">Modalita' di schedulazione
        <select value={mode} onChange={event => {
          if (event.target.value === 'AUTO_FIRST_AVAILABLE' || event.target.value === 'MANUAL_CALENDAR') {
            setMode(event.target.value); setSelection(null);
          }
        }}>
          <option value="AUTO_FIRST_AVAILABLE">Primo slot libero (automatico)</option>
          <option value="MANUAL_CALENDAR">Calendario manuale</option>
        </select>
      </label>
      {mode === 'MANUAL_CALENDAR' && <>
        <label className="decision-label">Giorno esame
          <input type="date" required min={options.data?.fromDate} max={options.data?.toDate} value={selectedDay}
            onChange={event => { setDay(event.target.value); setSelection(null); }} />
        </label>
        {validPair && slots.isFetching && <p role="status">Verifica disponibilita'...</p>}
        {slots.isError && <p className="notice error" role="alert">{slots.error.message}</p>}
        {validPair && slots.isSuccess && !slots.isFetching && slots.data.length === 0
          && <p role="status">Nessuna fascia disponibile in questo giorno. Scegli un altro giorno.</p>}
        {validPair && <button className="secondary" type="button" disabled={slots.isFetching}
          onClick={() => { setSelection(null); void slots.refetch(); }}>Aggiorna disponibilita'</button>}
        {slots.isSuccess && !slots.isError && !slots.isFetching && validPair && <>
          <label className="decision-label">Fascia oraria
            <select required value={availableSelection ? `${selection.resourceId}|${selection.startTime}` : ''}
              onChange={event => setSelection(slots.data.find(slot =>
                `${slot.resourceId}|${slot.startTime}` === event.target.value) ?? null)}>
              <option value="">Seleziona fascia</option>
              {slots.data.map(slot => <option key={`${slot.resourceId}|${slot.startTime}`}
                value={`${slot.resourceId}|${slot.startTime}`}>
                {slot.startTime.slice(11, 16)} - {slot.endTime.slice(11, 16)} | {slot.resourceCode} | {slot.examDisplayName}
              </option>)}
            </select>
          </label>
          {availableSelection && <div className="notice">
            <p>{selection.resourceName} · {selection.totalSlotMinutes} minuti</p>
            {selection.requiresContrast && <p>Protocollo con mezzo di contrasto.</p>}
            {selection.requiresFasting && <p>Protocollo con digiuno.</p>}
            {selection.notes && <p>{selection.notes}</p>}
          </div>}
        </>}
      </>}
      <label className="decision-label">Note per il laboratorio (facoltative)
        <textarea rows={3} maxLength={2000} value={note} onChange={event => setNote(event.target.value)} />
      </label>
      <p className="detail-footnote">La scelta non prenota uno slot. La Diagnostica confermera', modifichera' o rifiutera'
        la proposta in base alla disponibilita' al momento della prenotazione.</p>
      <button type="submit" disabled={!canReview}>Rivedi richiesta esame</button>
    </fieldset>
  </form>;
}
