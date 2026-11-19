@echo off
rem Compile tout le projet (JDK 17+ ; teste avec JDK 23)
if not exist out mkdir out
javac -encoding UTF-8 -cp lib\jade.jar -sourcepath src -d out src\clinique\Main.java
