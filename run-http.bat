@echo off
set PORT=%1
if "%PORT%"=="" set PORT=3001
"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe" -jar "%~dp0build\libs\zayit-mcp.jar" --port %PORT%
