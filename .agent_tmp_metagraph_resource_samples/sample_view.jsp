<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="/WEB-INF/views/common/header.jsp" %>
<html>
<head>
    <script src="/resources/js/survey/surveyRegist.js"></script>
</head>
<body>
    <form action="/survey/regist.do" method="post">
        <input type="text" name="surveyTitle" id="surveyTitle" />
        <input type="hidden" name="surveyId" value="123" />
        <button type="submit">Submit</button>
    </form>
</body>
</html>
