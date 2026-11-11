#!/bin/bash
# Выбор исполняемого, миграция старых ярлыков Тихо и совместимость с dash.
set -euo pipefail
SCR=$(cd "$(dirname "$0")/../app/src/main/assets/scripts" && pwd)
fail() { echo "FAIL: $*"; exit 1; }
pass() { echo "  PASS $*"; }

echo "== dash Bad substitution (как на телефоне Ивана) =="
# Старый pingvin-run: #!/bin/sh + ${PIPESTATUS[0]} → «Bad substitution» и код 2.
if dash -c 'rc=${PIPESTATUS[0]}' >/tmp/dash-ps.out 2>&1; then
  fail "dash unexpectedly accepts PIPESTATUS"
fi
grep -q 'Bad substitution' /tmp/dash-ps.out || fail "expected dash Bad substitution on PIPESTATUS"
rc=$(dash -c 'rc=${PIPESTATUS[0]}' >/dev/null 2>&1; echo $?)
[ "$rc" = 2 ] || fail "dash PIPESTATUS should exit 2, got $rc"
grep -E '^[^#]*\$\{PIPESTATUS' "$SCR/pingvin-run" && fail "pingvin-run must not use bash PIPESTATUS" || true
out=$(dash "$SCR/pingvin-run" /bin/true 2>&1 || true)
echo "$out" | grep -q 'Bad substitution' && fail "pingvin-run under dash still has Bad substitution: $out" || true
pass "dash no longer Bad-substitutes pingvin-run"

echo "== pickexe предпочитает firefox, а не crashreporter =="
td=$(mktemp -d)
mkdir -p "$td/firefox"
python3 -c '
import os, sys
d = sys.argv[1]
os.makedirs(d, exist_ok=True)
def elf(name, size):
    p = os.path.join(d, name)
    with open(p, "wb") as f:
        f.write(b"\x7fELF" + b"\0" * (size - 4))
    os.chmod(p, 0o755)
elf("firefox", 5520)
elf("firefox-bin", 642000)
elf("updater", 311528)
elf("pingsender", 686880)
elf("crashhelper", 3378600)
elf("crashreporter", 4425792)
open(os.path.join(d, "application.ini"), "w").write("[App]\nName=Firefox\n")
' "$td/firefox"
echo Тихо > "$td/README-TIHO.txt"
picked=$("$SCR/pingvin-pickexe" "$td" "tiho-linux-x86") || fail "pickexe failed"
case "$picked" in */firefox) pass "pickexe=$picked";; *) fail "pickexe chose '$picked', expected .../firefox";; esac
"$SCR/pingvin-tiho" --is-helper "$td/firefox/crashreporter" || fail "crashreporter should be helper"
"$SCR/pingvin-tiho" --is-helper "$td/firefox/updater" || fail "updater should be helper"
"$SCR/pingvin-tiho" --is-helper "$td/firefox/pingsender" || fail "pingsender should be helper"
"$SCR/pingvin-tiho" --is-helper "$td/firefox/crashhelper" || fail "crashhelper should be helper"
"$SCR/pingvin-tiho" --is-helper "$td/firefox/firefox" && fail "firefox must not be helper" || true
rm -rf "$td"
pass pickexe-and-helpers

echo "== миграция старых ярлыков tiho-linux-x86 и Windows =="
ROOT=$(mktemp -d)
export PINGVIN_ROOT="$ROOT" PINGVIN_MIGRATE_SKIP_BUILD=1
mkdir -p "$ROOT/opt/tiho-linux-x86/firefox" \
         "$ROOT/root/.pingvin/apps" \
         "$ROOT/usr/local/bin" \
         "$ROOT/usr/share/applications/pingvin" \
         "$ROOT/root/.local/share/applications" \
         "$ROOT/root/WinApps/tiho-windows-x64/firefox"
python3 -c '
import os, sys
d = sys.argv[1]
os.makedirs(d, exist_ok=True)
def elf(name, size):
    p = os.path.join(d, name)
    with open(p, "wb") as f:
        f.write(b"\x7fELF" + b"\0" * (size - 4))
    os.chmod(p, 0o755)
elf("firefox", 5520)
elf("crashreporter", 4425792)
open(os.path.join(d, "application.ini"), "w").write("[App]\nName=Firefox\n")
' "$ROOT/opt/tiho-linux-x86/firefox"
echo Тихо > "$ROOT/opt/tiho-linux-x86/README-TIHO.txt"
printf 'name=tiho-linux-x86\ncmd=%s\ngui=1\nlibs=\n' "$ROOT/opt/tiho-linux-x86/firefox/crashreporter" \
  > "$ROOT/root/.pingvin/apps/tiho-linux-x86.properties"
printf '#!/bin/sh\nexec pingvin-gui "%s" "$@"\n' "$ROOT/opt/tiho-linux-x86/firefox/crashreporter" \
  > "$ROOT/usr/local/bin/tiho-linux-x86"
chmod +x "$ROOT/usr/local/bin/tiho-linux-x86"
cat > "$ROOT/usr/share/applications/pingvin/tiho-linux-x86.desktop" <<D
[Desktop Entry]
Name=tiho-linux-x86
Exec=$ROOT/opt/tiho-linux-x86/firefox/crashreporter
D
printf 'MZ' > "$ROOT/root/WinApps/tiho-windows-x64/firefox/firefox.exe"
printf 'MZ' > "$ROOT/root/WinApps/tiho-windows-x64/firefox/crashreporter.exe"
printf '[App]\nName=Firefox\n' > "$ROOT/root/WinApps/tiho-windows-x64/firefox/application.ini"
printf '#!/bin/sh\nexec pingvin-wine "%s" "$@"\n' "$ROOT/root/WinApps/tiho-windows-x64/firefox/firefox.exe" \
  > "$ROOT/usr/local/bin/win-tiho-windows-x64"
chmod +x "$ROOT/usr/local/bin/win-tiho-windows-x64"
printf 'name=tiho-windows-x64\ncmd=%s\ngui=1\nlibs=\n' "$ROOT/usr/local/bin/win-tiho-windows-x64" \
  > "$ROOT/root/.pingvin/apps/tiho-windows-x64.properties"
cat > "$ROOT/root/.local/share/applications/pingvin-win-tiho-windows-x64.desktop" <<D
[Desktop Entry]
Name=Тихо
Exec=pingvin-wine "$ROOT/root/WinApps/tiho-windows-x64/firefox/firefox.exe"
D

"$SCR/pingvin-tiho" --migrate >/tmp/tiho-migrate.out
grep -q pingvin-tiho "$ROOT/usr/local/bin/tiho-linux-x86" || fail "linux wrapper not rewritten: $(cat "$ROOT/usr/local/bin/tiho-linux-x86")"
grep -q pingvin-tiho "$ROOT/usr/local/bin/win-tiho-windows-x64" || fail "windows wrapper not rewritten"
grep -q 'cmd=' "$ROOT/root/.pingvin/apps/tiho-linux-x86.properties" || fail "properties missing"
grep -q 'tiho-linux-x86' "$ROOT/root/.pingvin/apps/tiho-linux-x86.properties" || fail "properties not rewritten: $(cat "$ROOT/root/.pingvin/apps/tiho-linux-x86.properties")"
cmd=$(sed -n 's/^cmd=//p' "$ROOT/root/.pingvin/apps/tiho-linux-x86.properties")
grep -q pingvin-tiho "$cmd" || fail "properties cmd does not point at tiho wrapper: $cmd"
grep -q pingvin-tiho "$ROOT/usr/share/applications/pingvin/tiho-linux-x86.desktop" \
  || fail "desktop not rewritten"
if ! grep -q pingvin-tiho "$ROOT/root/.local/share/applications/pingvin-win-tiho-windows-x64.desktop" 2>/dev/null \
   && ! grep -q pingvin-tiho "$ROOT/usr/share/applications/pingvin/win-tiho-windows-x64.desktop" 2>/dev/null \
   && ! grep -q pingvin-tiho "$ROOT/usr/share/applications/pingvin/tiho-windows-x64.desktop" 2>/dev/null \
   && ! grep -q pingvin-tiho "$ROOT/usr/share/applications/pingvin/pingvin-win-tiho-windows-x64.desktop" 2>/dev/null; then
  # rewrite_tiho_launcher uses basename of desktop without pingvin-win- prefix → tiho-windows-x64
  # and writes to applications/pingvin/$key.desktop. key from pingvin-win-tiho-windows-x64.desktop
  # after stripping pingvin-win- is tiho-windows-x64.
  fail "windows desktop not rewritten; migrate out: $(cat /tmp/tiho-migrate.out); dests: $(ls "$ROOT/usr/share/applications/pingvin")"
fi
grep -q crashreporter "$ROOT/usr/local/bin/tiho-linux-x86" && fail "wrapper still mentions crashreporter" || true
pass migrate-wrappers

echo "== pingvin-run перенаправляет crashreporter Тихо (и под dash) =="
export PATH="$SCR:$PATH" PINGVIN_TEST_REDIRECT=1 PINGVIN_NO_MIGRATE=1
out=$(dash "$SCR/pingvin-run" "$ROOT/opt/tiho-linux-x86/firefox/crashreporter" 2>&1 || true)
echo "$out" | grep -q 'Bad substitution' && fail "redirect path Bad substitution: $out" || true
echo "$out" | grep -q 'PINGVIN_REDIRECT pingvin-tiho' || fail "expected redirect, got: $out"
printf 'name=tiho-linux-x86\ncmd=%s\ngui=1\nlibs=\n' "$ROOT/opt/tiho-linux-x86/firefox/crashreporter" \
  > "$ROOT/root/.pingvin/apps/tiho-linux-x86.properties"
out=$(dash "$SCR/pingvin-run" tiho-linux-x86 2>&1 || true)
echo "$out" | grep -q 'PINGVIN_REDIRECT pingvin-tiho' || fail "named shortcut should redirect, got: $out"
unset PINGVIN_TEST_REDIRECT PINGVIN_NO_MIGRATE PINGVIN_ROOT PINGVIN_MIGRATE_SKIP_BUILD
rm -rf "$ROOT"
pass run-redirect

echo "Все runtime-проверки прошли."
