@echo off
:: Gradle wrapper Windows script

set DIR=%~dp0
set APP_BASE_NAME=%~n0
set CLASSPATH=%DIR%\gradle\wrapper\gradle-wrapper.jar

set DEFAULT_JVM_OPTS=

java %DEFAULT_JVM_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
