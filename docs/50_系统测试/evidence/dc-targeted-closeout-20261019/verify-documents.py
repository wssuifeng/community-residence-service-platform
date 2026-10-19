"""只读核对本批交付，比较caae28d文档审计；不访问应用或数据库。"""
from pathlib import Path
from collections import Counter
import ast,hashlib,importlib.util,json,re,subprocess,sys
OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[3];HEAD='caae28d';prefix=OUT.relative_to(ROOT).as_posix()
def read(p):return p.read_text('utf-8-sig')
def load(p):return json.loads(read(p))
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT).decode('utf-8-sig')
basefiles=set(git('ls-tree','-r','--name-only','-z',HEAD).split('\0'))
diffpaths=[x for x in git('diff','--name-only','-z',HEAD).split('\0') if x]
changed=[x for x in diffpaths if x in basefiles]
new=[x for x in git('ls-files','--others','--exclude-standard','-z').split('\0') if x and (x.startswith(prefix+'/') or x.startswith('backend/src/') and x.endswith(('RedisFailures.java','RedisFailuresTest.java','TokenBlacklistRedisOutageTest.java','NotificationSequenceRecoveryTest.java')))]
new=sorted(set(new+[x for x in diffpaths if x not in basefiles]));allfiles=sorted(set(changed+new));cases=load(OUT/'case-supplement.json');defs=load(OUT/'defect-supplement.json');summary=load(OUT/'summary.json')
checks={'unique305TC':len(cases['rows'])==len({x['id'] for x in cases['rows']})==305,'contiguous215DEF':{r['id'] for r in defs['rows']}=={f'DEF-{n:03}' for n in range(1,216)},'23SupplementedTC':sum(r['currentSupplement'] is not None for r in cases['rows'])==23,'18OriginalDispositions':defs['original18Disposed']==18 and defs['nonFinancialTargeted']==13 and defs['financialEvidenceAdopted']==5,'frozenStagesAndArchiveUnchanged':not any(re.match(r'docs/(?:10_|20_|30_|40_|60_|70_|archive/)',p) for p in changed),'historicalEvidenceUnchanged':not any('/evidence/' in p and not p.startswith(prefix+'/') for p in changed),'03ThresholdsUnchanged':'docs/50_系统测试/03_系统测试计划.md' not in changed,'pointerStill50':'- 正在做：`50_系统测试/`' in read(ROOT/'docs/00_总控.md'),'financialCommandsZero':summary['financialCommands']==0,'normalProcessesPreserved':load(OUT/'environment-after.json')['normalProcessesPreserved'],'ownProcessesStopped':load(OUT/'cleanup.json')['ownProcessesStopped'],'phaseExitUnsigned':not summary['phaseExitApproved']}
checks['backendTargetedPassed']=load(OUT/'backend-targeted.json')['failures']==load(OUT/'backend-targeted.json')['errors']==0
checks['frontendTargetedPassed']=load(OUT/'frontend-targeted.json')['failed']==0
perf=load(OUT/'performance.json');checks['latestPerformancePassed']=perf['allP95Within2s'] and perf['functionalErrors']==0 and perf['allPushWithin3s']
checks['newPythonSourcesParse']=True
for f in OUT.glob('*.py'):ast.parse(read(f),filename=str(f))
missing=[]
pattern=r'(?<!!)\[[^\]]*\]\(([^)]+)\)'
for rel in allfiles:
 if not rel.endswith('.md'):continue
 f=ROOT/rel;old_text=git('show',HEAD+':'+rel) if rel in changed else ''
 old_links=set(re.findall(pattern,old_text))
 for target in re.findall(pattern,read(f)):
  target=target.strip('<>').split('#')[0]
  if not target or target in old_links or re.match(r'[a-zA-Z]+:',target):continue
  if not (f.parent/target).resolve().exists():missing.append({'file':rel,'target':target})
checks['newMarkdownLinksResolve']=not missing
scan=[]
for rel in allfiles:
 f=ROOT/rel
 if f.suffix not in ('.java','.md','.py','.json','.jmx','.csv'):continue
 text=read(f)
 if re.search(r'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{15,}|sk-[A-Za-z0-9]{25,}',text):scan.append(rel)
checks['noCredentialPatterns']=not scan
checks['diffWhitespaceClean']=all(subprocess.run(['git','diff',*flags,'--check'],cwd=ROOT,capture_output=True).returncode==0 for flags in [[],['--cached']])
# Use the governance audit with a baseline overlay, leaving all historical files on disk.
skill=Path('C:/Users/17841/.agents/skills/project-docs-governance/scripts/audit_project_docs.py');spec=importlib.util.spec_from_file_location('dc_audit',skill);mod=importlib.util.module_from_spec(spec);sys.modules[spec.name]=mod;spec.loader.exec_module(mod)
head_texts={rel:git('show',HEAD+':'+rel) for rel in changed if rel.endswith('.md')}
class BaselineAudit(mod.Audit):
 def collect(self):
  super().collect();self.texts.update(head_texts)
  for p in list(self.texts):
   if p.startswith(prefix+'/'):del self.texts[p]
def signature(audit,findings):
 out=Counter()
 for item in findings:
  lines=audit.texts.get(item.path,'').splitlines();content=lines[item.line-1].strip() if 0<item.line<=len(lines) else ''
  out[(item.severity,item.code,item.path,item.message,content)]+=1
 return out
current=mod.Audit(ROOT);cf=current.run();baseline=BaselineAudit(ROOT);bf=baseline.run();added=signature(current,cf)-signature(baseline,bf)
checks['auditComplete']=not current.incomplete and not baseline.incomplete;checks['noNewAuditFindings']=not added
result={'date':'2026-10-19','sourceBase':git('rev-parse',HEAD).strip(),'checks':checks,'currentAuditFindings':len(cf),'baselineAuditFindings':len(bf),'newAuditFindings':[list(k)+[v] for k,v in added.items()],'globalAuditPassed':not cf,'globalAuditComplete':not current.incomplete,'newMissingLinks':missing,'credentialPatternFiles':scan,'changedFiles':changed,'newTaskFiles':new,'phaseExitApproved':False,'pushPerformed':False}
(OUT/'document-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
manifest={'sourceBase':git('rev-parse',HEAD).strip(),'files':[{'path':rel,'sha256':hashlib.sha256((ROOT/rel).read_bytes()).hexdigest()} for rel in allfiles if (ROOT/rel).is_file() and not rel.endswith(('document-verification.json','source-manifest.json'))]};(OUT/'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps({'checks':checks,'currentFindings':len(cf),'baselineFindings':len(bf),'newFindings':result['newAuditFindings'],'missingLinks':missing,'credentialPatternFiles':scan},ensure_ascii=False,indent=2))
if not all(checks.values()):raise SystemExit(1)
