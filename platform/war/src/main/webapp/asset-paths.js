/*
 * Copyright (c) 2024 Domatar
 *
 * Browser URLs for any file served by an app's AppAssetServlet
 * (HTML, images, icons). Mirrors com.domatar.install.AssetPaths.
 *
 * WIRE_CONTEXT is built with concatenation so a reverse-proxy sub_filter
 * on "/domatar/" cannot rewrite this file's source.
 */
(function (global)
{
    var WIRE_CONTEXT = "/" + "domatar";
    var DEFAULT_APP_ID = "domatar";
    var LAUNCHER_ASSET = "icons/app.svg";
    var DEFAULT_GLYPH_ASSET = "icons/cls/default/obj.svg";
    var SCRIPT_NAME = "asset-paths.js";

    function normalizeContext(contextPath)
    {
        if (contextPath == null)
            return WIRE_CONTEXT;

        var ctx = String(contextPath).replace(/^\s+|\s+$/g, "");

        if (ctx === "" || ctx === "-" || ctx === "none" || ctx === "/")
            return "";

        if (ctx.charAt(0) !== "/")
            ctx = "/" + ctx;

        while (ctx.length > 1 && ctx.charAt(ctx.length - 1) === "/")
            ctx = ctx.substring(0, ctx.length - 1);

        return ctx;
    }

    function trimLeadingSlash(path)
    {
        var p = String(path || "").replace(/\\/g, "/");

        while (p.charAt(0) === "/")
            p = p.substring(1);

        return p;
    }

    function join(contextPath, appId, assetPath)
    {
        var ctx = (contextPath == null) ? WIRE_CONTEXT : normalizeContext(contextPath);
        var asset = trimLeadingSlash(assetPath);

        return ctx + "/" + appId + "/" + asset;
    }

    function url(origin, contextPath, appId, assetPath)
    {
        if (!appId || !assetPath)
            return url(origin, contextPath, DEFAULT_APP_ID, DEFAULT_GLYPH_ASSET);

        var path = join(contextPath, appId, assetPath);

        if (!origin)
            return path;

        var base = String(origin);
        if (base.charAt(base.length - 1) === "/")
            base = base.substring(0, base.length - 1);

        return base + path;
    }

    function contextFromScript()
    {
        var scripts = document.getElementsByTagName("script");
        var i, src, idx, u, prefix;

        for (i = 0; i < scripts.length; i++)
        {
            src = scripts[i].src || "";
            idx = src.indexOf("/" + SCRIPT_NAME);
            if (idx < 0 && src.indexOf(SCRIPT_NAME) >= 0)
                idx = src.lastIndexOf("/");
            if (idx < 0)
                continue;
            try
            {
                u = new URL(src, global.location.href);
                prefix = u.pathname.substring(0, u.pathname.lastIndexOf("/"));
                if (prefix === WIRE_CONTEXT || prefix.indexOf(WIRE_CONTEXT + "/") === 0)
                    return WIRE_CONTEXT;
                return normalizeContext(prefix === "/" ? "" : prefix);
            }
            catch (e) {}
        }

        return "";
    }

    function sameHost(origin)
    {
        if (!origin)
            return true;
        try
        {
            return (new URL(origin, global.location.href)).host === global.location.host;
        }
        catch (e)
        {
            return false;
        }
    }

    /**
     * @param {string} [origin]       scheme://host or empty for this page
     * @param {string|null} [contextPath]  /domatar, "", or null to infer
     *        from how this script was loaded
     */
    function AssetPaths(origin, contextPath)
    {
        this.origin = origin || "";
        this.contextPath = (contextPath === undefined || contextPath === null)
            ? contextFromScript()
            : normalizeContext(contextPath);
    }

    AssetPaths.WIRE_CONTEXT = WIRE_CONTEXT;
    AssetPaths.DEFAULT_APP_ID = DEFAULT_APP_ID;
    AssetPaths.LAUNCHER_ASSET = LAUNCHER_ASSET;
    AssetPaths.DEFAULT_GLYPH_ASSET = DEFAULT_GLYPH_ASSET;

    AssetPaths.normalizeContext = normalizeContext;
    AssetPaths.url = url;
    AssetPaths.contextFromScript = contextFromScript;

    AssetPaths.fromSiteRole = function (role)
    {
        if (!role || role.AssetContextPath === undefined || role.AssetContextPath === null)
            return new AssetPaths("", null);

        return new AssetPaths("", role.AssetContextPath);
    };

    AssetPaths.fromHosts = function (domain, publicDomain)
    {
        var host = publicDomain || domain || "";

        if (!host)
            return new AssetPaths("", null);

        var origin = host.indexOf("://") >= 0
            ? host
            : (global.location.protocol + "//" + host);

        if (sameHost(origin))
            return new AssetPaths("", null);

        var ctx = (publicDomain && publicDomain !== domain) || (publicDomain && !domain)
            ? ""
            : WIRE_CONTEXT;

        return new AssetPaths(origin, ctx);
    };

    AssetPaths.prototype.url = function (appId, assetPath)
    {
        var origin = this.origin;
        var ctx = this.contextPath;
        var path = (global.location && global.location.pathname) || "";
        var onWar = path === WIRE_CONTEXT
            || path.indexOf(WIRE_CONTEXT + "/") === 0;

        if (onWar)
        {
            var originIsLocal = false;
            try
            {
                if (origin)
                {
                    var h = (new URL(origin, global.location.href)).hostname;
                    originIsLocal = h === "localhost" || h === "127.0.0.1";
                }
            }
            catch (e) { originIsLocal = false; }

            if (origin && !sameHost(origin) && originIsLocal)
                ctx = WIRE_CONTEXT;
            else
            {
                origin = "";
                ctx = WIRE_CONTEXT;
            }
        }
        else if (sameHost(origin))
            origin = "";

        return url(origin, ctx, appId, assetPath);
    };

    AssetPaths.prototype.launcher = function (appId)
    {
        return this.url(appId, LAUNCHER_ASSET);
    };

    AssetPaths.prototype.cls = function (appId, clsId)
    {
        if (!clsId)
            return this.defaultGlyph();

        return this.url(appId, "icons/cls/" + clsId + ".svg");
    };

    AssetPaths.prototype.defaultGlyph = function ()
    {
        return this.url(DEFAULT_APP_ID, DEFAULT_GLYPH_ASSET);
    };

    AssetPaths.prototype.isDefaultGlyph = function (src)
    {
        if (!src)
            return false;

        var def = this.defaultGlyph();

        return src.indexOf(def) !== -1
            || src.indexOf("/" + DEFAULT_APP_ID + "/" + DEFAULT_GLYPH_ASSET) !== -1
            || src.indexOf("/icons/cls/default/obj.svg") !== -1;
    };

    global.AssetPaths = AssetPaths;
})(window);
