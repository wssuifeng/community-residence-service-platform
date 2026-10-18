"""S2 split HTTP methods by existing authorization; retain one handler per contract."""
from pathlib import Path
import csv,importlib.util,re,json
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).parent
sp=importlib.util.spec_from_file_location('inv',OUT/'migration_inventory.py');inv=importlib.util.module_from_spec(sp);sp.loader.exec_module(inv)
rows=list(csv.DictReader((OUT/'backend-ownership.csv').open(encoding='utf-8')))
report=[]
for row in rows:
 original=ROOT/row['source'];src=ROOT/row['transitionTarget']
 if original.parent.name!='controller':continue
 text=inv.read(src);head,parts,tail,entries=inv.methods(text)
 groups=sorted({e['owner'] for e in entries})
 helpers=[part for part in parts if part not in [e['declaration'] for e in entries]]
 destinations=[]
 for owner in groups:
  dst=inv.controller_destination(original,owner)
  selected=[e['declaration'] for e in entries if e['owner']==owner]
  # Keep only helpers reachable from selected methods. Fields follow actual usage.
  method_helpers=[x for x in helpers if re.search(r'\b(?:private|protected)\s+[^;{]*\(',inv.mask(x))]
  used='\n'.join(selected);keep=[]
  changed=True
  while changed:
   changed=False
   for helper in method_helpers:
    name=re.search(r'\b(?:private|protected)\s+[^;{]*?\s+(\w+)\s*\(',inv.mask(helper))[1]
    if helper not in keep and re.search(r'\b'+name+r'\s*\(',used):keep.append(helper);used+='\n'+helper;changed=True
  fields=[]
  for field in helpers:
   if field in method_helpers:continue
   names=re.findall(r'\b(\w+)\s*(?:=|;)',inv.mask(field))
   if not field.strip():continue
   if names and not any(re.search(r'\b'+n+r'\b',used) for n in names):continue
   fields.append(field)
  newhead=re.sub(r'^package\s+[\w.]+;', 'package '+'.'.join(dst.parent.relative_to(ROOT/'backend/src/main/java').parts)+';',head,count=1,flags=re.M)
  newhead=re.sub(r'\bclass\s+'+src.stem+r'\b','class '+dst.stem,newhead)
  output=newhead+''.join(fields)+''.join(selected)+''.join(keep)+'\n'+tail
  # Remove unused ordinary imports after the split; static/wildcard imports remain intact.
  body=re.sub(r'^import[^\n]+\n','',output,flags=re.M)
  output=re.sub(r'^import ([\w.]+);\r?\n',lambda m:m[0] if re.search(r'\b'+m[1].split('.')[-1]+r'\b',body) else '',output,flags=re.M)
  dst.parent.mkdir(parents=True,exist_ok=True)
  assert not dst.exists() or dst==src,dst
  dst.write_text(output,encoding='utf-8')
  destinations.append(inv.rel(dst))
 if inv.rel(src) not in destinations:src.unlink()
 report.append({'source':inv.rel(src),'destinations':destinations,'methods':len(entries)})
(OUT/'s2-controller-relocation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
# Direct controller tests now register the real per-end entry points together.
p=ROOT/'backend/src/test/java/com/community/residence/common/workorder/controller/WorkOrderActionBodyOptionalTest.java'
s=inv.read(p).replace('new WorkOrderController(workOrderService)', 'new com.community.residence.admin.workorder.controller.AdminWorkOrderController(workOrderService), new com.community.residence.resident.workorder.controller.ResidentWorkOrderController(workOrderService), new com.community.residence.staff.workorder.controller.StaffWorkOrderController(workOrderService), new WorkOrderQueryController(workOrderService)')
p.write_text(s,encoding='utf-8')
p=ROOT/'backend/src/test/java/com/community/residence/common/community/service/CommunityStructureContractTest.java';s=inv.read(p).replace('com.community.residence.common.community.controller.HouseController.class','com.community.residence.admin.community.controller.AdminHouseController.class');p.write_text(s,encoding='utf-8')
print('Split',len(report),'controller files into',sum(len(x['destinations']) for x in report),'entry classes;',sum(x['methods'] for x in report),'HTTP methods')
