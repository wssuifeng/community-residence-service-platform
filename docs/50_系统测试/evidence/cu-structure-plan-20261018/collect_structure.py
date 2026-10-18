"""Collect static migration evidence only; no services, databases, or business APIs."""
from collections import Counter
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
DEST = Path(__file__).with_name('structure-analysis.json')
JAVA = ROOT / 'backend/src/main/java/com/community/residence'
FRONT = ROOT / 'frontend/src'
BASE = 'f58282cca0e8bb9c6660c76e6ff2acb560c6600a'

def read(p):
    return p.read_text(encoding='utf-8-sig')

def path(p):
    return p.relative_to(ROOT).as_posix()

def anchor(rel, needle):
    lines = [n for n, line in enumerate(read(ROOT / rel).splitlines(), 1) if needle in line]
    if not lines:
        raise ValueError(f'Missing anchor: {rel}: {needle}')
    return {'path': rel, 'needle': needle, 'lines': lines}

java = sorted(JAVA.rglob('*.java'))
vue = sorted(FRONT.rglob('*.vue'))
ts = [p for p in FRONT.rglob('*.ts') if '__tests__' not in p.parts
      and 'test' not in p.relative_to(FRONT).parts
      and not re.search(r'\.(spec|test)\.ts$', p.name)]
controllers = []
for p in java:
    if p.parent.name != 'controller':
        continue
    text = read(p)
    controllers.append({'path': path(p), 'module': p.relative_to(JAVA).parts[0],
        'requestMappings': re.findall(r'@RequestMapping\(([^\n]+)\)', text),
        'preAuthorizeExpressions': sorted(set(re.findall(r'@PreAuthorize\(([^\n]+)\)', text))),
        'methodMappingAnnotations': len(re.findall(r'@(?:Get|Post|Put|Patch|Delete)Mapping\b', text))})

outside_import_fqn = []
for p in java:
    lines = [i for i, line in enumerate(read(p).splitlines(), 1)
             if 'com.community.residence.' in line
             and not re.match(r'\s*(package|import)\s', line)]
    if lines:
        outside_import_fqn.append({'path': path(p), 'lines': lines})

path_sensitive_tests = []
for p in sorted(FRONT.rglob('*.ts')):
    if '__tests__' not in p.parts and not re.search(r'\.(spec|test)\.ts$', p.name):
        continue
    text = read(p)
    if ('readFileSync' in text or 'readFile(' in text) and any(s in text for s in ('views/', 'components/', 'api/', 'composables/', 'router/', 'utils/')):
        path_sensitive_tests.append(path(p))

anchors = [
    anchor('backend/src/main/java/com/community/residence/CommunityResidenceApplication.java', '@SpringBootApplication'),
    anchor('backend/src/main/java/com/community/residence/config/MybatisPlusConfig.java', 'new DataScopeInterceptor()'),
    anchor('backend/src/main/java/com/community/residence/log/aspect/OperationLogAspect.java', '@annotation'),
    anchor('backend/src/main/java/com/community/residence/config/RedisConfig.java', 'GenericJackson2JsonRedisSerializer'),
    anchor('frontend/src/components/conversation/ConversationBusinessActions.vue', "@/views/resident/HousingReservationView.vue"),
    anchor('frontend/src/store/user.ts', 'useNotificationStore'),
    anchor('frontend/src/store/permission.ts', 'residentRoutes'),
    anchor('frontend/src/router/index.ts', "from './admin'"),
]
result = {
    'date': '2026-10-18',
    'planningBaseCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
    'productionCodeBase': BASE,
    'productionCodeUnchangedSinceAssessment': subprocess.run(['git', 'diff', '--quiet', BASE, '--', 'backend', 'frontend'], cwd=ROOT).returncode == 0,
    'counts': {'productionJava': len(java), 'controllers': len(controllers), 'vue': len(vue), 'nonTestTs': len(ts),
        'mapperXmlInMainResources': len(list((ROOT / 'backend/src/main/resources').rglob('*.xml'))),
        'mapperAnnotatedJava': sum(bool(re.search(r'@Mapper\b', read(p))) for p in java),
        'mapperScanAnnotatedJava': sum('@MapperScan' in read(p) for p in java),
        'sourcePathSensitiveFrontendTests': len(path_sensitive_tests)},
    'javaByRootModule': dict(sorted(Counter(p.relative_to(JAVA).parts[0] for p in java).items())),
    'controllerDeclarationInventory': controllers,
    'fullyQualifiedReferencesOutsidePackageAndImport': outside_import_fqn,
    'sourcePathSensitiveFrontendTests': path_sensitive_tests,
    'anchors': anchors,
    'limits': [
        'Annotation declarations are static inventory, not Spring runtime handler or permission verification.',
        'Fully qualified reference hits include reflective string checks; not a Spring bean dependency graph.',
        'No complete target ownership per class or endpoint is claimed; that is implementation task S0.',
        'A configured Redis JSON serializer does not prove cached values contain old Java class names.',
        'No application tests, build, profiling, service, browser, database, or payment operations executed.'
    ]
}
DEST.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'counts': result['counts'], 'anchors': len(anchors),
                  'productionCodeUnchangedSinceAssessment': result['productionCodeUnchangedSinceAssessment'],
                  'fqnReferenceFiles': len(outside_import_fqn)}, ensure_ascii=False))
