<%@ page language="java" contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="pwm" prefix="pwm" %>
<jsp:root xmlns:jsp="http://java.sun.com/JSP/Page" xmlns:c="http://java.sun.com/jsp/jstl/core" version="2.0">
    <jsp:directive.page import="java.util.*"/>
    <jsp:directive.page import="java.text.*"/>
    <jsp:directive.page import="javax.servlet.http.*"/>
    <jsp:directive.page import="javax.servlet.jsp.*"/>
    <jsp:directive.page import="javax.servlet.jsp.tagext.*"/>
    <jsp:directive.page import="com.example.util.*"/>
    <jsp:directive.page import="com.example.beans.*"/>
<html lang="<pwm:value name='<%=PwmValue.localeCode%>'/>" dir="<pwm:value name='<%=PwmValue.localeDir%>'/>">
<jsp:include page="fragment/header.jsp"/>
<body>
    <div id="wrapper" class="login-wrapper">
        <jsp:include page="fragment/header-body.jsp">
            <jsp:param name="pwm.PageName" value="Title_Login"/>
        </jsp:include>
        <div id="centerbody">
            <h1 id="page-content-title"><pwm:display key="Title_Login" displayIfMissing="true"/></h1>
            <p><pwm:display key="Display_LoginPasswordOnly"/></p>
            <form action="<pwm:current-url/>" method="post" name="login-password" enctype="application/x-www-form-urlencoded" id="login-password" autocomplete="off" class="pwm-form">
                <%@ include file="/WEB-INF/jsp/fragment/message.jsp" %>
                <h2><label for="password"><pwm:display key="Field_Password"/></label></h2>
                <input type="<pwm:value name='<%=PwmValue.passwordFieldType%>'/>" name="password" id="password" class="inputfield passwordfield" placeholder="<pwm:display key='Field_Password'/>" <pwm:autofocus/> />
                <div class="buttonbar">
                    <button type="submit" class="btn" name="button" id="submitBtn">
                        <pwm:if test="<%=PwmIfTest.showIcons%>"><span class="btn-icon pwm-icon pwm-icon-sign-in"></span></pwm:if>
                        <pwm:display key="Button_Login"/>
                    </button>
                    <input type="hidden" name="processAction" value="login">
                    <%@ include file="/WEB-INF/jsp/fragment/cancel-button.jsp" %>
                    <input type="hidden" id="pwmFormID" name="pwmFormID" value="<pwm:FormID/>"/>
                </div>
            </form>
            <br/>
        </div>
        <div class="push"></div>
    </div>
    <%@ include file="/WEB-INF/jsp/fragment/cancel-form.jsp" %>
    <jsp:include page="fragment/footer.jsp"/>
</body>
</html>
           