import fs from 'node:fs';
// Collection path: first CLI arg, or COLLECTION env, else the original Downloads copy.
const P = process.argv[2] || process.env.COLLECTION ||
  'C:/Users/bharathkumar/Downloads/NetMirror API (reverse-engineered).postman_collection.json';
if (!fs.existsSync(P)) { console.error(`Collection not found: ${P}\nPass a path: node update_collection.mjs <collection.json>`); process.exit(1); }
const c = JSON.parse(fs.readFileSync(P, 'utf8'));

// ---- Verified authenticated bodies captured 2026-10-23 via CDP browser-based capture ----
// Secrets replaced with placeholders: {{t}} session token, {{play_token}} HLS in= token.
const BODIES = {
  'check.php': `{"token_hash":"aHR0cHM6Ly9uZXQ1Mi5jYy9tb2JpbGUvaG9tZT9hcHA9MQ==","doms":"false","mwin":"true","popwin":"true","var":"1.0","stape":"aHR0cHM6Ly9uZXQ1Mi5jYy9zdHJlYW10YXBlLnBocA==","u":["aHR0cHM6Ly9zdHJlYW10YXBlLmNvbQ==","aHR0cHM6Ly9zdHJlYW10YXBlLm5ldA==","aHR0cHM6Ly9zdHJlYW10YXBlLnRv","aHR0cHM6Ly9zdGFwZS5mdW4="]}`,
  'verify2.php': `{"c":"y","statusup":"Waiting for your ads click."}`,
  'post.php': `{"status":"y","d_lang":"eng","title":"Vishwanath & Sons","year":"2026","ua":"U/A 16+","match":"99% match","runtime":"2h 40m","hdsd":"HD","type":"m","creator":"","director":"Venky Atluri","writer":"Venky Atluri","short_cast":"Suriya, Mamitha Baiju, Radikaa Sarathkumar, Raveena Tandon, Nassar, Rajiv Menon, Sudha, Sunil Reddy, Harsha Chemudu, Raghu Babu, Hyper Aadi, Harika","cast":"Suriya, Mamitha Baiju, Radikaa Sarathkumar, Raveena Tandon, Nassar, Rajiv Menon, Sudha, Sunil Reddy, Harsha Chemudu, Raghu Babu, Hyper Aadi, Harika","genre":"Drama Movies, Romantic Movies, Telugu-Language Movies","thismovieis":"Sentimental, Heartfelt, Drama, Parenthood, Indian, Blockbuster, Romantic, Opposites-Attract, Movie","m_desc":"Suitable for persons aged 16 and above and under parental guidance for people under age of 16","m_reason":"mature themes","desc":"A celebrated Olympian shooter travels to America looking for a donor to help his sick child — but unexpected romance complicates his search.","oin":"","resume":"","lang":[{"l":"Hindi","s":"hin"},{"l":"Kannada","s":"kan"},{"l":"Malayalam","s":"mal"},{"l":"Tamil","s":"tam"},{"l":"Telugu","s":"tel"}],"episodes":[null],"suggest":[{"id":"82757039"},{"id":"70155618"},{"id":"81056195"},{"id":"81002412"},{"id":"81435227"},{"id":"80184100"},{"id":"70131764"},{"id":"81555635"},{"id":"81626817"},{"id":"81662957"},{"id":"80216541"},{"id":"60036752"}],"error":null}`,
  'search.php': `{"status":"y","head":"Movies & TV ..","type":0,"searchResult":[{"id":82034837,"t":"Vishwanath & Sons"}],"error":""}`,
  'playlist.php': `[{
  "image2": "https://imgcdn.kim/poster/1920/82034837.jpg",
  "sources": [
    {"file": "/mobile/hls/82034837.m3u8?in={{play_token}}&hd=off&lang=eng&hp=yes", "label": "Auto", "type": "application/vnd.apple.mpegurl"},
    {"file": "/mobile/hls/82034837.m3u8?q=720p&in={{play_token}}&lang=eng", "label": "Mid HD", "type": "application/vnd.apple.mpegurl", "default": "true"},
    {"file": "/mobile/hls/82034837.m3u8?q=480p&in={{play_token}}&lang=eng", "label": "Low HD", "type": "application/vnd.apple.mpegurl"}
  ],
  "tracks": [
    {"kind": "captions", "file": "//subscdn.top/files/82034837/82034837-en.srt", "label": "English"},
    {"kind": "thumbnails", "file": "https://imgcdn.kim/nfplayer/back05.nfmirrorcdn.top/vtt/82034837.vtt", "label": "English"}
  ]
}]`,
  'hls/': `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="hin",NAME="1. Hindi",AUTOSELECT=NO,DEFAULT=NO,URI="https://s88.nm-cdn30.top/s22.freecdn41.top/files/82034837/a/0/0.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="kan",NAME="2. Kannada",AUTOSELECT=NO,DEFAULT=NO,URI="https://s88.nm-cdn30.top/s22.freecdn41.top/files/82034837/a/1/1.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="mal",NAME="3. Malayalam",AUTOSELECT=NO,DEFAULT=NO,URI="https://s88.nm-cdn30.top/s22.freecdn41.top/files/82034837/a/2/2.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="tam",NAME="4. Tamil",AUTOSELECT=NO,DEFAULT=NO,URI="https://s88.nm-cdn30.top/s22.freecdn41.top/files/82034837/a/3/3.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="tel",NAME="5. Telugu",AUTOSELECT=NO,DEFAULT=NO,URI="https://s88.nm-cdn30.top/s22.freecdn41.top/files/82034837/a/4/4.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO="aac",RESOLUTION=1280x720,CLOSED-CAPTIONS=NONE
https://s88.nm-cdn30.top/s22.freecdn41.top/files/82034837/720p/720p.m3u8?in={{play_token}}
#EXT-X-STREAM-INF:BANDWIDTH=400000,AUDIO="aac",RESOLUTION=854x480,CLOSED-CAPTIONS=NONE
https://s88.nm-cdn30.top/s22.freecdn41.top/files/82034837/480p/480p.m3u8?in={{play_token}}`,
  'language.php': `{ "lang":1 }`,
};

const PREVIEW = { 'hls/': 'text', 'check.php': 'json', 'verify2.php': 'json', 'post.php': 'json',
  'search.php': 'json', 'playlist.php': 'json', 'language.php': 'json' };

function mkExample(name, body, lang, mime) {
  return {
    name,
    status: 'OK', code: 200,
    _postman_previewlanguage: lang,
    header: [{ key: 'Content-Type', value: mime }],
    cookie: [],
    body
  };
}

let patched = [];
function walk(items) {
  for (const it of items) {
    if (it.item) { walk(it.item); continue; }
    if (!it.request) continue;
    const u = typeof it.request.url === 'string' ? it.request.url : (it.request.url.raw || '');
    for (const key of Object.keys(BODIES)) {
      if (!u.includes(key)) continue;
      // verify2 lives in two places historically; only patch the POST verify2 under the ad-gate folder
      if (key === 'verify2.php' && it.request.method !== 'POST') continue;
      it.response = it.response || [];
      const exName = 'LIVE AUTHENTICATED 2026-10-23 (CDP browser capture, verified session)';
      if (it.response.some(r => r.name === exName)) continue; // idempotent
      const lang = PREVIEW[key] || 'text';
      const mime = lang === 'json' ? 'application/json' : (key === 'hls/' ? 'application/vnd.apple.mpegurl' : 'text/plain');
      const ex = mkExample(exName, BODIES[key], lang, mime);
      ex.originalRequest = JSON.parse(JSON.stringify(it.request));
      it.response.push(ex);
      patched.push(`${it.name}  <= ${key}`);
    }
  }
}
walk(c.item);

// ensure placeholder variables exist
const vars = c.variable = c.variable || [];
function ensureVar(k, v, d) { if (!vars.some(x => x.key === k)) vars.push({ key: k, value: v, description: d }); }
ensureVar('t', '', 'Session token = first md5 segment of the verified t_hash_t cookie. SECRET — fill from your own authorized session; not stored here.');
ensureVar('play_token', '', 'HLS signing token for in=... (<md5>::<md5>::<ts>::ni::m[yes]). SECRET, short-TTL, rotates per rendition. Fill from your own playlist.php response.');

// Collection-level pre-request: pull tokens from the ACTIVE Postman environment
// (local, never exported with the collection) and warn — never throw — when a
// request references {{t}}/{{play_token}} but you haven't set them. Requests that
// don't need a token (check.php, verify2 shell) are unaffected. Idempotent.
const PREREQ = [
  "// netmirror-capture: tokens live ONLY in your local Postman environment, never in this collection or any app.",
  "const KEYS = ['t', 'play_token'];",
  "KEYS.forEach(k => { const v = pm.environment.get(k); if (v) pm.variables.set(k, v); });",
  "const raw = pm.request.url.toString() + '\\n' + ((pm.request.body && pm.request.body.raw) || '');",
  "const missing = KEYS.filter(k => raw.includes('{{' + k + '}}') && !pm.environment.get(k));",
  "if (missing.length) console.warn('[netmirror-capture] missing token(s): ' + missing.join(', ') + \" — set them in the 'NetMirror (local session)' environment from your own authorized session.\");"
];
c.event = c.event || [];
const hasPrereq = c.event.some(e => e.listen === 'prerequest' && ((e.script && e.script.exec) || []).join('\n').includes('netmirror-capture'));
if (!hasPrereq) c.event.push({ listen: 'prerequest', script: { type: 'text/javascript', exec: PREREQ } });

fs.writeFileSync(P, JSON.stringify(c, null, 2));
console.log('Patched requests with authenticated examples:');
console.log(patched.map(s => '  ' + s).join('\n'));
console.log(`Pre-request token resolver: ${hasPrereq ? 'already present' : 'installed'}.`);
console.log('\nValidating JSON...');
JSON.parse(fs.readFileSync(P, 'utf8'));
console.log('OK — valid JSON.');
