package com.project.lol.bridge

/** Installed at document start on the two trusted origins, before any dependent script. */
object BridgeScript {
    const val CONTENT = """
(function() {
    'use strict';
    if (window !== window.top || !window.NativeBridge || window.AndBridge) return;
    var origin = window.location.origin;
    if (origin !== 'https://open.spotify.com' && origin !== 'https://accounts.spotify.com') return;
    var pending = new Map(), sequence = 0, bridge = {};
    window.NativeBridge.onmessage = function(event) {
        try {
            var reply = JSON.parse(event.data), item = pending.get(reply.id);
            if (!item) return;
            pending.delete(reply.id); clearTimeout(item.timer);
            if (reply.error) item.reject(new Error(reply.error)); else item.resolve(reply.result);
        } catch (e) {}
    };
    function send(method, args, reply) {
        var id = String(++sequence);
        if (!reply) {
            window.NativeBridge.postMessage(JSON.stringify({id:id, method:method, args:args}));
            return;
        }
        return new Promise(function(resolve, reject) {
            if (pending.size >= 32) { reject(new Error('Bridge busy')); return; }
            var timer = setTimeout(function() { pending.delete(id); reject(new Error('Bridge timeout')); }, 20000);
            pending.set(id, {resolve:resolve, reject:reject, timer:timer});
            try { window.NativeBridge.postMessage(JSON.stringify({id:id, method:method, args:args})); }
            catch (e) { pending.delete(id); clearTimeout(timer); reject(e); }
        });
    }
    var methods = origin === 'https://accounts.spotify.com' ? ['loginDetected'] : [
        'loginDetected','deferMessage','wakeUp','wakeOff','cssInjected','dbg','clearDebugLog',
        'recAdContentIds','playLoaded','recMediaPosition','recMediaStatus','onMediaItemsLoaded',
        'onSearchCompleted','manageTShut','manageTSleep','recAccountName','openTimerDialog',
        'enterPip','enterPipVideo','downloadTrack','downloadCollection','skipDownload','cancelDownload'
    ];
    methods.forEach(function(method) {
        bridge[method] = function() { return send(method, Array.prototype.slice.call(arguments), false); };
    });
    if (origin === 'https://open.spotify.com') {
        bridge.isWoke = function() { return document.visibilityState === 'visible'; };
        bridge.nFetch = function(url, options) { return send('nFetch', [String(url), options || null], true); };
    }
    Object.defineProperty(window, 'AndBridge', {value:Object.freeze(bridge), writable:false, configurable:false});
})();
"""
}
