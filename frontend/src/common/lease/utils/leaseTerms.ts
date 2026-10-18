export function calendarBoundary(iso: string, months: number): string {
 if(!iso || !Number.isInteger(months) || months<0) return ''
 const parts=iso.slice(0,10).split('-').map(Number);const y=parts[0]!,m=parts[1]!,d=parts[2]!
 const result=new Date(y,m-1+months,1);const last=new Date(result.getFullYear(),result.getMonth()+1,0).getDate()
 result.setDate(Math.min(d,last));return localISO(result)
}
function localISO(date: Date){const pad=(n:number)=>String(n).padStart(2,'0');return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}`}
export function contractEnd(iso: string, months: number): string { const bound=calendarBoundary(iso,months);if(!bound)return '';const date=new Date(`${bound}T00:00:00`);date.setDate(date.getDate()-1);return localISO(date) }
export function todayLeaseISO(){return localISO(new Date())}
