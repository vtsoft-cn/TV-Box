
@if EXIST "%~dp0\gradlew.bat" (
    @echo off
    setlocal
    set "APP_HOME=%~dp0"
    set "APP_NAME=Gradle"
    set "APP_BASE_NAME=%~n0"
    set "DEFAULT_JVM_OPTS=-Xmx64m -Xms64m"

    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA_EXE=java.exe"
    )

    if not exist "%JAVA_EXE%" (
        echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
        echo.
        echo Please set the JAVA_HOME variable in your environment to match the
        echo location of your Java installation.
        exit /b 1
    )

    set "CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"

    set "APP_OPTS=%DEFAULT_JVM_OPTS%"

    "%JAVA_EXE%" %APP_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

    if %ERRORLEVEL% neq 0 (
        exit /b %ERRORLEVEL%
    )
) else (
    echo "Can't find '%~dp0\gradlew.bat'" 1>&2
    exit /b 1
)
