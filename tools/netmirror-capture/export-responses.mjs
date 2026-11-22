// Turns a sanitized capture (cdp-capture/authenticated-*.json) into one JSON document of
// endpoint -> request + response, parsing JSON bodies so they read as JSON, not strings.
// Usage: node export-responses.mjs [capture.json] [out.json]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.join(here, 'cdp-capture');
const src = process.argv[2] || path.join(dir, fs.readdirSync(dir).filter(f => /^authenticated-.*\.json$/.test(f)).sort().pop());
const dst = process.argv[3] || path.join(dir, 'api-responses.json');

const cap = JSON.parse(fs.readFileSync(src, 'utf8'));
const parse = s => { try { return JSON.parse(s); } catch { return undefined; } };

const responses = cap.results.map(r => {
  const body = r.body ?? '';
  const json = parse(body);
  const entry = {
    name: r.name,
    request: r.method ? { method: r.method, url: r.u, ...(r.form ? { form: r.form } : {}) } : undefined,
    status: r.s ?? null,
    contentType: r.ct ?? null,
    length: r.len ?? null,
  };
  if (r.err) entry.error = r.err;
  else if (json !== undefined) entry.json = json;
  else entry.text = body;
  // the capture keeps a prefix of long bodies; say so instead of passing it off as whole
  if (r.len != null && body.length < r.len) entry.truncated = `first ${body.length} of ${r.len} chars`;
  return entry;
});

fs.writeFileSync(dst, JSON.stringify({ captured: cap.captured, id: cap.id, query: cap.query, note: cap.note, responses }, null, 2));
console.log(dst);
