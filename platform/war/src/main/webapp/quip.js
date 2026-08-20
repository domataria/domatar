  /*
 * Copyright (c) 2024 Domatar
 */

function setCookie(name, value)
  {
    var date = new Date();
    date.setTime(date.getTime() + 365*24*60*60*1000);
    document.cookie = encodeURIComponent(name) + '=' +
                      encodeURIComponent(value) + '; expires=' +
                      date.toUTCString() + '; path=/';
  };

  function getCookie (name)
  {
    var prefix = encodeURIComponent(name) + '=';
    var c = document.cookie;
    var nullstring = '';
    var cookieStartIndex = c.indexOf(" " + prefix);

    if (cookieStartIndex == -1)
    {
      if(c.indexOf(prefix) == 0)
        cookieStartIndex = 0;
      else
        return nullstring;
    }
    else
      cookieStartIndex++;

    var cookieEndIndex = c.indexOf(";", cookieStartIndex + prefix.length);

    if (cookieEndIndex == -1)
      cookieEndIndex = c.length;

    var cookie = c.substring(cookieStartIndex + prefix.length, cookieEndIndex );

    if (cookie.charAt(0) == '"' && cookie.charAt(cookie.length - 1) == '"')
      cookie = cookie.substring(1, cookie.length - 1);

    return decodeURIComponent(cookie);
  }

  function getLocation()
  {
    if (navigator.geolocation)
      navigator.geolocation.getCurrentPosition(setGeoLoc);
  }

  function setGeoLoc(position)
  {
    var value = position.coords.latitude + "," + position.coords.longitude;
    setCookie("loc", value);
  }

  getLocation();

  function getChildren(quipId)
  {
    var dataString = 'Action=GetQuipChildren&QuipId=' + quipId;

    $.ajax({
          type: "POST",
          cache: false,
          url: "/domatar/quippin/Wui/QuipWui",
          crossDomain: false,
          data: dataString,
          timeout: 10000000,
          success: function(json) {addChildren(quipId, JSON.parse(json));},
          error: function(jqXHR, textStatus, errorThrown) {alert(textStatus + " : " + errorThrown);}
        });
  }

  function addChildren(parentId, children)
  {
    var text = '<div style="text-align:center;"><table style="text-align:left;width:100%;">';

    var quipArray = children.Attrs.Quips;

    // alert("insertQuips, quipArray[0] = " + quipArray[0]);

    for (var i = 0; i < quipArray.length; i++)
    {
      quipArray[i].ParentId = parentId;
      text += quipHtml(quipArray[i]);
    }

    text += '</table></div><br />';

    var childrenDiv = document.getElementById("children-" + parentId);

    childrenDiv.innerHTML = text;

    //$("#children-" + quipId).html(text);
  }

  function addChild(quipId)
  {
    var txt = document.getElementById("AddChildTxt-" + quipId);
    var btn = document.getElementById("AddChildBtn-" + quipId);

    if (txt.style.display === 'none' || txt.dataset.mode !== 'reply')
    {
      // Open (or switch) to reply mode
      txt.dataset.mode  = 'reply';
      txt.style.display = 'block';
      txt.placeholder   = 'Write your reply...';
      btn.title         = 'Send Reply';
      replySetOthers(quipId, 'none');
    }
    else
    {
      // Second click: cancel if empty, otherwise submit
      var raw = txt.value.trim();

      if (raw === '')
      {
        txt.style.display = 'none';
        txt.dataset.mode  = '';
        btn.title         = 'Reply';
        replySetOthers(quipId, '');
        return;
      }

      var dataString = 'Text=' + encodeURIComponent(raw) + '&ParentId=' + encodeURIComponent(quipId) + '&Action=AddQuip';

      $.ajax({
               type: "POST",
               cache: false,
               url: '/domatar/quippin/Wui/QuipsWui',
               crossDomain: false,
               data: dataString,
               timeout: 10000000,
               success: function(json) { childAdded(quipId); },
               error: function(jqXHR, textStatus, errorThrown) { alert(textStatus + ': ' + errorThrown); }
             });
    }
  }

  function childAdded(quipId)
  {
    var txt = document.getElementById("AddChildTxt-" + quipId);
    var btn = document.getElementById("AddChildBtn-" + quipId);

    txt.style.display = 'none';
    txt.value         = '';
    txt.dataset.mode  = '';
    btn.title         = 'Reply';
    replySetOthers(quipId, '');

    getChildren(quipId);
  }

  function removeQuipChild(childId, parentId)
  {
    var dataString = 'ParentQuipId=' + encodeURIComponent(parentId) + '&ChildQuipId=' + encodeURIComponent(childId) + '&Action=RemoveQuipChild';

    var urlString = '/domatar/quippin/Wui/QuipWui';

    $.ajax({
             type: "POST",
             cache: false,
             url: urlString,
             crossDomain: false,
             data: dataString,
             timeout: 10000000,
             success: function(json) {childQuipRemoved(childId);},
             error: function(jqXHR, textStatus, errorThrown) {alert(textStatus + " : " + errorThrown);}
           });
  }

  function childQuipRemoved(quipId)
  {
    var row = document.getElementById('tr-' + quipId);

    row.style.display = 'none';
  }

  function blockUser(childId, parentId)
  {
    var dataString = 'BanIp=True&ParentQuipId=' + encodeURIComponent(parentId) + '&ChildQuipId=' + encodeURIComponent(childId) + '&Action=BanChild';

    var urlString = '/domatar/quippin/Wui/QuipWui';

    $.ajax({
             type: "POST",
             cache: false,
             url: urlString,
             crossDomain: false,
             data: dataString,
             timeout: 10000000,
             success: function(json) {childBanned(childId);},
             error: function(jqXHR, textStatus, errorThrown) {alert(textStatus + " : " + errorThrown);}
           });
  }

  function childBanned(quipId)
  {
    alert("User and IP address blocked");
  }

  function toggleLike(liked, quipId)
  {
    var sentiment;

    if (liked == "True")
      sentiment = "None";
    else
      sentiment = "Like";

    var dataString = 'QuipId=' + quipId + '&Sentiment=' + sentiment + '&Action=AddSentiment';

    $.ajax({
             type: "POST",
             cache: false,
             url: "/domatar/quippin/Wui/QuipWui",
             crossDomain: false,
             data: dataString,
             timeout: 10000000,
             success: function(json) {showLike(liked, quipId);},
             error: function(jqXHR, textStatus, errorThrown) {alert(textStatus + " : " + errorThrown);}
           });
  };

  function showLike(liked, quipId)
  {
    var likedTxt;

    if (liked == 'True')
    {
      liked = 'False';
      likedTxt = '<img src="/domatar/quippin/like.png" title="Not Liked" height="10" width="10"> Like';
    }
    else
    {
      liked = 'True';
      likedTxt = '<img src="/domatar/quippin/liked.png" title="Liked" height="10" width="10"> Liked';
    }

    var text = '<a href="#" onclick="toggleLike(\'' + liked + '\', \'' + quipId + '\')">' + likedTxt + '</a>';

    var likeSpan = document.getElementById("like-" + quipId);

    likeSpan.innerHTML = text;
  }

  function replySetOthers(quipId, display)
  {
    ['GetResponseBtn', 'ReQuipBtn', 'QuoteQuipBtn'].forEach(function(name) {
      var el = document.getElementById(name + '-' + quipId);
      if (el) el.style.display = display;
    });
  }

  function quoteQuipSetOthers(quipId, display)
  {
    ['AddChildBtn', 'GetResponseBtn', 'ReQuipBtn'].forEach(function(name) {
      var el = document.getElementById(name + '-' + quipId);
      if (el) el.style.display = display;
    });
  }

  function quoteQuip(quipId)
  {
    var txt = document.getElementById("AddChildTxt-" + quipId);
    var btn = document.getElementById("QuoteQuipBtn-" + quipId);

    if (txt.style.display === 'none' || txt.dataset.mode !== 'quote')
    {
      // Open in quote mode: hide the three sibling action buttons
      txt.dataset.mode  = 'quote';
      txt.style.display = 'block';
      txt.placeholder   = 'Add your comment to quote this quip...';
      btn.title         = 'Send Quote';
      quoteQuipSetOthers(quipId, 'none');
    }
    else
    {
      // Second click: cancel if empty, otherwise submit
      var raw = txt.value.trim();

      if (!raw)
      {
        txt.style.display = 'none';
        txt.value         = '';
        txt.dataset.mode  = '';
        btn.title         = 'Quote Quip';
        quoteQuipSetOthers(quipId, '');
        return;
      }

      // Server stores the quoted quip under the same "ReQuipId" attr used
      // for requips; text presence distinguishes QuoteQuip from ReQuip.
      var dataString = 'Text=' + encodeURIComponent(raw) +
                       '&ReQuipId=' + encodeURIComponent(quipId) +
                       '&Action=AddQuip';

      $.ajax({
               type: "POST",
               cache: false,
               url: '/domatar/quippin/Wui/QuipsWui',
               crossDomain: false,
               data: dataString,
               timeout: 10000000,
               success: function(json) { quoteQuipAdded(quipId); },
               error: function(jqXHR, textStatus, errorThrown) { alert(textStatus + ': ' + errorThrown); }
             });
    }
  }

  function quoteQuipAdded(quipId)
  {
    var txt = document.getElementById("AddChildTxt-" + quipId);
    var btn = document.getElementById("QuoteQuipBtn-" + quipId);

    txt.style.display = 'none';
    txt.value         = '';
    txt.dataset.mode  = '';
    btn.title         = 'Quote Quip';
    quoteQuipSetOthers(quipId, '');

    // Refresh the News Feed so the new quote-quip appears at the top.
    refreshFeed();
  }

  function reQuip(quipId)
  {
    var dataString = 'ReQuipId=' + encodeURIComponent(quipId) + '&Action=AddQuip';

    $.ajax({
             type: "POST",
             cache: false,
             url: '/domatar/quippin/Wui/QuipsWui',
             crossDomain: false,
             data: dataString,
             timeout: 10000000,
             success: function(json) { refreshFeed(); },
             error: function(jqXHR, textStatus, errorThrown) { alert(textStatus + " : " + errorThrown); }
           });
  }

  // Called after any action that adds a top-level quip to the feed.
  // Each feed page registers its own reload function on window:
  //   quips.html  → window.getQuips
  //   news.html   → window.getNews
  function refreshFeed()
  {
    if (typeof window.getNews === 'function')
      window.getNews();
    else if (typeof window.getQuips === 'function')
      window.getQuips();
  }

  function quipHtml(quip)
  {
    var quipId = quip.DomId;
    var quipUrlId = quip.QuipId;
    var quipDate = new Date(parseInt(quipUrlId)); // UTC Milliseconds
    var authorUrl = '/domatar/quippin/quips.html?user=' + encodeURIComponent(quip.UsrId);
    var quipUrl = '/domatar/quippin/quip.html?handle=' + encodeURIComponent(quip.Handle) + '&quipId=' + encodeURIComponent(quipUrlId);
    var liked = quip.Liked;

    var likedTxt;

    if (liked == 'True')
      likedTxt = '<img src="/domatar/quippin/liked.png" title="Liked" height="10" width="10"> Liked';
    else
      likedTxt = '<img src="/domatar/quippin/like.png" title="Not Liked" height="10" width="10"> Like';

    var quoteQuip;

    if (quip.Type == 'QuoteQuip')
    {
      var quoteQuipId = quip.QuoteQuipId;
      var quoteQuipDate = new Date(parseInt(quoteQuipId)); // UTC Milliseconds
      // Server returns QuoteHandle + QuoteHost; QuoteUsrId is not in the response.
      var quoteAuthorUrl = 'http://' + quip.QuoteHost + '/quippin/quips.html?user=' + encodeURIComponent(quip.QuoteHandle);
      var quoteQuipUrl   = 'http://' + quip.QuoteHost + '/quippin/quip.html?handle=' + encodeURIComponent(quip.QuoteHandle) + '&quipId=' + encodeURIComponent(quoteQuipId);

      quoteQuip =

        '<br/><br/><div id="' + quoteQuipId + '" style="border-style:ridge;">' +
        '<span><a href="' + quoteAuthorUrl + '">' + quip.QuoteName +
        '</a> @' + quip.QuoteHandle + ' ' +
        '<blockquote>' + htmlEncode(quip.QuoteText) +
        '</blockquote>' +
        ' <a href="' + quoteQuipUrl + '">' +
        'Quip</a> time: ' + quoteQuipDate.toLocaleString() + '</span>' +
        '</div>';
    }
    else
      quoteQuip = '';

    // For ReQuip: show a "Requip: <author> <time>" banner, then the original
    // quip content attributed to the original author.
    var repostHeader = '';
    var dispName     = quip.Name;
    var dispHandle   = quip.Handle && quip.Handle.indexOf('@') >= 0
                         ? quip.Handle : quip.Handle + '@' + quip.Host;
    var dispDate     = quipDate;
    var dispAuthorUrl = 'http://' + quip.Host + '/quippin/quips.html?user=' + encodeURIComponent(quip.Handle);
    var dispQuipUrl  = quipUrl;

    if (quip.Type == 'ReQuip')
    {
      repostHeader =
        '<div style="font-size:0.85em;color:#555;border-bottom:1px solid #ccc;margin-bottom:4px;padding-bottom:2px;">' +
        '&#8635;&nbsp;<strong>Requip:</strong> ' + htmlEncode(quip.Name) +
        ' (' + htmlEncode(dispHandle) + ') ' + quipDate.toLocaleString() +
        '</div>';
      var origHandle = quip.OrigHandle && quip.OrigHandle.indexOf('@') >= 0
                         ? quip.OrigHandle : quip.OrigHandle + '@' + quip.OrigHost;
      dispName      = quip.OrigName;
      dispHandle    = origHandle;
      dispDate      = new Date(parseInt(quip.OrigQuipId));
      dispAuthorUrl = 'http://' + (quip.OrigHost || quip.Host) + '/quippin/quips.html?user=' + encodeURIComponent(quip.OrigHandle || '');
      dispQuipUrl   = 'http://' + (quip.OrigHost || quip.Host) + '/quippin/quip.html?handle=' + encodeURIComponent(quip.OrigHandle || '') + '&quipId=' + encodeURIComponent(quip.OrigQuipId);
    }

    var childQuip;
    var parentId = quip.ParentId;

    // Compare the server-returned usrId (Handle + Host) against the usrId cookie.
    var myUsrId   = getCookie('usrId');
    var isOwnQuip = myUsrId && myUsrId.length > 0 && quipUsrId(quip) === myUsrId;

    if (isOwnQuip)
    {
      // Owner: show a delete button (works for both top-level and reply quips).
      childQuip = buttonStr(quipId, null, "DeleteQuipBtn", "Del", "deleteQuip", "Delete");
    }
    else if (parentId)
    {
      // Other user's reply on the current user's quip: block via BanChild.
      childQuip =
        buttonStr(quipId, parentId, "BlockUserBtn", "Block", "blockUser", "Block User");
    }
    else
    {
      // Other user's top-level quip in the feed: block via BanUser.
      childQuip = buttonStr(quipId, null, "BlockUserBtn", "Block", "blockTopUser", "Block User");
    }

    text =

     '<tr id="tr-' + quipId + '"><td>' +
     '<div id="' + quipId + '" style="border-style:ridge;color:black;background-color:#e5eecc;padding:4px;">' +
     repostHeader +
     '<span><a href="' + dispAuthorUrl + '">' + dispName +
     '</a> (' + dispHandle + ') ' +
     '<blockquote>' + htmlEncode(quip.Text) + quoteQuip +
     '</blockquote>' +
     ' <a href="' + dispQuipUrl + '">' + 'Quip</a> time: ' + dispDate.toLocaleString() + '</span>' +
     '<div><span id="like-' + quipId + '"><a href="#" onclick="toggleLike(\'' + liked + '\', \'' + quipId + '\')">' +
       likedTxt + '</a></span> ' + quip.SentimentNum + '</div>' +
     '<div class="quip-action-bar">' +

        buttonStr(quipId, parentId, "AddChildBtn",    "Add",   "addChild",   "Reply") +
        buttonStr(quipId, parentId, "GetResponseBtn", "Get",   "getChildren","Get Replies") +
        buttonStr(quipId, parentId, "ReQuipBtn",      "Share", "reQuip",     "Repost") +
        buttonStr(quipId, parentId, "QuoteQuipBtn",   "Quote", "quoteQuip",  "Quote Quip") +

     childQuip +
     '<textarea id="AddChildTxt-' + quipId + '" name="AddChildTxt-' + quipId + '" rows="10" cols="50" style="display:none;margin-top:4px;"></textarea>' +
     '</div>' +
     '<div id="children-' + quipId + '"></div>' +
     '</div>' +
     '</td></tr>';

     return text;
  }

  /* ---- Action button icons (Twitter/X style) ---- */

  // Reconstruct the full usrId from the Handle and Host returned by the server.
  // For local quippin users the server omits the @host suffix from Handle, so
  // we append "@" + Host to make it comparable to the usrId cookie value.
  function quipUsrId(quip)
  {
    var h = quip.Handle || '';
    return h.indexOf('@') >= 0 ? h : (h + '@' + (quip.Host || ''));
  }

  function deleteQuip(quipId)
  {
    if (!confirm('Delete this quip?')) return;

    $.ajax({
             type: "POST",
             cache: false,
             url: '/domatar/quippin/Wui/QuipWui',
             crossDomain: false,
             data: 'Action=DeleteQuip&QuipId=' + encodeURIComponent(quipId),
             timeout: 10000000,
             success: function(json) { childQuipRemoved(quipId); refreshFeed(); },
             error: function(jqXHR, textStatus, errorThrown) { alert(textStatus + ': ' + errorThrown); }
           });
  }

  function blockTopUser(quipId)
  {
    if (!confirm('Block this user from your quips?')) return;

    $.ajax({
             type: "POST",
             cache: false,
             url: '/domatar/quippin/Wui/QuipsWui',
             crossDomain: false,
             data: 'Action=BanUser&QuipId=' + encodeURIComponent(quipId),
             timeout: 10000000,
             success: function(json) { childQuipRemoved(quipId); },
             error: function(jqXHR, textStatus, errorThrown) { alert(textStatus + ': ' + errorThrown); }
           });
  }

  var QUIP_ICONS = {
    /* Reply: speech bubble */
    AddChildBtn:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
      ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>' +
      '</svg>',

    /* Expand replies: chevron down */
    GetResponseBtn:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
      ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<polyline points="6 9 12 15 18 9"/>' +
      '</svg>',

    /* Repost: two cycling arrows */
    ReQuipBtn:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
      ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<polyline points="17 1 21 5 17 9"/>' +
      '<path d="M3 11V9a4 4 0 0 1 4-4h14"/>' +
      '<polyline points="7 23 3 19 7 15"/>' +
      '<path d="M21 13v2a4 4 0 0 1-4 4H3"/>' +
      '</svg>',

    /* Quote: speech bubble with a pen line inside */
    QuoteQuipBtn:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
      ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>' +
      '<line x1="9" y1="10" x2="15" y2="10"/>' +
      '<line x1="9" y1="14" x2="12" y2="14"/>' +
      '</svg>',

    /* Remove response: trash can */
    RemoveChildBtn:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
      ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<polyline points="3 6 5 6 21 6"/>' +
      '<path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>' +
      '<path d="M10 11v6"/><path d="M14 11v6"/>' +
      '<path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>' +
      '</svg>',

    /* Block user: circle with a diagonal slash */
    BlockUserBtn:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
      ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<circle cx="12" cy="12" r="10"/>' +
      '<line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>' +
      '</svg>',

    /* Delete own quip: trash can (same shape as RemoveChildBtn) */
    DeleteQuipBtn:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
      ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<polyline points="3 6 5 6 21 6"/>' +
      '<path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>' +
      '<path d="M10 11v6"/><path d="M14 11v6"/>' +
      '<path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>' +
      '</svg>'
  };

  function buttonStr(quipId, parentId, name, text, onClick, tip)
  {
      var id       = name + '-' + quipId;
      var paramStr = parentId ? ("'" + quipId + "','" + parentId + "'")
                              : ("'" + quipId + "'");
      var icon     = QUIP_ICONS[name] ||
                     ('<svg viewBox="0 0 24 24" width="18" height="18" fill="none"' +
                      ' stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/></svg>');

      /* colour class drives the hover tint (reply=blue, repost=green, quote=purple, danger=red) */
      var colourClass = name === 'ReQuipBtn'      ? 'qa-green'  :
                        name === 'QuoteQuipBtn'   ? 'qa-purple' :
                        name === 'RemoveChildBtn' ? 'qa-red'    :
                        name === 'BlockUserBtn'   ? 'qa-red'    :
                        name === 'DeleteQuipBtn'  ? 'qa-red'    : 'qa-blue';

      return '<button class="quip-action-btn ' + colourClass + '"' +
             ' title="' + tip + '"' +
             ' name="' + id + '" id="' + id + '"' +
             ' onclick="' + onClick + '(' + paramStr + ')">' +
             icon +
             '</button>';
  }

  function buttonSpan(quipId, tip)
  {
      /* no longer used for the new icon buttons; kept for back-compat */
      return '';
  }

  /* Inject action-button CSS once on first load */
  /* Append the logged-in user's name to the page <h1> in a smaller font. */
  (function injectCurrentUserName()
  {
    var name = getCookie('usrName');
    if (!name || !name.length) return;

    function doInject()
    {
      var h1 = document.querySelector('h1');
      if (!h1 || h1.querySelector('.quip-current-user')) return;
      var span = document.createElement('span');
      span.className = 'quip-current-user';
      span.style.cssText = 'font-size:0.55em;font-weight:normal;margin-left:0.6em;color:#555;vertical-align:middle;';
      span.textContent = name;
      h1.appendChild(span);
    }

    if (document.readyState === 'loading')
      document.addEventListener('DOMContentLoaded', doInject);
    else
      doInject();
  })();

  (function injectQuipActionCss()
  {
      if (document.getElementById('quip-action-css')) return;
      var s = document.createElement('style');
      s.id = 'quip-action-css';
      s.textContent =
        '.quip-action-bar { display:flex; gap:4px; align-items:center; padding:4px 0; }' +
        '.quip-action-btn {' +
        '  display:inline-flex; align-items:center; justify-content:center;' +
        '  width:34px; height:34px; padding:0; border:none; background:none;' +
        '  border-radius:50%; cursor:pointer; color:#536471;' +
        '  transition:background .15s, color .15s; flex-shrink:0;' +
        '}' +
        '.quip-action-btn:hover { background:rgba(29,155,240,.1); }' +
        '.quip-action-btn.qa-blue:hover  { color:#1d9bf0; background:rgba(29,155,240,.1); }' +
        '.quip-action-btn.qa-green:hover { color:#00ba7c; background:rgba(0,186,124,.1); }' +
        '.quip-action-btn.qa-purple:hover{ color:#7856ff; background:rgba(120,86,255,.1); }' +
        '.quip-action-btn.qa-red:hover   { color:#f4212e; background:rgba(244,33,46,.1); }' +
        '.quip-action-btn svg { display:block; pointer-events:none; }';
      document.head.appendChild(s);
  })();

  function htmlEncode(str)
  {
    if (str == null) 
      return '';
    
    var cr = true;
    var s = "";
    var c = "";

    for (var i = 0; i < str.length; i++)
    {
      var prevc = c;
      c = str.charAt(i);

      switch (c)
      {
        case '&':
          s += "&amp;";
          cr = false;
        break;

        case '<':
          s += "&lt;";
          cr = false;
        break;

        case '>':
          s += "&gt;";
          cr = false;
        break;

        case '"':
          s += "&quot;";
          cr = false;
        break;

        case "'":
          s += "&apos;";
          cr = false;
        break;

        case '\n':
          s += "<br/>";
          cr = true;
        break;

        case ' ':
          if (cr || prevc == ' ')
            s += "&nbsp;"
          else
            s += ' ';
        break;

        default:
          s += c;
          cr = false;
        break;
      }
    }

    return s;
  }
