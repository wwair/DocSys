<%@ page language="java"  import="com.DocSystem.controller.*" contentType="text/html; charset=utf-8" pageEncoding="utf-8"%>
<%
String officeEditorApi = BaseController.getOfficeEditor();
%>

<!DOCTYPE html>
<html style="height: 100%;">
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>
    <title>Office Editor</title>
    <script src="static/scripts/jquery.min.js"></script>
    <script type="text/javascript" src="js/common.js"></script>
    <script type="text/javascript" src="js/base64.js"></script>
    <script type="text/javascript" src="js/DocSys.js"></script>
    <script type="text/javascript" src="<%=officeEditorApi%>"></script>
</head>
<body style="height: 100%; margin: 0;">
    <div id="placeholder" style="height: 100%"></div>
    <script type="text/javascript">
    	var editor;
	    var docInfo = getDocInfoFromRequestParamStr();	    
	    var fileType = getFileSuffix(docInfo.name);
	    var documentType = getDocumentType(fileType);
	    var title = docInfo.name;
	    var key = docInfo.docId + "";
	    
	    var fileLink = getQueryString("fileLink");	    
	    if(!fileLink || fileLink != null)
	    {
	    	getDocFileLink(docInfo, showOffice);
	    }
	    else
	    {
	    	showOffice(fileLink);
	    }
	    
	    console.log("showOffice() title=" + title + " key=" + key + " fileType=" + fileType + " documentType=" + documentType + " fileLink="+fileLink);
	    
	    function showOffice(fileLink)
	   	{
	        window.docEditor = new DocsAPI.DocEditor("placeholder",
	            {
	                "document": {
	                    "fileType": fileType,
	                    "key": key,
	                    "title": title,
	                    "url": fileLink,
	                },
	                "documentType": documentType,
	                //"editorConfig": {
	                //    "callbackUrl": saveUrl,
	                //    "lang": "zh-CN",
	                //},
	                "height": "100%",
	                "width": "100%"
	            });
	   	}
    </script>
</body>
</html>