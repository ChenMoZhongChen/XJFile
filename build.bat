@echo off
rem ============================================================
rem  XJ File 一键构建（debug APK）
rem
rem  用法：
rem    build.bat                 只打 debug APK
rem    build.bat clean           先 clean 再打包
rem    build.bat --offline       其它 Gradle 参数原样透传
rem
rem  说明：
rem    - JAVA_HOME / GRADLE_USER_HOME 由 gradlew.bat 的默认值兜底
rem      （K:\Android_Studio_Setup\jbr 和工程内 .gradle-home），不用手设。
rem    - 本机沙箱下 Gradle 起不来（projectDir.canWrite() 恒 false），
rem      本脚本仍需用“完全权限”方式执行，详见 PHASE3_HANDOFF.md 第 1 节。
rem    - 输出故意只用 ASCII，避免 pwsh 按 UTF-8 解码 GBK 输出时乱码。
rem ============================================================
setlocal
cd /d "%~dp0"

call gradlew.bat %* assembleDebug --console=plain
if errorlevel 1 goto :fail

set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" goto :noapk
echo.
echo [build.bat] OK -^> %APK%
exit /b 0

:fail
echo.
echo [build.bat] FAILED - see Gradle output above.
exit /b 1

:noapk
echo.
echo [build.bat] Gradle succeeded but APK is missing: %APK%
exit /b 1
