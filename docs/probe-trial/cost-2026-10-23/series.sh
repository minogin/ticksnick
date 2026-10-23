#!/bin/sh
# Probe off/on, alternating, on the same Java 26 bench. Each run's walk cost and the time it ended.
D=/c/Users/Andrey/AppData/Local/Temp/claude/C--idea-maven-graph/f65929a7-0888-4df6-adb9-faa15b23b38c/scratchpad/probecost
cd /c/idea/ticksnick
./gradlew -q classes java22Classes > /dev/null 2>&1
for arm in off on off on off on; do
  echo "=== probe=$arm $(date +%H:%M:%S)"
  ./gradlew -q probeRun --args="--probe=$arm --seconds=15" > "$D/run-$arm-$(date +%H%M%S).log" 2>&1
  grep -h -E "BROKEN|probe:|slot walk|achieved step|ticks:|calls" "$D"/run-$arm-*.log | tail -6
done
echo "=== done $(date +%H:%M:%S)"
