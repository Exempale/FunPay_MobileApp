package com.funpay.app.ui.webview

/**
 * PageScripts — JS-скрипты, которые внедряются в загруженные страницы FunPay.
 *
 * 1) [UNREAD_WATCHER] — следит за счётчиком непрочитанных сообщений в шапке
 *    сайта. Благодаря ему уведомление приходит практически сразу, пока
 *    приложение открыто (не нужно ждать фоновую проверку).
 *
 * 2) [PULL_TO_REFRESH] — сообщает нативному слою, можно ли сейчас делать
 *    pull-to-refresh. Именно этот скрипт исправляет баг «листаю чат вверх —
 *    страница перезагружается»: в диалоге обновление по свайпу выключено,
 *    а внутри скроллящегося контейнера — только когда он действительно вверху.
 */
object PageScripts {

    val PULL_TO_REFRESH: String = """
        (function () {
          if (window.__fpPullInstalled) { return; }
          window.__fpPullInstalled = true;

          var lastTouch = null;
          var lastState = null;

          function isScrollable(el) {
            if (!el || el.nodeType !== 1) { return false; }
            var style = window.getComputedStyle(el);
            var overflowY = style.overflowY;
            if (overflowY !== 'auto' && overflowY !== 'scroll' && overflowY !== 'overlay') {
              return false;
            }
            return (el.scrollHeight - el.clientHeight) > 4;
          }

          function scrollerAt(el) {
            var node = el;
            while (node && node !== document.body && node !== document.documentElement) {
              if (isScrollable(node)) { return node; }
              node = node.parentElement;
            }
            return null;
          }

          function chatOpen() {
            if (document.querySelector('.chat-message-list')) { return true; }
            return /[?&]node=(\d+)/.test(location.search || '');
          }

          function allowed() {
            if (chatOpen()) { return false; }
            var scroller = scrollerAt(lastTouch);
            if (scroller) { return scroller.scrollTop <= 0; }
            var y = window.scrollY ||
                    document.documentElement.scrollTop ||
                    document.body.scrollTop || 0;
            return y <= 0;
          }

          function report() {
            try {
              var value = allowed();
              if (value === lastState) { return; }
              lastState = value;
              if (window.FunPayBridge && window.FunPayBridge.setPullToRefreshEnabled) {
                window.FunPayBridge.setPullToRefreshEnabled(value);
              }
            } catch (e) { }
          }

          document.addEventListener('touchstart', function (e) {
            lastTouch = e.target;
            report();
          }, true);

          document.addEventListener('touchend', function () {
            setTimeout(report, 60);
          }, true);

          document.addEventListener('scroll', report, true);
          document.addEventListener('click', function () {
            setTimeout(report, 250);
          }, true);
          window.addEventListener('resize', report);
          window.addEventListener('pageshow', report);

          setInterval(report, 250);
          report();
        })();
    """.trimIndent()

    val UNREAD_WATCHER: String = """
        (function () {
          if (window.__fpUnreadInstalled) { return; }
          window.__fpUnreadInstalled = true;

          function toInt(value) {
            var n = parseInt(value, 10);
            return isNaN(n) ? 0 : n;
          }

          function text(el) {
            if (!el) { return ''; }
            return (el.textContent || '').replace(/\s+/g, ' ').trim();
          }

          function totalUnread() {
            var badge = document.querySelector('a.menu-item-chat span.badge');
            return toInt(text(badge));
          }

          function activeChatId() {
            var match = /[?&]node=(\d+)/.exec(location.search || '');
            return match ? toInt(match[1]) : 0;
          }

          function chats() {
            var items = document.querySelectorAll('a.contact-item');
            var out = [];
            for (var i = 0; i < items.length && i < 25; i++) {
              var item = items[i];
              var nameEl = item.querySelector('.media-user-name') ||
                           item.querySelector('.contact-item-name');
              var msgEl = item.querySelector('.contact-item-message');
              out.push({
                id: toInt(item.getAttribute('data-id')),
                name: text(nameEl),
                preview: text(msgEl).slice(0, 140),
                last: toInt(item.getAttribute('data-node-msg')),
                read: toInt(item.getAttribute('data-user-msg')),
                unread: /(^|\s)unread(\s|$)/.test(item.className || '')
              });
            }
            return out;
          }

          var lastTotal = -1;

          function report() {
            try {
              var total = totalUnread();
              if (total === lastTotal) { return; }
              lastTotal = total;

              if (!window.FunPayBridge || !window.FunPayBridge.reportUnread) { return; }
              window.FunPayBridge.reportUnread(
                total,
                activeChatId(),
                JSON.stringify(chats())
              );
            } catch (e) { }
          }

          setInterval(report, 3000);

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', report);
          } else {
            report();
          }
          window.addEventListener('pageshow', report);
        })();
    """.trimIndent()
}
