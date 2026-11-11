#!/bin/bash
# Локальные проверки установки Тихо (без proot и без телефона).
set -euo pipefail
SCR=$(cd "$(dirname "$0")/../app/src/main/assets/scripts" && pwd)
echo "== bash -n =="
for f in "$SCR"/pingvin-*; do
  case "$(basename "$f")" in pingvin-xz) echo "  skip $(basename "$f") (binary)"; continue;; esac
  head1=$(head -n1 "$f" 2>/dev/null || true)
  case "$head1" in
    *bash*|*"/bin/sh"|*'env sh'*) bash -n "$f"; echo "  OK $(basename "$f")";;
    *) echo "  skip $(basename "$f")";;
  esac
done

# Подгрузить функции определения, не выполняя основной код
eval "$(sed -n '/^isff()/,/^tiho_compat_install()/p' "$SCR/pingvin-install" | sed '$d')"

fail() { echo "FAIL: $*"; exit 1; }
pass() { echo "  PASS $*"; }

echo "== istiho_name =="
istiho_name "Tiho-Setup-1.0.0-win-x64.exe" || fail "Tiho-Setup should match"
istiho_name "Tiho-1.0.0-linux-amd64.deb" || fail "Tiho deb should match"
istiho_name "тихо.zip" || fail "тихо should match"
istiho_name "firefox-esr.deb" && fail "firefox-esr should not match" || true
pass istiho_name

echo "== isff (Firefox-сборка Тихо) =="
td=$(mktemp -d)
mkdir -p "$td/firefox/distribution"
printf '[App]\nName=Firefox\n' > "$td/firefox/application.ini"
touch "$td/firefox/firefox"
echo hi > "$td/README-TIHO.txt"
isff "$td" || fail "isff linux tree"
touch "$td/firefox/firefox.exe"
isff "$td" || fail "isff with firefox.exe"
rm -f "$td/firefox/firefox" "$td/firefox/firefox.exe"
isff "$td" && fail "isff without binary" || true
rm -rf "$td"
pass isff

echo "== is_tiho_dir =="
td=$(mktemp -d)
echo '{}' > "$td/desktop-update-key.json"
is_tiho_dir "$td" || fail "desktop-update-key.json marks tiho"
rm -rf "$td"
td=$(mktemp -d)
mkdir -p "$td/resources"
echo asar > "$td/resources/app.asar"
is_tiho_dir "$td" && fail "random electron dir should not be tiho" || true
echo README > "$td/README-TIHO.txt"
is_tiho_dir "$td" || fail "README-TIHO marks tiho"
rm -rf "$td"
pass is_tiho_dir

echo "== elf_machine / pe_machine =="
appimage=/workspace/tiho-desktop/dist/Tiho-1.0.0-linux-x86_64.AppImage
if [ -f "$appimage" ]; then
  m=$(elf_machine "$appimage" || true)
  [ "$m" = x86_64 ] || fail "AppImage elf_machine got '$m'"
  pass "elf_machine AppImage=$m"
fi
wexe=/workspace/tiho-desktop/dist/Tiho-Setup-1.0.0-win-x64.exe
if [ -f "$wexe" ]; then
  m=$(pe_machine "$wexe" || true)
  # NSIS-установщик x64 часто сам 32-битный (i386), полезная программа внутри — x64.
  case "$m" in amd64|i386) pass "pe_machine Setup=$m (stub)";; *) fail "Setup exe pe_machine got '$m'";; esac
  [ "$m" = arm64 ] && fail "x64 Setup must not be classified as arm64"
  is_firefox_windows_exe "$wexe" && fail "Tiho-Setup is not firefox.exe" || true
  istiho_name "$(basename "$wexe")" || fail "Tiho-Setup name"
fi
wexe2=/tmp/firefox.exe
printf 'MZ' > "$wexe2"
is_firefox_windows_exe "$wexe2" || fail "firefox.exe name"
pass "is_firefox_windows_exe"

echo "== pingvin-tiho policies fallback =="
grep -q 'tiho-policies.json' "$SCR/pingvin-tiho" || fail "pingvin-tiho should fallback to bundled policies"
grep -q 'logged_run' "$SCR/pingvin-run" || fail "pingvin-run should log crashes"
grep -q 'tiho_compat_install' "$SCR/pingvin-install" || fail "compat path missing"
grep -q 'exec pingvin-tiho --run tiho' "$SCR/pingvin-wine" || fail "wine should remap Tiho x64"
pass script-markers

echo "Все проверки прошли."
