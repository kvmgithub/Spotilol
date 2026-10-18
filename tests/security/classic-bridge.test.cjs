const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source=fs.readFileSync('app/src/main/java/com/project/lol/webview/injections/ClassicBridge.kt','utf8').split('"""')[1];
function page(reply){
  const requests=[];
  const w={Headers,Request,ArrayBuffer,Uint8Array,TextEncoder,TextDecoder,
    btoa:s=>Buffer.from(s,'binary').toString('base64'),atob:s=>Buffer.from(s,'base64').toString('binary'),
    AndBridge:{async nFetch(url,opts){requests.push({url,opts:JSON.parse(opts)});return JSON.stringify(reply);}}};
  w.window=w;vm.runInNewContext(source,w);return {w,requests};
}
test('native binary transport keeps protobuf bytes intact',async()=>{
  const bytes=Uint8Array.from([0,255,128,65]);
  const {w,requests}=page({status:200,bodyBase64:Buffer.from(bytes).toString('base64'),headers:{}});
  const response=await w.mngFetch('https://spclient.wg.spotify.com/canvaz-cache/v0/canvases',{
    method:'POST',headers:{'Content-Type':'application/x-protobuf'},body:bytes});
  assert.equal(requests[0].opts.bodyBase64,Buffer.from(bytes).toString('base64'));
  assert.equal(requests[0].opts.responseBase64,true);
  assert.deepEqual(Buffer.from(await response.arrayBuffer()),Buffer.from(bytes));
});
test('Request objects preserve URL method headers and payload',async()=>{
  const {w,requests}=page({status:200,body:'{"ok":true}',headers:{}});
  const input=new Request('https://api.spotify.com/v1/me/player',{method:'PUT',headers:{Authorization:'Bearer secret'},body:'{"position_ms":1234}'});
  const response=await w.mngFetch(input);
  assert.equal(requests[0].url,input.url);
  assert.equal(requests[0].opts.method,'PUT');
  assert.equal(requests[0].opts.headers.authorization,'Bearer secret');
  assert.equal(Buffer.from(requests[0].opts.bodyBase64,'base64').toString(),'{"position_ms":1234}');
  assert.equal((await response.json()).ok,true);
});
