#!/usr/bin/env sh
# Обновить релей на сервере до свежего образа. Его вызывает пайплайн
# (шаг deploy), можно и руками:  sh /opt/faust/deploy/scripts/update.sh
#
# Перед обновлением — резервная копия базы: если новая версия что-то испортит
# в данных, есть куда откатиться.
set -eu
cd "$(dirname "$0")/.."
compose="docker compose -f docker-compose.prod.yml"

sh scripts/backup.sh
$compose pull relay
$compose up -d relay

# Ждём, пока релей станет healthy (до 2 минут); не дождались — пайплайн
# покажет красный шаг, а в выводе будут последние строки журнала релея.
i=0
while [ $i -lt 24 ]; do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$($compose ps -q relay)" 2>/dev/null || echo unknown)
  if [ "$status" = "healthy" ]; then
    echo "релей обновлён и работает"
    exit 0
  fi
  i=$((i + 1))
  sleep 5
done
echo "релей не поднялся за 2 минуты:"
$compose logs --tail 50 relay
exit 1
