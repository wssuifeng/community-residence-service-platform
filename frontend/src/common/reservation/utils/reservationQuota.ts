interface Slot {date:string;maxBookings:number;currentBookings:number;status:string}
interface Reservation {reserveDate:string;status:string}
export interface DayQuota {capacity:number;active:number;pending:number;full:boolean;reservations:number}
export function reservationDayQuotas(slots:Slot[], rows:Reservation[]):Map<string,DayQuota> {
  const result=new Map<string,DayQuota>()
  const ensure=(date:string)=>{ let entry=result.get(date); if(!entry){entry={capacity:0,active:0,pending:0,full:true,reservations:0};result.set(date,entry)}return entry }
  for(const slot of slots){ const entry=ensure(slot.date);entry.capacity+=slot.maxBookings;entry.active+=slot.currentBookings;entry.full=entry.full && slot.status==='FULL' }
  for(const row of rows){ if(!['PENDING','RESERVED','CONFIRMED'].includes(row.status))continue;const entry=ensure(row.reserveDate);entry.reservations++;if(row.status==='PENDING')entry.pending++ }
  return result
}
