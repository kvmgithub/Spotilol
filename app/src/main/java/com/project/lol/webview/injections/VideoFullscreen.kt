package com.project.lol.webview.injections

object VideoFullscreen {
    const val CONTENT = """
        (function(){
            if(window.__splVfs) return;
            window.__splVfs=true;
            var ACTIVE=false, MISSING=0, BAR=null, HIDE=null, POLL=null, DRAG=false;
            var GLYPH_PLAY=String.fromCharCode(9654);
            var GLYPH_PAUSE=String.fromCharCode(10074)+String.fromCharCode(10074);
            var GLYPH_BACK=String.fromCharCode(171)+'10';
            var GLYPH_FWD='10'+String.fromCharCode(187);
            function vid(){
                var v=document.querySelector('.VideoPlayer__container video');
                if(!v||v.ended) return null;
                return v;
            }
            function inNowPlaying(el){
                if(!el||!el.closest) return false;
                return !!el.closest('aside[data-testid="now-playing-bar"],[data-testid="now-playing-bar"],[data-testid="now-playing-widget"],[video-player-npv]');
            }
            function isTrigger(el){
                if(!el||el.id==='spl-vfs-exit'||el.id==='spl-vfs-bar') return false;
                if(el.matches('[data-testid="fullscreen-mode-button"]')) return true;
                var a=(el.getAttribute('aria-label')||'').trim().toLowerCase();
                if(a==='full screen'||a==='enter full screen') return true;
                if(a==='exit full screen') return ACTIVE;
                return a==='expand now playing view'&&inNowPlaying(el);
            }
            function fmt(t){
                if(!isFinite(t)||t<0) t=0;
                var m=Math.floor(t/60), s=Math.floor(t%60);
                return m+':'+(s<10?'0':'')+s;
            }
            function ensureCss(){
                if(document.getElementById('spl-vfs-css')) return;
                var st=document.createElement('style');
                st.id='spl-vfs-css';
                st.textContent=
                    '#spl-vfs-bar{position:fixed;left:50%;bottom:14px;transform:translateX(-50%);display:flex;align-items:center;gap:4px;padding:4px 12px 4px 4px;border-radius:999px;background:rgba(18,18,18,.86);box-shadow:0 4px 18px rgba(0,0,0,.45);color:#fff;font-size:12px;width:min(520px,calc(100% - 28px));transition:opacity .18s;z-index:2147483647}'+
                    '.spl-vfs-btn{flex:0 0 auto;display:flex;align-items:center;justify-content:center;width:32px;height:32px;padding:0;border:0;border-radius:50%;background:transparent;color:#fff;font-size:13px;line-height:1;cursor:pointer;-webkit-tap-highlight-color:transparent}'+
                    '.spl-vfs-btn:active{background:rgba(255,255,255,.14)}'+
                    '.spl-vfs-time{flex:0 0 auto;opacity:.8;font-size:11px;font-variant-numeric:tabular-nums;letter-spacing:.02em}'+
                    '.spl-vfs-track{position:relative;flex:1 1 auto;min-width:0;height:24px;display:flex;align-items:center;cursor:pointer;touch-action:none}'+
                    '.spl-vfs-rail{position:absolute;left:0;right:0;top:50%;transform:translateY(-50%);height:4px;border-radius:2px;background:rgba(255,255,255,.16)}'+
                    '.spl-vfs-fill{position:absolute;left:0;top:50%;transform:translateY(-50%);height:4px;border-radius:2px;background:var(--spl-accent,#1db954);width:0%}'+
                    '.spl-vfs-knob{position:absolute;top:50%;left:0%;width:11px;height:11px;transform:translate(-50%,-50%);border-radius:50%;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.5);pointer-events:none;opacity:0;transition:opacity .15s}'+
                    '.spl-vfs-track:active .spl-vfs-knob{opacity:1}';
                var t=document.head||document.documentElement;
                if(t) t.appendChild(st);
            }
            function paint(pct){
                if(!BAR) return;
                var p=Math.max(0,Math.min(1,pct||0))*100;
                var f=BAR.querySelector('.spl-vfs-fill');
                var k=BAR.querySelector('.spl-vfs-knob');
                if(f) f.style.width=p+'%';
                if(k) k.style.left=p+'%';
            }
            function exitButton(show){
                var b=document.getElementById('spl-vfs-exit');
                if(!show){
                    if(b&&b.parentNode) b.parentNode.removeChild(b);
                    return;
                }
                if(b) return;
                var c=document.querySelector('.VideoPlayer__container');
                if(!c) return;
                b=document.createElement('button');
                b.id='spl-vfs-exit';
                b.type='button';
                b.setAttribute('aria-label','Exit full screen');
                b.textContent=String.fromCharCode(10005);
                b.style.cssText='position:fixed;top:12px;right:12px;z-index:2147483647;width:40px;height:40px;padding:0;border:0;border-radius:50%;background:rgba(18,18,18,.72);color:#fff;font-size:16px;line-height:40px;text-align:center;cursor:pointer;-webkit-tap-highlight-color:transparent;';
                b.addEventListener('click',function(ev){ev.preventDefault();ev.stopImmediatePropagation();exitFs();},true);
                c.appendChild(b);
            }
            function togglePlay(){
                var v=vid();
                if(!v) return;
                try{ if(v.paused) v.play(); else v.pause(); }catch(e){}
                sync(); showBar();
            }
            function seekBy(delta){
                var v=vid();
                if(!v) return;
                var dur=isFinite(v.duration)?v.duration:0;
                var next=(v.currentTime||0)+delta;
                if(next<0) next=0;
                if(dur>0&&next>dur) next=dur;
                try{ v.currentTime=next; }catch(e){}
                sync(); showBar();
            }
            function sync(){
                if(!BAR) return;
                var v=vid();
                if(!v) return;
                var dur=isFinite(v.duration)?v.duration:0;
                var cur=v.currentTime||0;
                var time=BAR.querySelector('.spl-vfs-time');
                var play=BAR.querySelector('#spl-vfs-play');
                if(!DRAG) paint(dur>0?cur/dur:0);
                if(time) time.textContent=fmt(cur)+' / '+fmt(dur);
                if(play) play.textContent=v.paused?GLYPH_PLAY:GLYPH_PAUSE;
            }
            function showBar(){
                if(!BAR) return;
                BAR.style.opacity='1';
                BAR.style.pointerEvents='auto';
                if(HIDE) clearTimeout(HIDE);
                HIDE=setTimeout(hideBar,3500);
            }
            function hideBar(){
                if(!BAR) return;
                BAR.style.opacity='0';
                BAR.style.pointerEvents='none';
            }
            function removeBar(){
                if(HIDE){ clearTimeout(HIDE); HIDE=null; }
                if(POLL){ clearInterval(POLL); POLL=null; }
                DRAG=false;
                if(BAR&&BAR.parentNode) BAR.parentNode.removeChild(BAR);
                BAR=null;
            }
            function mkBtn(id,label,glyph){
                var b=document.createElement('button');
                b.type='button';
                b.id=id;
                b.className='spl-vfs-btn';
                b.setAttribute('aria-label',label);
                b.textContent=glyph;
                return b;
            }
            function ensureBar(){
                var c=document.querySelector('.VideoPlayer__container');
                if(!c) return null;
                if(BAR&&BAR.parentNode===c) return BAR;
                if(BAR&&BAR.parentNode) BAR.parentNode.removeChild(BAR);
                ensureCss();
                BAR=document.createElement('div');
                BAR.id='spl-vfs-bar';
                var play=mkBtn('spl-vfs-play','Play or pause',GLYPH_PAUSE);
                var back=mkBtn('spl-vfs-rew','Rewind 10 seconds',GLYPH_BACK);
                var fwd=mkBtn('spl-vfs-fwd','Forward 10 seconds',GLYPH_FWD);
                var time=document.createElement('span');
                time.className='spl-vfs-time';
                time.textContent='0:00 / 0:00';
                var track=document.createElement('div');
                track.className='spl-vfs-track';
                var rail=document.createElement('div');
                rail.className='spl-vfs-rail';
                var fill=document.createElement('div');
                fill.className='spl-vfs-fill';
                var knob=document.createElement('div');
                knob.className='spl-vfs-knob';
                track.appendChild(rail);
                track.appendChild(fill);
                track.appendChild(knob);
                function seekTo(ev){
                    var v=vid();
                    var r=track.getBoundingClientRect();
                    if(!r.width) return;
                    var x=(ev.touches&&ev.touches[0])?ev.touches[0].clientX:ev.clientX;
                    var pct=Math.max(0,Math.min(1,(x-r.left)/r.width));
                    paint(pct);
                    if(v&&isFinite(v.duration)&&v.duration>0){
                        try{ v.currentTime=pct*v.duration; }catch(e){}
                    }
                }
                play.addEventListener('click',function(ev){ev.preventDefault();ev.stopImmediatePropagation();togglePlay();},true);
                back.addEventListener('click',function(ev){ev.preventDefault();ev.stopImmediatePropagation();seekBy(-10);},true);
                fwd.addEventListener('click',function(ev){ev.preventDefault();ev.stopImmediatePropagation();seekBy(10);},true);
                track.addEventListener('pointerdown',function(ev){
                    DRAG=true;
                    if(HIDE) clearTimeout(HIDE);
                    try{ track.setPointerCapture(ev.pointerId); }catch(e){}
                    seekTo(ev);
                    ev.preventDefault();ev.stopPropagation();
                },true);
                track.addEventListener('pointermove',function(ev){
                    if(!DRAG) return;
                    seekTo(ev);
                    ev.preventDefault();ev.stopPropagation();
                },true);
                track.addEventListener('pointerup',function(ev){
                    DRAG=false;
                    showBar();
                    ev.preventDefault();ev.stopPropagation();
                },true);
                BAR.addEventListener('click',function(ev){ev.stopPropagation();},false);
                BAR.addEventListener('pointerdown',function(ev){ev.stopPropagation();},false);
                BAR.appendChild(play);
                BAR.appendChild(back);
                BAR.appendChild(fwd);
                BAR.appendChild(time);
                BAR.appendChild(track);
                c.appendChild(BAR);
                return BAR;
            }
            function enterFs(){
                if(ACTIVE) return false;
                if(!vid()) return false;
                ACTIVE=true;MISSING=0;
                try{ window.__splPipFillVideo(true); }catch(e){}
                exitButton(true);
                ensureBar();
                if(!POLL) POLL=setInterval(sync,500);
                sync(); showBar();
                try{ AndBridge.enterVideoFullscreen(); }catch(e){}
                return true;
            }
            function exitFs(){
                if(!ACTIVE) return false;
                ACTIVE=false;MISSING=0;
                exitButton(false);
                removeBar();
                try{ window.__splPipFillVideo(false); }catch(e){}
                try{ AndBridge.exitVideoFullscreen(); }catch(e){}
                return true;
            }
            window.__splVfsEnter=enterFs;
            window.__splVfsExit=exitFs;
            window.__splVfsActive=function(){ return ACTIVE; };
            document.addEventListener('click',function(e){
                var el=e.target&&e.target.closest?e.target.closest('button,[role="button"]'):null;
                if(!el||!isTrigger(el)) return;
                if(ACTIVE){ e.preventDefault();e.stopPropagation();exitFs();return; }
                if(!vid()) return;
                e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();
                enterFs();
            },true);
            document.addEventListener('pointerdown',function(e){
                if(!ACTIVE) return;
                var c=document.querySelector('.VideoPlayer__container');
                if(c&&e.target&&c.contains(e.target)) showBar();
            },true);
            document.addEventListener('keydown',function(e){
                if(e.key==='Escape'&&ACTIVE){ e.preventDefault();exitFs(); }
            },true);
            setInterval(function(){
                if(!ACTIVE) return;
                if(vid()){ MISSING=0; return; }
                if(++MISSING>=2) exitFs();
            },1000);
        })();
    """
}
