#!/bin/sh
# Builds jimagePatcher.jar. Needs a JDK 11 or newer; the jar runs on Java 11 and newer.
# The JDK is taken from $JAVA_HOME, or else from the PATH. If the JDK has no javac or jar
# launcher (e.g. some Linux packages), the tool is started from its module with "java -m".
set -e
cd "$(dirname "$0")"

BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVA="${BIN}java"
if command -v "${BIN}javac" >/dev/null 2>&1; then JAVAC="${BIN}javac"; else JAVAC="$JAVA -m jdk.compiler/com.sun.tools.javac.Main"; fi
if command -v "${BIN}jar" >/dev/null 2>&1; then JAR="${BIN}jar"; else JAR="$JAVA -m jdk.jartool/sun.tools.jar.Main"; fi
SOURCES="src/module-info.java src/jimagePatcher/Run.java"

rm -rf build
mkdir build
if $JAVAC --release 11 -Xlint:all -d build/classes $SOURCES 2>build/javac.log; then
	cat build/javac.log >&2
elif grep -q "release version 11 not supported" build/javac.log; then
	# a runtime without lib/ct.sym cannot compile against the Java 11 API
	echo "Warning: this JDK does not support --release 11, compiling with --source 11 --target 11 instead" >&2
	$JAVAC --source 11 --target 11 -Xlint:all,-options -d build/classes $SOURCES
else
	cat build/javac.log >&2
	exit 1
fi
$JAR --create --file jimagePatcher.jar --manifest src/META-INF/MANIFEST.MF --main-class jimagePatcher.Run -C build/classes .
echo "Created jimagePatcher.jar"
