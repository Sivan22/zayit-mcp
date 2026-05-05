@echo off
set PORT=%1
if "%PORT%"=="" set PORT=3001
java -jar "%~dp0build\libs\zayit-mcp.jar" --port %PORT%
