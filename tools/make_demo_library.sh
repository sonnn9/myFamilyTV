#!/usr/bin/env bash
# Creates a small DEMO library (test pattern videos) for trying Family TV on an emulator or TV.
# It is only test data: copy the result to a USB stick (or the emulator) yourself.
# Requires ffmpeg. Usage: tools/make_demo_library.sh <output-dir>
set -euo pipefail
OUT="${1:?usage: make_demo_library.sh <output-dir>}"
ROOT="$OUT/FamilyTV"
mkdir -p "$ROOT/videos/kids/songs" "$ROOT/videos/kids/english" "$ROOT/videos/adults" "$ROOT/videos/shared" "$ROOT/private"

clip() { # <file> <seconds> <pattern> <hz>
  ffmpeg -loglevel error -y -f lavfi -i "$3=size=640x360:rate=25:duration=$2" -f lavfi -i "sine=frequency=$4:duration=$2" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 64k -shortest "$1"
}

clip "$ROOT/videos/kids/songs/Bài hát ABC.mp4" 40 testsrc2 440
clip "$ROOT/videos/kids/songs/Con cò bé bé.mp4" 25 smptebars 523
clip "$ROOT/videos/kids/english/Learn colors 1.mp4" 20 rgbtestsrc 330
clip "$ROOT/videos/kids/english/Learn colors 2.mp4" 20 testsrc 349
clip "$ROOT/videos/kids/english/Learn colors 10.mp4" 20 smptehdbars 392
clip "$ROOT/videos/adults/Phim tài liệu người lớn.mp4" 30 testsrc 262
clip "$ROOT/videos/shared/Du lịch Đà Lạt.mp4" 30 smptehdbars 294
clip "$ROOT/private/Không được hiện.mp4" 10 testsrc2 600

# Sidecar thumbnail next to a video (same name, .jpg).
ffmpeg -loglevel error -y -f lavfi -i "color=c=0xFF7A59:size=640x360:duration=1" -frames:v 1 "$ROOT/videos/kids/songs/Bài hát ABC.jpg"
# A broken file and a macOS AppleDouble file (must be ignored / reported as damaged).
head -c 200000 /dev/urandom > "$ROOT/videos/shared/Video hỏng.mp4"
head -c 4096 /dev/urandom > "$ROOT/videos/kids/songs/._Bài hát ABC.mp4"

cp "$(dirname "$0")/../sample/FamilyTV/config.json" "$ROOT/config.json"
echo "Demo library created in $ROOT"
