package com.funpay.app.ui.webview

/**
 * Скрипты, которые внедряются в загруженные страницы FunPay.
 *
 * [PULL_TO_REFRESH] сообщает нативной части, можно ли сейчас начинать
 * обновление свайпом вниз. Решение принимается строго: обновление разрешено
 * только если палец находится вне любого прокручиваемого контейнера и сама
 * страница прокручена в самое начало. На страницах чатов обновление запрещено.
 *
 * [UNREAD_WATCHER] следит за счётчиком непрочитанных в шапке сайта и за
 * списком диалогов, пока приложение открыто, и передаёт состояние в натив.
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

          // Ближайший прокручиваемый предок элемента (или сам элемент).
          function scrollerAt(el) {
            var node = el;
            while (node && node.nodeType === 1) {
              if (isScrollable(node)) { return node; }
              if (node === document.body) { break; }
              node = node.parentElement;
            }
            return null;
          }

          function chatOpen() {
            if (/\/chat\//.test(location.pathname || '')) { return true; }
            if (/[?&]node=(\d+)/.test(location.search || '')) { return true; }
            return !!document.querySelector(
              '.chat-message-list, .chat-msg-item, .chat-header, .chat-form'
            );
          }

          function docScrollTop() {
            return window.scrollY ||
                   window.pageYOffset ||
                   document.documentElement.scrollTop ||
                   document.body.scrollTop || 0;
          }

          function allowed() {
            // В чате свайп вниз — это всегда чтение истории, а не обновление.
            if (chatOpen()) { return false; }
            // Палец на прокручиваемом блоке (список лотов, сообщений и т.п.).
            if (lastTouch && scrollerAt(lastTouch)) { return false; }
            return docScrollTop() <= 0;
          }

          function report(force) {
            try {
              var value = allowed();
              if (value === lastState && !force) { return; }
              lastState = value;
              if (window.FunPayBridge && window.FunPayBridge.setPullToRefreshEnabled) {
                window.FunPayBridge.setPullToRefreshEnabled(value);
              }
            } catch (e) { }
          }

          document.addEventListener('touchstart', function (e) {
            lastTouch = e.target;
            report(true);
          }, true);

          document.addEventListener('touchend', function () {
            setTimeout(function () { report(false); }, 60);
          }, true);

          document.addEventListener('touchcancel', function () {
            setTimeout(function () { report(false); }, 60);
          }, true);

          document.addEventListener('scroll', function () { report(false); }, true);
          document.addEventListener('click', function () {
            setTimeout(function () { report(false); }, 250);
          }, true);

          window.addEventListener('resize', function () { report(false); });
          window.addEventListener('pageshow', function () { report(true); });

          setInterval(function () { report(false); }, 300);
          report(true);
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

          // Значение бейджа рядом с пунктом меню «Чат».
          function totalUnread() {
            var badge = document.querySelector('a.menu-item-chat span.badge') ||
                        document.querySelector('.menu-item-chat .badge');
            return toInt(text(badge));
          }

          function activeChatId() {
            var match = /[?&]node=(\d+)/.exec(location.search || '');
            return match ? toInt(match[1]) : 0;
          }

          function chats() {
            var items = document.querySelectorAll('a.contact-item[data-id]');
            var out = [];
            for (var i = 0; i < items.length && i < 30; i++) {
              var item = items[i];
              var nameEl = item.querySelector('.media-user-name');
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

          var lastPayload = null;

          function report() {
            try {
              if (!window.FunPayBridge || !window.FunPayBridge.reportUnread) { return; }

              var total = totalUnread();
              var active = activeChatId();
              var payload = total + '|' + active + '|' + JSON.stringify(chats());
              if (payload === lastPayload) { return; }
              lastPayload = payload;

              window.FunPayBridge.reportUnread(total, active, JSON.stringify(chats()));
            } catch (e) { }
          }

          // Счётчик в шапке меняется без перезагрузки страницы, поэтому
          // состояние проверяется раз в секунду. Отправляется только изменение.
          setInterval(report, 1000);

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', report);
          } else {
            report();
          }
          window.addEventListener('pageshow', report);
          document.addEventListener('visibilitychange', function () {
            if (!document.hidden) { report(); }
          });
        })();
    """.trimIndent()
}
