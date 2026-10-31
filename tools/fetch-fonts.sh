#!/bin/bash
# 下载行程导出 PDF 用的中文字体（思源黑体 Noto Sans SC，SIL OFL 开源授权）的常规和粗体 TTF。
# 用法: tools/fetch-fonts.sh [目录]，默认 data/fonts
set -euo pipefail
DIR="${1:-data/fonts}"
mkdir -p "$DIR"
# 用老的 User-Agent 请求 Google Fonts，返回的是完整 TTF 而不是分片的 woff2
CSS=$(curl -fsS -A "Wget/1.21" "https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;700")
REGULAR=$(echo "$CSS" | awk '/font-weight: 400/{f=1} f && /src:/{match($0,/https:[^)]*/); print substr($0,RSTART,RLENGTH); exit}')
BOLD=$(echo "$CSS" | awk '/font-weight: 700/{f=1} f && /src:/{match($0,/https:[^)]*/); print substr($0,RSTART,RLENGTH); exit}')
for pair in "NotoSansSC-Regular.ttf $REGULAR" "NotoSansSC-Bold.ttf $BOLD"; do
  set -- $pair
  if [ -s "$DIR/$1" ]; then echo "已有 $DIR/$1"; continue; fi
  echo "下载 $1 …"
  curl -fsS --retry 3 -o "$DIR/$1.part" "$2" && mv "$DIR/$1.part" "$DIR/$1"
done
ls -lh "$DIR"
