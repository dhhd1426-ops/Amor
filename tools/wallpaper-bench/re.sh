#!/usr/bin/env bash
# 官方引擎 libscenejni.so 的逆向小工具（符号没去掉，C++ 名字都在）。用法见 逆向.md。
#   re.sh syms  [关键词]      列出函数：地址 大小 名字（已还原 C++ 名）
#   re.sh dis   函数名片段     反汇编一个函数（aarch64，带名字）
#   re.sh str   [正则]        引擎里的字符串（JSON 键名、着色器名、报错文本）
#   re.sh xref  字符串        哪些函数用到了这个字符串（先找地址再搜 adrp/add）
set -eu
W=${WE:-/home/claude/weapk}; S=$W/so/lib/arm64-v8a/libscenejni.so
[ -f "$S" ] || { mkdir -p "$W/so"; unzip -qo "$W/base.1.apk" 'lib/arm64-v8a/libscenejni.so' classes.dex classes2.dex -d "$W/so"; }
SYM=$W/so/syms.txt
[ -f "$SYM" ] || readelf -Ws --dyn-syms "$S" | awk '$4=="FUNC" && $2!~/^0+$/ {print $2, $3, $8}' | c++filt | sort -u > "$SYM"
case "${1:-}" in
  syms) grep -iE "${2:-.}" "$SYM" ;;
  dis)  grep -F -- "$2" "$SYM" | head -1 | { read a n name; echo "# $name ($n 字节)"; llvm-objdump-18 -d --demangle --no-show-raw-insn --start-address=0x$a --stop-address=$(printf "0x%x" $((0x$a+n))) "$S" | tail -n +7; } ;;
  str)  strings -n 4 -t x "$S" | grep -E -- "${2:-.}" ;;
  xref) off=$(strings -n 4 -t x "$S" | grep -m1 -F -- "$2" | awk '{print $1}'); echo "# 字符串在 0x$off"; page=$(printf "0x%x" $((0x$off & ~0xfff))); lo=$(printf "#0x%x" $((0x$off & 0xfff)))
        llvm-objdump-18 -d --demangle --no-show-raw-insn "$S" | awk -v p="$page" -v lo="$lo" '/^[0-9a-f]+ </{f=$0} /adrp/ && index($0,p){a=1;next} a && /add/ && index($0,lo){print f; a=0} {a=0}' | sort -u | head -20 ;;
  *) sed -n 2,6p "$0" ;;
esac
