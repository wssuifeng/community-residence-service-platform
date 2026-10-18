"""Check reconciliation records and compare document audit against 20538a6. No app access."""
from pathlib import Path
from collections import Counter
import hashlib,importlib.util,json,re,subprocess,sys
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent;BASE=ROOT/'docs/50_系统测试';HEAD='20538a6'
def read(p):return p.read_text('utf-8-sig')
def load(p):return json.loads(read(p))
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT).decode('utf-8-sig')
cases=load(OUT/'case-ledger.json');defs=load(OUT/'defect-ledger.json');rtm=load(OUT/'rtm-ledger.json');summary=load(OUT/'summary.json');reg=load(OUT/'evidence-registry.json')
changed=[x for x in git('diff','--name-only','-z',HEAD).split('\0') if x]
new=[x for x in git('ls-files','--others','--exclude-standard','-z').split('\0') if x and (x.startswith('docs/50_系统测试/evidence/db-final-reconciliation-20261019/') or x=='docs/50_系统测试/39_最终用例与缺陷处置台账.md')]
checks={}
checks['unique305TC']=len(cases)==len({x['id'] for x in cases})==305
checks['contiguous212DEF']={x['id'] for x in defs}=={f'DEF-{n:03}' for n in range(1,213)}
checks['unique85RTM']={x['id'] for x in rtm}=={f'R{n}' for n in range(1,76)}|{f'N{n}' for n in range(1,11)}
checks['allRTMCasesDefined']=all(cid in {x['id'] for x in cases} for r in rtm for cid in r['caseIds'])
checks['priority104_179_22']=Counter(x['priority'] for x in cases)==Counter({'P0':104,'P1':179,'P2':22})
checks['prioritiesActuallyWrittenIn04']=all((x['priority'] in read(ROOT/x['definition']['path'])[read(ROOT/x['definition']['path']).index(x['id']):][:180]) for x in cases if x['historicalPriority']=='unspecified')
checks['15PriorityAdditions']=sum(x['historicalPriority']=='unspecified' for x in cases)==15
checks['29SeverityAdditions']=sum(x['originalSeverity'] is None for x in defs)==29
checks['original177SeveritiesPreserved']=all(next(r for r in defs if r['id']==old['id'])['severity']==old['severity'] for old in load(BASE/'evidence/cz-test-closeout-20261018/defect-table-snapshot.json'))
checks['allDefinitionAndRegistrationLocationsResolve']=all((p:=ROOT/r[key]['path']).is_file() and r['id'] in read(p).splitlines()[r[key]['line']-1] for rows,key in [(cases,'definition'),(defs,'registration')] for r in rows)
checks['allEvidenceKeysExist']=all(key in reg for r in cases+defs for key in r['evidence'])
checks['allEvidenceLocationsResolve']=all((p:=ROOT/v['path']).is_file() and v['line']<=len(read(p).splitlines()) for v in reg.values())
checks['boundTestFilesExist']=all((ROOT/t['path']).is_file() for r in defs for t in r['testBindings'])
checks['boundHistoricalSuitesHaveNoFailures']=all(not t['historicalExecutedSuite'] or all(int(t['historicalExecutedSuite'][k])==0 for k in ('failures','errors','skipped')) for r in defs for t in r['testBindings'])
checks['caseStatsMatch']=Counter(x['disposition'] for x in cases)==Counter(summary['caseDisposition'])
checks['defectStatsMatch']=Counter(x['disposition'] for x in defs)==Counter(summary['defectDisposition'])
checks['severityStatsMatch']=Counter(x['severity'] for x in defs)==Counter(summary['defectSeverity'])
checks['18EngineeringRecordsNotClosed']=sum(x['disposition']=='工程回归有据，专项待评审' for x in defs)==18
checks['allProductDenominatorsReconcile']=sum(r['effectiveProductDenominator'] for r in summary['rates'].values())==190 and all(r['repairedWithRegression']+r['acceptedByUser']+r['engineeringEvidenceAwaitingReview']+r['retained']==r['effectiveProductDenominator'] for r in summary['rates'].values())
checks['statedClosureCountsMatch']=sum(r['repairedWithRegression'] for r in summary['rates'].values())==159 and sum(r['acceptedByUser'] for r in summary['rates'].values())==11
checks['onlyDocumentationAndOwnEvidenceChanged']=all(p in ['AGENTS.md','README.md'] or p.startswith('docs/') for p in changed+new)
checks['frozenStagesAndArchiveUnchanged']=not any(re.match(r'docs/(?:10_|20_|30_|40_|60_|70_|archive/)',p) for p in changed+new)
checks['historicalSnapshotsUnchanged']=not any('/evidence/' in p and '/db-final-reconciliation-20261019/' not in p for p in changed)
checks['03OriginalThresholdsUnchanged']='docs/50_系统测试/03_系统测试计划.md' not in changed
checks['businessSourceUnchanged']=not git('diff','--name-only',HEAD,'--','backend/','frontend/').strip()
checks['currentPointer50']='- 正在做：`50_系统测试/`' in read(ROOT/'docs/00_总控.md')
checks['cyUserConfirmationPreserved']=all('金融' in read(BASE/p) for p in ['09_测试评估与报告.md','39_最终用例与缺陷处置台账.md'])
checks['diffWhitespaceClean']=subprocess.run(['git','diff','--check'],cwd=ROOT,capture_output=True).returncode==0
# Validate markdown links in newly created docs. Existing historical links are left to the global audit.
new_missing=[]
for rel in new:
 if not rel.endswith('.md'):continue
 p=ROOT/rel
 for m in re.finditer(r'(?<!!)\[[^\]]*\]\(([^)]+)\)',read(p)):
  target=m[1].split('#',1)[0].strip('<>')
  if target and not re.match(r'(?:https?:|mailto:)',target) and not (p.parent/target).exists():new_missing.append({'file':rel,'target':target})
checks['newMarkdownLinksResolve']=not new_missing
# Read-only governance audit. Overlay all modified tracked files and omit new db files for the baseline.
skill=Path('C:/Users/17841/.agents/skills/project-docs-governance/scripts/audit_project_docs.py')
spec=importlib.util.spec_from_file_location('db_document_audit',skill);mod=importlib.util.module_from_spec(spec);sys.modules[spec.name]=mod;spec.loader.exec_module(mod)
head_texts={p:git('show',HEAD+':'+p) for p in changed if p.endswith('.md')}
class BaselineAudit(mod.Audit):
 def collect(self):
  super().collect();self.texts.update(head_texts)
  for p in list(self.texts):
   if p in new or p.startswith('docs/50_系统测试/evidence/db-final-reconciliation-20261019/'):del self.texts[p]
def signature(audit,findings):
 result=Counter()
 for item in findings:
  lines=audit.texts.get(item.path,'').splitlines();content=lines[item.line-1].strip() if 0<item.line<=len(lines) else ''
  result[(item.severity,item.code,item.path,item.message,content)]+=1
 return result
current=mod.Audit(ROOT);cf=current.run();baseline=BaselineAudit(ROOT);bf=baseline.run();added=signature(current,cf)-signature(baseline,bf)
checks['auditComplete']=not current.incomplete and not baseline.incomplete
checks['noNewAuditFindings']=not added
# No assumption that the old commit is locally available.
old_obj=subprocess.run(['git','cat-file','-e','07f216f^{commit}'],cwd=ROOT,capture_output=True).returncode==0
result={'date':'2026-10-19','sourceHead':git('rev-parse',HEAD).strip(),'businessBaseline':'def6f90','runtimeLastObserved':'2026-10-18','checks':checks,'currentAuditFindings':len(cf),'baselineAuditFindings':len(bf),'newAuditFindings':[list(k)+[v] for k,v in added.items()],'globalAuditPassed':not cf,'globalAuditComplete':not current.incomplete,'historicalGitObject07f216fAvailable':old_obj,'newMissingLinks':new_missing,'businessTestsRun':0,'financialCommandsRun':0,'databaseQueriesRun':0,'browserInteractions':0,'serviceOperations':0,'networkRequests':0,'phaseExitApproved':False,'pushPerformed':False,'changedFiles':changed,'newTaskFiles':new}
(OUT/'document-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
# Only hash saved input sources and delivered text, never credentials or arbitrary output directories.
source_paths={x['definition']['path'] for x in cases}|{x['registration']['path'] for x in defs}|{x['path'] for x in reg.values()}|set(changed)
manifest={'date':'2026-10-19','businessBaseline':'def6f90','files':[{'path':rel,'sha256':hashlib.sha256((ROOT/rel).read_bytes()).hexdigest()} for rel in sorted(source_paths) if (ROOT/rel).is_file()]}
(OUT/'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'checks':checks,'auditCurrent':len(cf),'auditBaseline':len(bf),'newAuditFindings':result['newAuditFindings'],'missingLinks':new_missing},ensure_ascii=False,indent=2))
if not all(checks.values()):raise SystemExit(1)
