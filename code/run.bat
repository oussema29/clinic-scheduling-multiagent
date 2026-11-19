@echo off
rem Usage : run.bat                 journee type
rem         run.bat aleatoire 20 42   20 demandes aleatoires, graine 42
rem         run.bat --gui             avec l interface RMA de JADE
java -cp "lib\jade.jar;out" clinique.Main %*
