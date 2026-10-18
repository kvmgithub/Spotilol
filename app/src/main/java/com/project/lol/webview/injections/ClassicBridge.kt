package com.project.lol.webview.injections

object ClassicBridge {
    const val CONTENT = """
        (function(){
            window.mngFetch = async function(url, opts){
                var o = opts || {};
                var input = typeof Request !== 'undefined' && url instanceof Request ? url : null;
                var target = input ? input.url : String(url);
                var h = o.headers || (input && input.headers) || {};
                var headers = {};
                if(typeof h.forEach === 'function') {
                    h.forEach(function(v,k){ headers[k]=v; });
                } else if(typeof h.get === 'function') {
                    try {
                        headers['Authorization'] = h.get('Authorization');
                        headers['Client-Token'] = h.get('Client-Token');
                        headers['Content-Type'] = h.get('Content-Type');
                    } catch(e){}
                } else {
                    headers = h;
                }
                var body = o.body;
                if (body === undefined && input && input.body) body = await input.clone().arrayBuffer();
                var binary = body instanceof ArrayBuffer || ArrayBuffer.isView(body);
                var options = {method:o.method || (input && input.method) || 'GET',headers:headers,body:null};
                if (binary) {
                    var bytes = body instanceof ArrayBuffer ? new Uint8Array(body) : new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
                    var encoded = '';
                    for (var i=0;i<bytes.length;i++) encoded += String.fromCharCode(bytes[i]);
                    options.bodyBase64 = btoa(encoded);
                } else if (body != null) {
                    options.body = typeof body === 'string' ? body : JSON.stringify(body);
                }
                options.responseBase64 = binary || Object.keys(headers).some(function(k){
                    return /^(accept|content-type)$/i.test(k) && /protobuf/i.test(headers[k]);
                });
                var raw = await AndBridge.nFetch(target, JSON.stringify(options));
                var data;
                try { data = JSON.parse(raw); }
                catch(e) { throw new Error('Bad native response: ' + raw); }
                if(!data || data.status === 0) {
                    throw new Error('network error: ' + (data ? data.body : 'unknown'));
                }
                function responseBytes(){
                    if (!data.bodyBase64) return new TextEncoder().encode(data.body || '');
                    var decoded=atob(data.bodyBase64), bytes=new Uint8Array(decoded.length);
                    for(var i=0;i<decoded.length;i++) bytes[i]=decoded.charCodeAt(i);
                    return bytes;
                }
                function responseText(){
                    return data.bodyBase64 ? new TextDecoder().decode(responseBytes()) : (data.body || '');
                }
                return {
                    status: data.status,
                    ok: data.status >= 200 && data.status < 300,
                    headers: new Headers(data.headers || {}),
                    url: target,
                    json: function(){ return Promise.resolve(JSON.parse(responseText() || '{}')); },
                    text: function(){ return Promise.resolve(responseText()); },
                    arrayBuffer: function(){ return Promise.resolve(responseBytes().buffer); },
                    clone: function(){ return this; }
                };
            };
        })();
    
    """
}
