#!/usr/bin/env bash
# 打包安卓 APK：Apktool（自带 aapt2 与 smali）组装，uber-apk-signer 签名（v1/v2/v3）。
# 用法：KS=/path/renwuzhi.jks KS_PASS=*** ./build-apk.sh
set -euo pipefail
cd "$(dirname "$0")"
TOOLS=${TOOLS:-.tools}; mkdir -p "$TOOLS" out
[ -f "$TOOLS/apktool.jar" ] || curl -sSL -o "$TOOLS/apktool.jar" https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar
[ -f "$TOOLS/signer.jar" ] || curl -sSL -o "$TOOLS/signer.jar" https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar
python3 build.py
rm -rf out/app && cp -r android out/app && mkdir -p out/app/assets/www
cp dist/index.html dist/sample.html dist/evidence.js dist/extraction.html out/app/assets/www/
java -jar "$TOOLS/apktool.jar" b out/app -o out/unsigned.apk
: "${KS:?请用 KS 指定签名密钥（.jks），密钥不入库}"
: "${KS_PASS:?请用 KS_PASS 提供密钥口令}"
java -jar "$TOOLS/signer.jar" -a out/unsigned.apk --ks "$KS" --ksAlias "${KS_ALIAS:-renwuzhi}" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS" -o out/signed
VER=$(grep versionName android/apktool.yml | sed "s/.*'\(.*\)'.*/\1/")
cp out/signed/*-aligned-signed.apk "out/xiaoshuo-fenxi-v$VER.apk"
echo "完成：out/xiaoshuo-fenxi-v$VER.apk"
