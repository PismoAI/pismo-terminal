#!/bin/sh
set -e

echo "=== Setting up Pismo Terminal ==="

echo "[1/4] Updating package index..."
apk update

echo "[2/4] Installing essential packages..."
apk add --no-cache bash coreutils curl wget git openssh-client ca-certificates nodejs npm python3

echo "[3/4] Configuring shell..."
cat > /root/.bashrc << 'BASHRC'
export PS1='\[\033[01;32m\]pismo\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export HOME=/root
export TERM=xterm-256color

echo ""
echo "  Pismo Terminal Ready!"
echo "  Type 'apk add <pkg>' to install packages"
echo ""
BASHRC

echo "[4/4] Done!"
echo ""
echo "=== Setup complete! ==="
