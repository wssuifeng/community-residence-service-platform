"""只读核对de文档与来源；不运行应用、不访问数据库或渠道。"""
from pathlib import Path
from collections import Counter
import ast,hashlib,importlib.util,json,re,subprocess,sys
OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[3];BASE='441f295';PREFIX=OUT.relative_to(ROOT).as_posix()
def text(p):return p.read_text('utf-8-sig')
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT).decode('utf-8-sig')
basefiles=set(git('ls-tree','-r','--name-only','-z',BASE).split('\0'))
paths=[p for p in git('diff','--name-only','-z',BASE).split('\0') if p]
changed=[p for p in paths if p in basefiles]
new=sorted(set([p for p in paths if p not in basefiles]+[p for p in git('ls-files','--others','--exclude-standard','-z').split('\0') if p and (p.startswith(PREFIX+'/') or p=='docs/50_系统测试/40_并发与Redis故障复核.md')]))
files=sorted(set(changed+new));review=json.loads(text(OUT/'review.json'))
checks={'noProductCodeChanges':not any(p.startswith(('backend/','frontend/')) for p in paths),'frozenStagesAndArchiveUnchanged':not any(re.match(r'docs/(10_|20_|30_|40_|60_|70_|archive/)',p) for p in paths),'priorEvidenceUnchanged':not any('/evidence/' in p and not p.startswith(PREFIX+'/') for p in paths),'03Unchanged':'docs/50_系统测试/03_系统测试计划.md' not in paths,'pointerStill50':'- 正在做：`50_系统测试/`' in text(ROOT/'docs/00_总控.md'),'twoConfirmedOpenDefects':[x['id'] for x in review['confirmedOpen']]==['DEF-216','DEF-217'] and all(x['status']=='打开' for x in review['confirmedOpen']),'fourUnverifiedRisks':len(review['riskCandidates'])==4,'registry217':json.loads(text(ROOT/'docs/50_系统测试/evidence/dc-targeted-closeout-20261019/defect-supplement.json'))['defectCount']+len(review['confirmedOpen'])==217,'sourceHashesMatch':all(hashlib.sha256((ROOT/r['path']).read_bytes()).hexdigest()==r['sha256'] for r in review['sources']),'financialRequestsZero':review['financialRequests']==0,'noRuntimeChangesOrNewTestClaim':review['sourceMutations']==False and review['wholeProjectConcurrencySafetyProven']==False}
# Self-report placeholders let the first link audit inspect the final artifact locations.
for name in ['verification.json','source-manifest.json']:
 p=OUT/name
 if not p.exists():p.write_text('{"verificationPending": true}\n','utf-8')
for p in OUT.glob('*.py'):ast.parse(text(p),filename=str(p))
checks['auditSourcesParse']=True
checks['diffWhitespaceClean']=all(subprocess.run(['git','diff',*flags,'--check'],cwd=ROOT,capture_output=True).returncode==0 for flags in [[],['--cached']])
missing=[];credential=[];link=r'(?<!!)\[[^\]]*\]\(([^)]+)\)'
for rel in files:
 p=ROOT/rel
 if p.suffix=='.md':
  old=git('show',BASE+':'+rel) if rel in changed else '';prior=set(re.findall(link,old))
  for target in re.findall(link,text(p)):
   t=target.strip('<>').split('#')[0]
   if t and target not in prior and not re.match('[A-Za-z]+:',t) and not (p.parent/t).resolve().exists():missing.append({'file':rel,'target':target})
 if p.suffix in ['.md','.py','.json'] and re.search(r'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{15,}|sk-[A-Za-z0-9]{25,}',text(p)):credential.append(rel)
checks['newLinksResolve']=not missing;checks['noCredentialPatterns']=not credential
skill=Path('C:/Users/17841/.agents/skills/project-docs-governance/scripts/audit_project_docs.py');spec=importlib.util.spec_from_file_location('de_audit',skill);mod=importlib.util.module_from_spec(spec);sys.modules[spec.name]=mod;spec.loader.exec_module(mod)
oldtexts={p:git('show',BASE+':'+p) for p in changed if p.endswith('.md')}
class PriorAudit(mod.Audit):
 def collect(self):
  super().collect();self.texts.update(oldtexts)
  for p in list(self.texts):
   if p.startswith(PREFIX+'/') or p=='docs/50_系统测试/40_并发与Redis故障复核.md':del self.texts[p]
def signature(a,findings):
 c=Counter()
 for item in findings:
  lines=a.texts.get(item.path,'').splitlines();line=lines[item.line-1].strip() if 0<item.line<=len(lines) else '';c[(item.severity,item.code,item.path,item.message,line)]+=1
 return c
cur=mod.Audit(ROOT);cf=cur.run();old=PriorAudit(ROOT);bf=old.run();added=signature(cur,cf)-signature(old,bf)
checks['governanceAuditComplete']=not cur.incomplete and not old.incomplete;checks['noNewAuditFindings']=not added
result={'sourceBase':git('rev-parse',BASE).strip(),'checks':checks,'currentAuditFindings':len(cf),'baselineAuditFindings':len(bf),'globalAuditPassed':not cf,'newAuditFindings':[list(k)+[v] for k,v in added.items()],'missingLinks':missing,'credentialPatternFiles':credential,'changedFiles':changed,'newTaskFiles':new,'productTestsExecuted':False,'normalEnvironmentOperated':False,'phaseExitApproved':False,'pushPerformed':False}
(OUT/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
# Include the self-report paths even on the first run, while excluding their self-hashes.
files=sorted(set(files+[PREFIX+'/verification.json',PREFIX+'/source-manifest.json']))
manifest={'sourceBase':result['sourceBase'],'files':[{'path':p,'sha256':hashlib.sha256((ROOT/p).read_bytes()).hexdigest()} for p in files if not p.endswith(('verification.json','source-manifest.json'))]};(OUT/'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps({'checks':checks,'currentFindings':len(cf),'baselineFindings':len(bf),'newFindings':result['newAuditFindings'],'missingLinks':missing},ensure_ascii=False,indent=2))
if not all(checks.values()):raise SystemExit(1)
