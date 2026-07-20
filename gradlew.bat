@rem Gradle wrapper for Windows
@echo off
set DIRNAME=%~dp0
set JAVA_HOME=%JAVA_HOME%\bin\java.exe
if not exist "%JAVA_HOME%" set JAVA_HOME=D:\Android\Android Studio\jbr\bin\java.exe
"%JAVA_HOME%" -classpath "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
