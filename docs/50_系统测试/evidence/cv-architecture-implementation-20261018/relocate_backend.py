"""Apply the reviewed S1 relocation only; references and package declarations follow the inventory."""
from pathlib import Path
import csv,importlib.util,re,json
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).parent
spec=importlib.util.spec_from_file_location('inventory',OUT/'migration_inventory.py')
inv=importlib.util.module_from_spec(spec);spec.loader.exec_module(inv)
rows=list(csv.DictReader((OUT/'backend-ownership.csv').open(encoding='utf-8')))
paths={ROOT/r['source']:ROOT/r['transitionTarget'] for r in rows}
classmap={};packageclasses={}
for src,dst in paths.items():
 text=inv.read(src);pkg=re.search(r'\bpackage\s+([\w.]+);',text)[1]
 newpkg='.'.join(dst.parent.relative_to(ROOT/'backend/src/main/java').parts)
 classmap[pkg+'.'+src.stem]=newpkg+'.'+dst.stem
 packageclasses.setdefault(pkg,[]).append((src.stem,newpkg+'.'+dst.stem))
pattern=re.compile('|'.join(re.escape(k) for k in sorted(classmap,key=len,reverse=True)))
allfiles=sorted((ROOT/'backend/src').rglob('*.java'))
changed=[]
for src in allfiles:
 text=inv.read(src)
 oldpkg=re.search(r'\bpackage\s+([\w.]+);',text)[1]
 if src in paths:dst=paths[src]
 elif src.is_relative_to(ROOT/'backend/src/test/java/com/community/residence'):
  suffix=src.relative_to(ROOT/'backend/src/test/java/com/community/residence')
  if suffix.parts[0]=='architecture':dst=src
  else:dst=ROOT/'backend/src/test/java'/inv.backend_target(inv.BE/suffix).relative_to(ROOT/'backend/src/main/java')
 else:dst=src
 assert dst.resolve().is_relative_to(ROOT.resolve())
 newpkg='.'.join(dst.parent.relative_to(ROOT/('backend/src/test/java' if '/src/test/' in src.as_posix() else 'backend/src/main/java')).parts)
 # Expand old wildcard imports because old common.constant now has several owners.
 def expand(m):
  return '\n'.join('import '+fq+';' for _,fq in packageclasses.get(m[1],[])) if m[1] in packageclasses else m[0]
 text=re.sub(r'import\s+([\w.]+)\.\*;',expand,text)
 additions=[]
 for name,fq in packageclasses.get(oldpkg,[]):
  if name!=src.stem and fq.rsplit('.',1)[0]!=newpkg and re.search(r'\b'+name+r'\b',text):
   if 'import '+fq+';' not in text:additions.append('import '+fq+';')
 text=pattern.sub(lambda m:classmap[m[0]],text)
 # Rewrite literal old source paths used in existing guards, leaving historical evidence intact.
 for a,b in paths.items():
  text=text.replace(a.relative_to(ROOT/'backend').as_posix(),b.relative_to(ROOT/'backend').as_posix())
 text=re.sub(r'\bpackage\s+[\w.]+;', 'package '+newpkg+';',text,count=1)
 if additions:text=text.replace('package '+newpkg+';','package '+newpkg+';\n\n'+'\n'.join(sorted(set(additions))),1)
 if text!=inv.read(src) or dst!=src:
  dst.parent.mkdir(parents=True,exist_ok=True)
  if dst!=src:assert not dst.exists(),dst
  dst.write_text(text,encoding='utf-8')
  if dst!=src:src.unlink()
  changed.append({'source':inv.rel(src),'target':inv.rel(dst)})
(OUT/'s1-relocation.json').write_text(json.dumps(changed,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Relocated/updated',len(changed),'Java sources and tests')
