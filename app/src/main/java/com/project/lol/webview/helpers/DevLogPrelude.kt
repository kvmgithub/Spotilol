package com.project.lol.webview.helpers

object DevLogPrelude {

    fun js(): String = """
        (function(){
            window.__splDbgOn=true;
            function send(lvl,m){
                try{ AndBridge.dbg(lvl,String(m)); }catch(e){}
            }
            window.dbg =function(m){send('l',m)};
            window.dbgv=function(m){send('v',m)};
            window.dbgi=function(m){send('i',m)};
            window.dbgw=function(m){send('w',m)};
            window.dbge=function(m){send('e',m)};
            window.DevLog={
                verbose:function(){var a=[].slice.call(arguments).join(' ');send('v',a)},
                log:function(){var a=[].slice.call(arguments).join(' ');send('l',a)},
                info:function(){var a=[].slice.call(arguments).join(' ');send('i',a)},
                warn:function(){var a=[].slice.call(arguments).join(' ');send('w',a)},
                error:function(){var a=[].slice.call(arguments).join(' ');send('e',a)},
                sys:function(){var a=[].slice.call(arguments).join(' ');send('s',a)},
                clear:function(){try{ AndBridge.clearDebugLog(); }catch(e){}},
                dump:function(){return '(see Settings > Logger)'}
            };
        })();
    """.trimIndent()
}