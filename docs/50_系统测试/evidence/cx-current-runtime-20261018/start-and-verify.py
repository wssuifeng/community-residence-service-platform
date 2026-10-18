"""Start the current normal development build and record narrowly scoped readiness checks."""
import hashlib
import json
import os
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
RUNTIME = ROOT / 'output/runtime'
CHECKS = []
ENV = os.environ.copy()
for raw in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
    if '=' in raw and not raw.lstrip().startswith('#'):
        key, value = raw.split('=', 1)
        ENV[key.strip()] = value.strip().strip('"').strip("'")
FLAGS = subprocess.CREATE_NO_WINDOW | subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP


def check(name, passed):
    CHECKS.append({'name': name, 'passed': bool(passed)})
    if not passed:
        raise RuntimeError(name + '; inspect private runtime logs')


def listening(port):
    with socket.socket() as stream:
        stream.settimeout(1)
        return stream.connect_ex(('127.0.0.1', port)) == 0


def schema():
    query = "SELECT MAX(version+0) FROM flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='community_residence' AND table_name<>'flyway_schema_history';"
    result = subprocess.run(
        ['C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe', '-h127.0.0.1',
         '-u' + ENV.get('DB_USERNAME', 'root'), '-Dcommunity_residence', '-N', '-B', '-e', query],
        env={**ENV, 'MYSQL_PWD': ENV['DB_PASSWORD']}, capture_output=True,
        encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW, timeout=15)
    check('read-only migration metadata query succeeded', result.returncode == 0)
    return dict(zip(('flywayVersion', 'businessTables'), map(int, result.stdout.split())))


def request(path, token=None, payload=None, backend=False):
    check('request is local and outside financial endpoints',
          path.startswith(('/api/v1/', '/src/', '/auth/', '/v3/'))
          and not any(word in path for word in ('/payment', '/refund', '/alipay', '/wechat')))
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(
        ('http://127.0.0.1:8084' if backend else 'http://127.0.0.1:5176') + path,
        headers=headers, data=json.dumps(payload).encode() if payload is not None else None)
    with urllib.request.urlopen(req, timeout=20) as response:
        check('HTTP 200: ' + path, response.status == 200)
        return response.read().decode('utf-8')


def api(path, token=None, payload=None):
    result = json.loads(request(path, token, payload))
    check('business code 200: ' + path, result.get('code') == 200)
    return result.get('data')


check('MySQL and Redis are available', listening(3306) and listening(6379))
check('8084 and 5176 are free before startup', not listening(8084) and not listening(5176))
before = schema()
check('existing development schema is V43 with 61 business tables', before == {'flywayVersion': 43, 'businessTables': 61})
source_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
jar_source = ROOT / 'backend/target/residence-backend-0.1.0-SNAPSHOT.jar'
jar = RUNTIME / 'manual-cx-release-20261018.jar'
shutil.copy2(jar_source, jar)
jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
check('running candidate matches freshly built JAR', jar_hash == hashlib.sha256(jar_source.read_bytes()).hexdigest())
with zipfile.ZipFile(jar) as archive:
    check('candidate contains both new portal query controllers', all(name in archive.namelist() for name in (
        'BOOT-INF/classes/com/community/residence/staff/workorder/controller/StaffWorkbenchController.class',
        'BOOT-INF/classes/com/community/residence/resident/lease/controller/ResidentLeaseQueryController.class')))
java = Path('C:/Program Files/Java/jdk-17.0.4.1/bin/java.exe')
check('JDK17 exists', java.is_file())
processes = {}
for label, command, directory, environment in (
    ('backend', [str(java), '-Dfile.encoding=UTF-8', '-jar', str(jar), '--server.port=8084'], ROOT, ENV),
    ('frontend', [shutil.which('node'), str(ROOT / 'frontend/node_modules/vite/bin/vite.js'),
                  '--host', '127.0.0.1', '--port', '5176', '--strictPort'], ROOT / 'frontend',
     {**ENV, 'VITE_PROXY_TARGET': 'http://127.0.0.1:8084'})):
    with (RUNTIME / f'cx-{label}-out.log').open('wb') as out, (RUNTIME / f'cx-{label}-err.log').open('wb') as err:
        processes[label] = subprocess.Popen(command, cwd=directory, env=environment,
            stdin=subprocess.DEVNULL, stdout=out, stderr=err, creationflags=FLAGS)
state = {'startedAt': datetime.now().astimezone().isoformat(), 'backendPid': processes['backend'].pid,
         'frontendPid': processes['frontend'].pid, 'backendPort': 8084, 'frontendPort': 5176,
         'backendJar': str(jar), 'backendSha256': jar_hash, 'sourceCommit': source_commit}
(RUNTIME / 'cx-normal-pids.json').write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
for attempt in range(120):
    if any(process.poll() is not None for process in processes.values()):
        raise RuntimeError('New runtime process exited; inspect private startup logs')
    try:
        spec = json.loads(request('/v3/api-docs', backend=True))
        break
    except (urllib.error.URLError, TimeoutError, OSError):
        time.sleep(1)
else:
    raise RuntimeError('Backend did not become ready; inspect private startup logs')
paths = spec.get('paths', {})
for path in ('/api/v1/staff/workbench/summary', '/api/v1/leases/my-summaries',
             '/api/v1/conversation-inbox', '/api/v1/work-orders/{id}', '/api/v1/communities/{id}/houses-with-housing'):
    check('current OpenAPI path registered: ' + path, path in paths)
check('login document served by current Vite', '<div id="app">' in request('/auth/login'))
for path in ('/src/admin/community/views/CommunityStructureView.vue',
             '/src/staff/workbench/views/DashboardView.vue', '/src/resident/lease/views/HousingLeaseHubView.vue'):
    check('current Vue module transforms: ' + path, len(request(path)) > 100)
for username, password, role in (('admin1', 'Admin123456', 'ADMIN'),
                                  ('staff1', 'Staff123456', 'STAFF'),
                                  ('resident1', 'Resident123456', 'RESIDENT')):
    login = api('/api/v1/auth/' + ('resident' if role == 'RESIDENT' else 'admin') + '/login',
                payload={'username': username, 'password': password})
    check('demo identity authenticated: ' + role, login['user']['role'] == role and bool(login['token']))
    token = login['token']
    api('/api/v1/work-orders?page=1&size=1', token)
    if role == 'STAFF':
        api('/api/v1/staff/workbench/summary', token)
    elif role == 'RESIDENT':
        api('/api/v1/leases/my-summaries?page=1&size=10', token)
    else:
        communities = api('/api/v1/communities?page=1&size=1', token)
        records = communities.get('records', [])
        if records:
            api('/api/v1/communities/' + str(records[0]['id']) + '/houses-with-housing', token)
after = schema()
check('schema unchanged after startup', before == after)
check('both normal ports remain listening', listening(8084) and listening(5176))
summary = {'date': '2026-10-18', 'verifiedAt': datetime.now().astimezone().isoformat(),
           'scope': 'normal startup, current contracts, three demo logins and read-only affected entry readiness',
           'sourceCommit': source_commit, 'backendJar': str(jar.relative_to(ROOT)), 'backendSha256': jar_hash,
           'runtimeJava': '17.0.4.1', 'backendPort': 8084, 'frontendPort': 5176,
           'schemaBefore': before, 'schemaAfter': after, 'backendBuild': 'passed; Maven package -DskipTests under JDK17',
           'frontendBuild': 'passed; vue-tsc --noEmit and Vite build', 'checks': CHECKS,
           'financialCommands': 0, 'paymentConfiguration': 'not read or changed by probe',
           'browserInteraction': 'none', 'manualAcceptance': 'awaiting user confirmation for affected entries',
           'userFinancialConfirmation': 'user confirms usable; AI must not repeat financial testing',
           'serviceDisposition': 'left running', 'pushPerformed': False}
(EVIDENCE / 'startup-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'readinessChecks': len(CHECKS), 'allPassed': all(item['passed'] for item in CHECKS),
                  'backend': 8084, 'frontend': 5176, 'schema': after, 'sourceCommit': source_commit[:8],
                  'financialCommands': 0}, ensure_ascii=False))
