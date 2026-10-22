# ============================================================================
#  Dockerfile релея. Два этапа (multi-stage build):
#   1) "build" — собирает исполняемый jar. Тут нужны JDK и Gradle, но эта
#      стадия в конечный образ не попадает — она только помогает получить jar.
#   2) "run"   — конечный образ: только JRE (без компилятора и Gradle) и
#      готовый jar. Меньше размер образа и меньше что может быть уязвимо.
# ============================================================================

# ---------- Стадия 1: сборка ----------
# eclipse-temurin:21-jdk — открытый образ с JDK 21 (та же сборка OpenJDK,
# что мы используем локально). Alias "AS build" — чтобы сослаться на эту
# стадию во второй (COPY --from=build).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Копируем сначала только то, что нужно Gradle для загрузки зависимостей:
# сам wrapper и файлы описания сборки, БЕЗ исходников (src/ появится позже
# отдельным слоем). Так Docker кэширует слой с зависимостями: пока
# build.gradle.kts не меняется, повторная сборка не будет заново качать
# интернет — изменится только следующий слой (src).
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --version

# Теперь копируем исходный код и собираем исполняемый ("fat") jar.
# --no-daemon: в одноразовом контейнере демон Gradle не нужен, он бы просто
# висел в памяти после завершения сборки.
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# ---------- Стадия 2: запуск ----------
# JRE вместо JDK: компилятор и инструменты сборки рантайму не нужны.
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

# build/libs содержит один jar (bootJar кладёт туда jarvis-relay-<version>.jar) —
# берём его под фиксированным именем, чтобы ENTRYPOINT ниже не зависел от версии.
COPY --from=build /workspace/build/libs/*.jar app.jar

# Не запускаем процесс от root — обычная гигиена контейнеров.
RUN useradd --create-home --shell /usr/sbin/nologin relay
USER relay

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
