# Пингвин: DISPLAY для GUI-программ. X-сервер запускается автоматически обёртками GUI-программ.
export DISPLAY=:${PINGVIN_DISPLAY_NUM:-1}
alias gui=pingvin-gui
[ -x /usr/local/bin/pingvin-wrap ] && [ ! -f /root/.pingvin/wrapped-v1 ] && pingvin-wrap --all >/dev/null 2>&1 && touch /root/.pingvin/wrapped-v1
