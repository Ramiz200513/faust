#!/usr/bin/env sh
# Резервная копия базы «Фауста» на сервере: устройства, привязки, журнал,
# уроки своей модели. Запуск из папки deploy:
#   sh scripts/backup.sh
# Раз в сутки по cron (crontab -e):
#   0 4 * * * cd /opt/faust/deploy && sh scripts/backup.sh >> backups/backup.log 2>&1
#
# Хранятся 14 последних копий. Копия лежит на том же сервере — от поломки
# базы спасает, от потери сервера нет: важные копии иногда скачивайте себе
# (scp сервер:/opt/faust/deploy/backups/*.sql.gz .).
set -eu
cd "$(dirname "$0")/.."
mkdir -p backups
stamp=$(date +%Y-%m-%d_%H%M)
docker compose -f docker-compose.prod.yml exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d jarvis' | gzip > "backups/jarvis_$stamp.sql.gz"
# Старые копии — прочь, кроме 14 свежих.
ls -1t backups/jarvis_*.sql.gz | tail -n +15 | xargs -r rm --
echo "$(date '+%F %T') копия backups/jarvis_$stamp.sql.gz готова"
