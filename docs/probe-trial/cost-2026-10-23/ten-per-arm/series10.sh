#!/bin/sh
# Probe off/on, alternating, ten per arm, on the same Java 26 bench.
D=/c/Users/Andrey/AppData/Local/Temp/claude/C--idea-maven-graph/f65929a7-0888-4df6-adb9-faa15b23b38c/scratchpad/probecost/series10
cd /c/idea/ticksnick
n=0
for i in 1 2 3 4 5 6 7 8 9 10; do
  for arm in off on; do
    n=$((n+1))
    ./gradlew -q probeRun --args="--probe=$arm --seconds=15" > "$D/$(printf %02d $n)-$arm.log" 2>&1
  done
done
echo "done $(date +%H:%M:%S)"
