/*
 * Copyright (c) 2024 Domatar
 *
 * Same-origin signal that the user-app inventory changed (install /
 * uninstall). App Store notifies; Desktop listens and repaints.
 * BroadcastChannel plus a localStorage fallback for other tabs.
 */
(function (global)
{
    var CHANNEL = "domatar" + "-userApps";
    var STORE_KEY = CHANNEL + "-at";

    function notify()
    {
        try
        {
            if (global.BroadcastChannel)
            {
                var ch = new BroadcastChannel(CHANNEL);

                ch.postMessage({ type: "changed" });
                ch.close();
            }
        }
        catch (e) {}

        try
        {
            global.localStorage.setItem(STORE_KEY, String(Date.now()));
        }
        catch (e) {}
    }

    function listen(handler)
    {
        var timer = null;

        function fire()
        {
            if (timer)
                global.clearTimeout(timer);
            timer = global.setTimeout(function()
            {
                timer = null;
                handler();
            }, 80);
        }

        try
        {
            if (global.BroadcastChannel)
            {
                var ch = new BroadcastChannel(CHANNEL);

                ch.onmessage = fire;
            }
        }
        catch (e) {}

        global.addEventListener("storage", function(e)
        {
            if (e.key === STORE_KEY)
                fire();
        });
    }

    global.UserAppsEvents = { notify: notify, listen: listen };
})(window);
