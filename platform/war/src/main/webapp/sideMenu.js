/**
 * 
 */
 
function createSide() {
	
  var text = '<div id="OpenDiv" style="display:block;position:fixed;left:0;top:0;border:none;color: #fff;background-color: rgba(0,0,0, 0.99);"> ' +
             '<span style="font-size:30px;cursor:pointer" onclick="openSide()">&#9776;</span>' +
             '</div>' +

			  '<div id="SideMenuDiv" style="display: block">  <!-- display: none --> ' +
			  '	  <div id="inSide" class="overlay"> ' +
//			  '	    <a href="javascript:void(0)" class="closebtn" onclick="closeSide()">&times;</a> ' +
				
              '      <span class="closebtn" onclick="closeSide()">&times;</span>' +
			  '	     <br/> ' +
			  '	      <iframe name="SideMenuFrame" id="SideMenuFrame" width="100%" height="95%" align="center" scrolling="auto" marginheight="5" marginwidth="5" frameborder="0" seamless="seamless"></iframe> ' +
				
			  '	   </div>' +
			  '	</div>'	;

    var sideDiv = document.getElementById("SideDiv");

    sideDiv.innerHTML = text;
}
 
function openSide() {
    var width = window.innerWidth || document.documentElement.clientWidth || document.body.clientWidth; 
  
    if (width > 500)
      document.getElementById("inSide").style.width = "20%";
    else
      document.getElementById("inSide").style.width = "85%";
}

function closeSide() {
    document.getElementById("inSide").style.width = "0%";
}

// alert("1");

createSide();
document.getElementById('SideMenuFrame').src = "";
document.getElementById('SideMenuFrame').src = "sideMenu.html";
