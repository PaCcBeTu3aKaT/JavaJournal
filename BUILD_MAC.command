#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
echo "JavaJournal: проверка и создание macOS-приложения..."
./gradlew :core:test :desktop:packageMac
echo
echo "Готовый архив находится в desktop/build/distributions/"
ls -lh desktop/build/distributions/*macos*.zip
