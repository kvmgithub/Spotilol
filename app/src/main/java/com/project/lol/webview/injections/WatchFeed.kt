package com.project.lol.webview.injections

/*
 * CREDIT: Spotilol - Watch Feed (mobile)
 * Spotify's vertical video feed renders its desktop 3-column layout inside a
 * small floating card on a phone viewport. This makes the overlay and the feed
 * full-screen and stacks it: video as the base layer, info/actions as overlays.
 */

object WatchFeed {
    const val CONTENT = """
        (function(){
            var st = document.createElement('style');
            st.id = 'spl-watchfeed-style';
            st.textContent = [
                'div:has(> [data-testid="watch-feed-view"]){position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147482000!important;background:#000!important;display:block!important;overflow:hidden!important}',
                '[data-testid="watch-feed-view"]{width:100%!important;height:100%!important;max-width:none!important;margin:0!important;padding:0!important;border-radius:0!important;background:#000!important}',
                '[data-testid="watch-feed-view"] > div{position:relative!important;width:100%!important;height:100%!important;overflow:hidden!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(1){position:absolute!important;left:14px!important;bottom:104px!important;width:auto!important;max-width:68%!important;height:auto!important;z-index:6!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(2){position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;z-index:1!important;display:block!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(3){position:absolute!important;right:6px!important;top:0!important;height:100%!important;width:auto!important;z-index:6!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(2) > div:first-child{position:absolute!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100%!important;height:100%!important;max-width:none!important;padding:0!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(2) > div:first-child > div{width:100%!important;height:100%!important;max-width:none!important;margin:0!important;aspect-ratio:auto!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(2) > div:first-child > div > div{width:100%!important;height:100%!important;border-radius:0!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(2) > div:first-child > div img{width:100%!important;height:100%!important;object-fit:cover!important;border-radius:0!important}',
                '[data-testid="watch-feed-view"] > div > div:nth-child(2) > div:last-child{display:none!important}'
            ].join('\n');
            function appendStyle(){
                var t = document.head || document.documentElement;
                if (t && !document.getElementById('spl-watchfeed-style')) t.appendChild(st);
            }
            appendStyle();
            document.addEventListener('DOMContentLoaded', appendStyle);
        })();
    """
}
