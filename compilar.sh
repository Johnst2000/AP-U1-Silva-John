#!/usr/bin/env bash
set -e
rm -rf out
mkdir out
javac --release 21 -d out $(find src -name "*.java")
echo "Compilación correcta: clases en ./out"
