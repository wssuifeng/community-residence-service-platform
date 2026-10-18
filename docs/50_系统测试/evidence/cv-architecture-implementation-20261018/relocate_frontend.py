"""Two safe relocation batches; no business service or live data access."""
from pathlib import Path
import csv, importlib.util, json, re, sys
ROOT = Path(__file__).resolve().parents[4]
FE = ROOT / 'frontend/src'
OUT = Path(__file__).parent
spec = importlib.util.spec_from_file_location('inventory', OUT / 'migration_inventory.py')
inv = importlib.util.module_from_spec(spec); spec.loader.exec_module(inv)
stage = sys.argv[1]
assert stage in ('common', 'portals')

def destination(p):
    parts = p.relative_to(FE).parts
    n = p.stem.removesuffix('.spec')
    if parts[0] in ('common','admin','resident','staff') or parts[0] in ('test','__tests__','main.ts','App.vue','env.d.ts'): return p
    if parts[0] == 'store' and n == 'adminTodo': return FE / 'admin/workbench/store' / p.name
    if parts[0] == 'store' and '__tests__' in parts:
        return FE / 'common/messaging/store/__tests__' / p.name
    if parts[0] == 'utils':
        base = re.sub(r'^request.*$', 'request', n)
        module = inv.util_modules.get(base, 'web')
        if module == 'utils': module = 'web'
        return FE / 'common' / module / 'utils' / Path(*parts[1:])
    if parts[0] == 'composables' and len(parts) > 1 and parts[1] == 'housing':
        return FE / 'common/housing/composables' / Path(*parts[2:])
    if parts[0] == 'components' and parts[1] == 'housing':
        return FE / 'common/housing/components' / Path(*parts[2:])
    if parts[0] == 'views' and parts[1] in ('admin','resident','staff'):
        portal = parts[1]; rest = list(parts[2:])
        if n.endswith('Layout') or n == 'AdminPageHeader': return FE / portal / 'layout/views' / Path(*rest)
        module = rest.pop(0) if rest[0] != '__tests__' and len(rest)>1 else inv.view_module(n)
        if n in ('StaffWorkloadDashboard',): module = 'workbench'
        if n in ('RentMonthSelection','TenancyPrerequisites'): module = 'lease'
        kind = 'views' if n.endswith('View') or n in ('DashboardOverview','DashboardTabView','StaffWorkloadDashboard') else 'components'
        if p.suffix == '.ts' and not p.stem.endswith('.spec'): kind = 'composables' if n.startswith('use') else 'utils'
        return FE / portal / module / kind / Path(*rest)
    return inv.frontend_target(p)

files = sorted(p for p in FE.rglob('*') if p.is_file())
mapping = {}
for p in files:
    t = destination(p)
    # Root route and portal files belong to the second batch; common can update their imports.
    if stage == 'common' and (t.relative_to(FE).parts[0] != 'common' or p.parent.name == 'router'):
        t = p
    if p != t: mapping[p.resolve()] = t.resolve()
assert len(set(mapping.values())) == len(mapping)
assert all(p.is_relative_to(FE.resolve()) and t.is_relative_to(FE.resolve()) for p,t in mapping.items())
assert all(not t.exists() or t == p for p,t in mapping.items())
all_files = {p.resolve(): p.read_text(encoding='utf-8-sig') for p in files}

def resolve_import(source, value):
    if value.startswith('@/'): base = FE / value[2:]
    elif value.startswith(('./','../')): base = source.parent / value
    else: return None
    base = base.resolve()
    for candidate in (base, Path(str(base)+'.ts'), Path(str(base)+'.vue'), Path(str(base)+'.css'), Path(str(base)+'.json'), base/'index.ts'):
        if candidate in all_files: return candidate
    return None

changed = []
for p,s in all_files.items():
    t = mapping.get(p,p)
    def rewrite(m):
        value = m.group(2)
        resolved = resolve_import(p,value)
        if resolved is None: return m.group(0)
        target = mapping.get(resolved,resolved)
        if target == resolved and t == p: return m.group(0)
        value_new = '@/' + target.relative_to(FE.resolve()).as_posix()
        if not Path(value).suffix and value_new.endswith('.ts'): value_new = value_new[:-3]
        return m.group(1) + value_new + m.group(1)
    rewritten = re.sub(r"(['\"])(@/[^'\"\n]+|\.{1,2}/[^'\"\n]+)\1", rewrite, s)
    for old,new in mapping.items():
        rewritten = rewritten.replace(old.relative_to(ROOT).as_posix(),new.relative_to(ROOT).as_posix())
        rewritten = rewritten.replace(old.relative_to(FE.parent).as_posix(),new.relative_to(FE.parent).as_posix())
    if p.name == 'housePhoto.spec.ts':
        for old,new in mapping.items():
            if old.is_relative_to(FE/'views'):
                rewritten = rewritten.replace("'"+old.relative_to(FE/'views').as_posix()+"'", "'"+new.relative_to(FE).as_posix()+"'")
                rewritten = rewritten.replace("'"+old.relative_to(FE).as_posix()+"'", "'"+new.relative_to(FE).as_posix()+"'")
        if stage == 'portals': rewritten = rewritten.replace("'src/views'", "'src'")
    if rewritten != s or t != p:
        t.parent.mkdir(parents=True,exist_ok=True)
        t.write_text(rewritten,encoding='utf-8')
        changed.append({'source':p.relative_to(ROOT).as_posix(),'target':t.relative_to(ROOT).as_posix()})
for p,t in mapping.items(): p.unlink()
for p in sorted(FE.rglob('*'),key=lambda p:len(p.parts),reverse=True):
    if p.is_dir() and p.resolve().is_relative_to(FE.resolve()) and not any(p.iterdir()): p.rmdir()
(OUT / ('s5-'+stage+'-relocation.json')).write_text(json.dumps(changed,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'stage':stage,'moved':len(mapping),'affected':len(changed)}))
