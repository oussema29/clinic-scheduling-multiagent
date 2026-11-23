@echo off
rem V3 : compare FIFO, PRIORITE et NEGOCIATION sur les memes journees.
rem Usage : campagne.bat [N] [graine ...]     (defaut : 50 patients, graines 42 7 123 2026 1)
setlocal
set N=%~1
if "%N%"=="" set N=50
shift
set GRAINES=
:boucle
if "%~1"=="" goto fin
set GRAINES=%GRAINES% %~1
shift
goto boucle
:fin
if "%GRAINES%"=="" set GRAINES=42 7 123 2026 1
for %%G in (%GRAINES%) do (
  for %%S in (fifo priorite negociation) do (
    java -cp "lib\jade.jar;out" clinique.Main campagne %%S %N% %%G
  )
)
python comparer.py %N% %GRAINES%
