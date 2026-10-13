/* 统计真实构建产物及入口静态依赖，不把分包当作P95性能实测。 */
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const root = path.resolve(__dirname, '../..');
const dist = path.join(root, 'frontend/dist');
const out = process.argv[2];
if (!out) throw new Error('Usage: node frontend-bundle-snapshot.cjs <output-json>');
const files = fs.readdirSync(path.join(dist, 'assets')).filter(name => name.endsWith('.js')).map(name => {
  const bytes = fs.readFileSync(path.join(dist, 'assets', name));
  return { file: `assets/${name}`, bytes: bytes.length, gzipBytes: zlib.gzipSync(bytes).length };
}).sort((a, b) => b.bytes - a.bytes);
const html = fs.readFileSync(path.join(dist, 'index.html'), 'utf8');
const entry = html.match(/<script[^>]*src="([^"]+)"/)[1].replace(/^\//, '');
const reachable = new Set();
function visit(file) {
  if (reachable.has(file)) return;
  reachable.add(file);
  const source = fs.readFileSync(path.join(dist, file), 'utf8');
  for (const match of source.matchAll(/(?:from\s*|import\s*)["'](\.\/[^"']+\.js)["']/g)) {
    visit(path.posix.join(path.posix.dirname(file), match[1]));
  }
}
visit(entry);
const initial = files.filter(file => reachable.has(file.file));
const sum = (rows, key) => rows.reduce((a, b) => a + b[key], 0);
const result = { timestamp: new Date().toISOString(), entry, entryBytes: files.find(f => f.file === entry).bytes,
  totalJsBytes: sum(files, 'bytes'), totalJsGzipBytes: sum(files, 'gzipBytes'), chunkCount: files.length,
  maxChunk: files[0], above500KB: files.filter(f => f.bytes > 500000),
  initialStaticJsBytes: sum(initial, 'bytes'), initialStaticJsGzipBytes: sum(initial, 'gzipBytes'), initialFiles: initial, chunks: files };
fs.writeFileSync(path.resolve(root, out), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ chunkCount: result.chunkCount, maxChunk: result.maxChunk, entryBytes: result.entryBytes,
  totalJsGzipBytes: result.totalJsGzipBytes, initialStaticJsGzipBytes: result.initialStaticJsGzipBytes }));
