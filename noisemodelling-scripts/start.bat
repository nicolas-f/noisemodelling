@echo off
set DIR=%~dp0
if "%DIR:~-1%"=="\" set DIR=%DIR:~0,-1%
set CLASSPATH=%DIR%\classes;%DIR%\lib\*
java -cp "%CLASSPATH%" org.noise_planet.noisemodelling.webserver.Main
