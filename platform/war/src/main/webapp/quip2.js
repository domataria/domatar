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
      text += quipHtml(quipArray[i]);

    text += '</table></div><br />';

    var childrenDiv = document.getElementById("children-" + parentId);

    childrenDiv.innerHTML = text;

    //$("#children-" + quipId).html(text);  
  }
    
  function addChild(quipId)
  {
    var txt = document.getElementById("AddChildTxt-" + quipId);
    var btn = document.getElementById("AddChildBtn-" + quipId);
    
  if (txt.style.display == 'none')  
  {
    txt.style.display = 'block';  
    btn.textContent = ' Send Response ';
  } 
  else
  {
      var text       = encodeURIComponent(txt.value);  
    var dataString = 'Text=' + text + '&ParentId=' + encodeURIComponent(quipId) + '&Action=AddQuip';  
           
      var urlString = '/domatar/quippin/Wui/QuipsWui';    
         
      $.ajax({
               type: "POST",
               cache: false,     
               url: urlString, 
               crossDomain: false,
         data: dataString,
         timeout: 10000000,
             success: function(json) {childAdded(quipId);},
         error: function(jqXHR, textStatus, errorThrown) {alert(textStatus + " : " + errorThrown);}
           });
  }
  }
  
  function childAdded(quipId)
  {
    var txt = document.getElementById("AddChildTxt-" + quipId);
    var btn = document.getElementById("AddChildBtn-" + quipId);
      
    txt.style.display = 'none';  
    btn.textContent = ' Add Response ';
    
    getChildren(quipId);
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
  
  function requip(quipId)
  {
    
  }
  
  function quipHtml(quip)
  {
    var domId = quip.DomId;
    var quipId = quip.QuipId;
    var quipDate = new Date(parseInt(quipId)); // UTC Milliseconds 
    var authorUrl = 'quippin/quips/' + quip.Handle;
    var quipUrl = 'quippin/quip/' + quip.Handle + "/" + quipId;
    var liked = quip.Liked;
    var quipId = quip.DomId;
  
    var likedTxt;
  
    if (liked == 'True')
      likedTxt = '<img src="/domatar/quippin/liked.png" title="Liked" height="10" width="10"> Liked';
    else
    likedTxt = '<img src="/domatar/quippin/like.png" title="Not Liked" height="10" width="10"> Like';
      
    text = 
      
     '<tr><td style="font-size:100%;border:2px ridge;background-color:#e5eecc;">' +
     '<div id="' + quipId + '">' +
     '<span><a href="http://' + authorUrl + '">' + quip.Name + 
     '</a> @' + quip.Handle + ' ' +
     '<blockquote>' + htmlEncode(quip.Text) + '</blockquote>' +
     ' <a href="http://' + quipUrl + '">' + 'Quip</a> time: ' + quipDate.toLocaleString() + '</span>' +
     '<div><span id="like-' + domId + '"><a href="#" onclick="toggleLike(\'' + liked + '\', \'' + domId + '\')">' + 
       likedTxt + '</a></span> ' + quip.SentimentNum + '</div>' +
     '</div>' +
     '<div style="width:800px; padding-right:15px; margin-right:15px; float:left; text-align:left;">' +
     '<button style="font-weight: bold;" name="AddChildBtn-' + quipId + '" id="AddChildBtn-' + quipId + 
     '" onclick="addChild(\'' + quipId + '\')"> Add Response </button>&nbsp;' +
     '<button style="font-weight: bold;" name="GetResponseBtn-' + quipId + '" id="Button-' + quipId + 
     '" onclick="getChildren(\'' + quipId + '\')"/>&nbsp;Get Responses&nbsp;</button>&nbsp;' +
     '<button style="font-weight: bold;" name="ReQuipBtn-' + quipId + '" id="Button-' + quipId + 
     '" onclick="reQuip(\'' + quipId + '\')"/>&nbsp;&nbsp;&nbsp;Requip&nbsp;&nbsp;&nbsp;</button>&nbsp;' +
     '<button style="font-weight: bold;" name="QuoteQuipBtn-' + quipId + '" id="Button-' + quipId + 
     '" onclick="quoteQuip(\'' + quipId + '\')"/>&nbsp;&nbsp;&nbsp;QuoteQuip&nbsp;&nbsp;&nbsp;</button>&nbsp;' +
   '<textarea id="AddChildTxt-' + quipId + '" name="AddChildTxt-' + quipId + '" rows="10" cols="50" style="display: none"></textarea><br /><br />' +
   '</div>' +
   '<br/><br/>' +
     '<p><div id="children-' + quipId + '">' +
     '</div></p>' +
     '</div>' +
     '</td></tr>';

     return text;
  }
  
  function htmlEncode(str) 
  {
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

