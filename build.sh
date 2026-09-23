#!/usr/bin/env sh
# Compila o Post Editor sem Maven. Requer um JDK 8+ (javac e jar no PATH).
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -d build/classes @build/sources.txt
printf 'Main-Class: posteditor.App\n' > build/manifest.txt
jar cfm post-editor.jar build/manifest.txt -C build/classes .
echo
echo "Pronto! Execute com:  java -jar post-editor.jar"
