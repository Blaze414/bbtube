#!/bin/sh
# Builds app/bbtube.jar (a MIDP 2.0 MIDlet for BlackBerry OS) from src/ with the open-source tools in tools/.
set -e
cd "$(dirname "$0")"
T=tools
LIB=$T/cldcapi11-2.0.4.jar:$T/midpapi20-2.0.4.jar:$T/microemu-jsr-135-2.0.4.jar
# fetch the toolchain from Maven Central on first build
M=https://repo1.maven.org/maven2
for j in org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar org/microemu/cldcapi11/2.0.4/cldcapi11-2.0.4.jar \
         org/microemu/midpapi20/2.0.4/midpapi20-2.0.4.jar org/microemu/microemu-jsr-135/2.0.4/microemu-jsr-135-2.0.4.jar \
         net/sf/proguard/proguard-base/6.2.2/proguard-base-6.2.2.jar; do
  [ -f "$T/$(basename $j)" ] || { mkdir -p $T && curl -fsSL -o "$T/$(basename $j)" "$M/$j"; }
done
rm -rf build && mkdir -p build/classes build/test app

# Java 1.1 class files against the phone's API (CLDC 1.1 + MIDP 2.0 + JSR-135), not the desktop JDK
java -jar $T/ecj-3.33.0.jar -source 1.3 -target 1.1 -nowarn -bootclasspath $LIB -d build/classes src/BBTube.java
cp icon.png res/*.png build/classes/
jar cfm build/raw.jar src/manifest.txt -C build/classes .

# the phone's Java only loads preverified classes (StackMap attributes); ProGuard -microedition adds them
java -jar $T/proguard-base-6.2.2.jar -injars build/raw.jar -outjars app/bbtube.jar -libraryjars "$LIB" \
  -microedition -dontshrink -dontoptimize -dontobfuscate -dontnote

javac -nowarn -cp app/bbtube.jar:$LIB -d build/test src/TestBBTube.java
java -cp build/test:app/bbtube.jar:$LIB TestBBTube
python3 test_bbtube.py
ls -l app/bbtube.jar
