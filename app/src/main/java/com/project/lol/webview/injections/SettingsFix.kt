package com.project.lol.webview.injections

/*
 * CREDIT: Spotilol - Settings Page Fix
 */

object SettingsFix {
    const val CONTENT = """
        (function(){
            if(window.__splSettingsFix) return;
            window.__splSettingsFix = true;

            try {
                var origDefine = customElements.define.bind(customElements);
                customElements.define = function(name, constructor, options) {
                    if (name === 'ms-store-badge') {
                        try { AndBridge.dbg('s', 'Blocked ms-store-badge CE'); } catch(e) {}
                        return;
                    }
                    return origDefine(name, constructor, options);
                };
            } catch(e) {}

            function isSpotifyUrl(url) {
                return url.indexOf('https://open.spotify.com/') === 0 ||
                       url.indexOf('https://accounts.spotify.com/') === 0;
            }

            function isOAuthUrl(url) {
                var host = '';
                try {
                    host = new URL(url).hostname.toLowerCase();
                } catch(e) { return false; }
                return host === 'google.com' ||
                       host.indexOf('.google.com') !== -1 ||
                       host.indexOf('.google.') !== -1 ||
                       host === 'facebook.com' ||
                       host.indexOf('.facebook.com') !== -1 ||
                       host === 'appleid.apple.com' ||
                       host.indexOf('.apple.com') !== -1;
            }

            function isAllowed(url) {
                return isSpotifyUrl(url) || isOAuthUrl(url);
            }

            function hidePromos() {
                var badges = document.querySelectorAll('ms-store-badge');
                for (var i = 0; i < badges.length; i++) {
                    badges[i].style.pointerEvents = 'none';
                }

                var imgs = document.querySelectorAll('img[src*="get.microsoft.com"]');
                for (var i = 0; i < imgs.length; i++) {
                    imgs[i].style.pointerEvents = 'none';
                    imgs[i].style.display = 'none';
                }
            }

            function onExternalClick(e) {
                var a = e.target && e.target.closest ? e.target.closest('a[target="_blank"]') : null;
                if (!a) return;
                var href = a.href || '';
                if (!href || href.charAt(0) === '#') return;
                if (isAllowed(href)) return;
                e.preventDefault();
                e.stopPropagation();
                try { AndBridge.dbg('s', 'Blocked external nav: ' + href); } catch(err) {}
            }

            document.addEventListener('click', onExternalClick, true);
            document.addEventListener('auxclick', onExternalClick, true);

            hidePromos();
            setInterval(hidePromos, 5000);
        })();
    """
}