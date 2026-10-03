const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/java/com/project/lol/bridge/BridgeScript.kt', 'utf8').split('"""')[1];
function page(origin = 'https://open.spotify.com', mainFrame = true) {
  const sent = [], timers = new Map(); let n = 0;
  const w = {location:{origin}, document:{visibilityState:'visible'}, NativeBridge:{postMessage(m){sent.push(JSON.parse(m));}},
    setTimeout(fn){timers.set(++n, fn);return n;}, clearTimeout(id){timers.delete(id);}};
  w.window = w; w.top = mainFrame ? w : {}; vm.runInNewContext(source, w);
  return {w, sent, timers};
}
test('foreign origins and subframes receive no compatibility bridge', () => {
  assert.equal(page('https://evilspotify.com').w.AndBridge, undefined);
  assert.equal(page('https://open.spotify.com', false).w.AndBridge, undefined);
});
test('native replies resolve asynchronous fetch without exposing transport', async () => {
  const {w,sent,timers}=page(); const p=w.AndBridge.nFetch('https://api.spotify.com/v1/me','{}');
  assert.equal(sent[0].method,'nFetch'); assert.equal(sent[0].args[0],'https://api.spotify.com/v1/me');
  w.NativeBridge.onmessage({data:JSON.stringify({id:sent[0].id,result:'{"status":200}'})});
  assert.equal(await p,'{"status":200}'); assert.equal(timers.size,0);
});
test('errors and timeouts reject pending calls', async () => {
  const {w,sent,timers}=page(); let p=w.AndBridge.nFetch('https://api.spotify.com/','{}');
  w.NativeBridge.onmessage({data:JSON.stringify({id:sent[0].id,error:'denied'})});
  await assert.rejects(p,/denied/);
  p=w.AndBridge.nFetch('https://api.spotify.com/','{}'); [...timers.values()][0]();
  await assert.rejects(p,/timeout/);
});
test('accounts origin has only login authority', () => {
  const {w,sent}=page('https://accounts.spotify.com');
  assert.equal(w.AndBridge.nFetch,undefined); assert.equal(w.AndBridge.downloadTrack,undefined);
  w.AndBridge.loginDetected(); assert.equal(sent[0].method,'loginDetected');
});
test('legacy fire-and-forget methods and synchronous visibility remain usable', () => {
  const {w,sent}=page(); w.AndBridge.recMediaPosition(1234); assert.equal(sent[0].args[0],1234);
  assert.equal(w.AndBridge.isWoke(),true); w.document.visibilityState='hidden'; assert.equal(w.AndBridge.isWoke(),false);
});
