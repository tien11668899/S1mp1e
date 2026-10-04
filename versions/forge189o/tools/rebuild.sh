#!/bin/bash
# 全部重建：重映射 binpatched/原版 → DonorGen → 目標清單 → 打包進 forge189o
set -e
cd /c/Temp/s1port/forgetool
J="/c/Users/Administrator/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin"; CP=$(cat ../forgebin/cp.txt)
B=$(cygpath -w /c/Users/Administrator/.gradle/caches/minecraft/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/stable/20/forge-1.8.9-11.15.1.2318-1.8.9-binpatched.jar)
V=$(cygpath -w ~/.gradle/caches/fabric-loom/1.8.9/minecraft-merged.jar)
python gen_srg2rt.py
R=/c/Users/Administrator/source/S1mp1e/versions/forge189o/src/main/java/dev/s1mp1e/forge/remap
"$J/javac" -encoding UTF-8 -cp "$CP" -d cls DonorGen.java ListDonors.java Collide.java $R/ClassFixer.java $R/SrgMap.java
for ns in named intermediary; do
  "$J/java" -cp "$CP" net.fabricmc.tinyremapper.Main "$B" patched-$ns.jar tool.tiny official $ns 2>&1 | grep -iv "^\s*at \|finished" || true
  "$J/java" -cp "$CP" net.fabricmc.tinyremapper.Main "$V" vanilla-$ns.jar tool.tiny official $ns 2>&1 | grep -iv "^\s*at \|finished" || true
  "$J/java" -cp "$CP;cls" ListDonors vanilla-$ns.jar > vkinds-$ns.txt
  rm -rf out-$ns; "$J/java" -cp "$CP;cls" DonorGen patched-$ns.jar vanilla-$ns.jar out-$ns srg2rt-$ns.txt
  "$J/java" -cp "$CP;cls" ListDonors out-$ns/donor.jar > out-$ns/donors.txt
  python targets.py $ns
done
"$J/java" -cp "$CP;cls" Collide patched-named.jar patched-intermediary.jar tool.tiny 2>/dev/null | tail -5
bash package.sh > /dev/null && echo packaged
