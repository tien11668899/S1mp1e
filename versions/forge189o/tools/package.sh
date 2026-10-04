#!/bin/bash
# 把 DonorGen 的產物打包進 versions/forge189o：libs/ 兩個 Forge 函式庫 jar、resources/s1forge/ 的捐贈資料、產生的 mixin 目標清單
set -e
T=/c/Temp/s1port/forgetool
P=/c/Users/Administrator/source/S1mp1e/versions/forge189o
J="/c/Users/Administrator/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.3.9-hotspot/bin"
mkdir -p $P/libs $P/src/main/resources/s1forge
for ns in named intermediary; do
  rm -rf $T/stage-$ns && mkdir -p $T/stage-$ns && cd $T/stage-$ns
  unzip -q $T/out-$ns/lib.jar
  # 我們自己改寫（overlay）的類別：從函式庫拿掉，改由 forge189o 原始碼提供
  while read -r c; do [ -n "$c" ] && rm -f $c.class; done < $T/overlay.txt
  if [ $ns = intermediary ]; then
    cat > fabric.mod.json <<'J2'
{ "schemaVersion": 1, "id": "s1forge-lib", "version": "11.15.1.2318", "name": "Forge 1.8.9 library (LGPL-2.1, for S1mp1e Forge compat)", "license": "LGPL-2.1" }
J2
  fi
  rm -f $P/libs/s1forge-lib-$ns.jar $P/libs/s1forge-lib-$ns-11.15.1.2318.jar
  "$J/jar" cf $P/libs/s1forge-lib-$ns.jar .
  # flatDir 座標用的檔名（正式版 jar-in-jar）
  [ $ns = intermediary ] && mv $P/libs/s1forge-lib-$ns.jar $P/libs/s1forge-lib-$ns-11.15.1.2318.jar
  cp $T/out-$ns/donor.jar $P/src/main/resources/s1forge/donor-$ns.zip
  cp $T/out-$ns/access.txt $P/src/main/resources/s1forge/access-$ns.txt
  cp $T/srg2rt-$ns.txt $P/src/main/resources/s1forge/srg2rt-$ns.txt
  # Forge/FML 自己的資源（語言檔、貼圖）清單：執行期解壓到 .s1forge/forgeres 當資源包
  [ $ns = named ] && find assets/forge assets/fml -type f | sort > $P/src/main/resources/s1forge/forgeres.txt
done
# mixin 目標清單（類別、介面各一個；named／intermediary 各一份，執行期由外掛挑）
G=$P/src/main/java/dev/s1mp1e/forge/mixin/donor
mkdir -p $G
for ns in named intermediary; do
  N=$( [ $ns = named ] && echo Named || echo Intermediary )
  cls=$(grep '^C ' $T/out-$ns/targets.txt | awk '{printf "        \"%s\",\n", $2}')
  itf=$(grep '^I ' $T/out-$ns/targets.txt | awk '{printf "        \"%s\",\n", $2}')
  printf 'package dev.s1mp1e.forge.mixin.donor;\n\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.Pseudo;\n\n/** 自動產生（forgetool/package.sh）：要移植 Forge 新增成員的原版類別（%s 名稱）。本體是空的，實際工作在 DonorPlugin.preApply。 */\n@Pseudo\n@Mixin(targets = {\n%s\n}, priority = 1)\npublic abstract class Donor%s {\n}\n' "$ns" "$cls" "$N" > $G/Donor$N.java
  printf 'package dev.s1mp1e.forge.mixin.donor;\n\nimport org.spongepowered.asm.mixin.Mixin;\nimport org.spongepowered.asm.mixin.Pseudo;\n\n/** 自動產生（forgetool/package.sh）：要移植 Forge 新增成員的原版介面（%s 名稱）。 */\n@Pseudo\n@Mixin(targets = {\n%s\n}, priority = 1)\npublic interface DonorItf%s {\n}\n' "$ns" "$itf" "$N" > $G/DonorItf$N.java
done
ls -la $P/libs $P/src/main/resources/s1forge
