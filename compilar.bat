@echo off
rem Compila todo el proyecto con Java 21. Requiere el JDK 21 (javac) en el PATH.
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > fuentes.tmp
javac --release 21 -d out @fuentes.tmp
if errorlevel 1 (
  del fuentes.tmp
  echo La compilacion fallo.
  exit /b 1
)
del fuentes.tmp
echo Compilacion correcta: clases en .\out
