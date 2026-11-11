# Пингвин: DISPLAY для GUI-программ. X-сервер запускается автоматически обёртками GUI-программ.
export DISPLAY=:${PINGVIN_DISPLAY_NUM:-1}
alias gui=pingvin-gui
[ -x /usr/local/bin/pingvin-wrap ] && [ ! -f /root/.pingvin/wrapped-v1 ] && pingvin-wrap --all >/dev/null 2>&1 && touch /root/.pingvin/wrapped-v1

export NO_AT_BRIDGE=${NO_AT_BRIDGE:-1} GTK_A11Y=${GTK_A11Y:-none}
_mid=$(tr -d ' \n\r' < /etc/machine-id 2>/dev/null)
if ! printf '%s' "$_mid" | grep -qxE '[0-9a-f]{32}'; then
  _mid=$(head -c16 /dev/urandom | od -An -tx1 | tr -d " \n")
  printf '%s\n' "$_mid" > /etc/machine-id 2>/dev/null || true
fi
mkdir -p /var/lib/dbus 2>/dev/null || true
[ -n "$_mid" ] && printf '%s\n' "$_mid" > /var/lib/dbus/machine-id 2>/dev/null || true
command -v pingvin-tiho >/dev/null 2>&1 && pingvin-tiho --migrate >/dev/null 2>&1 || true
