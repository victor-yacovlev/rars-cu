# RARS - эмулятор ассеблера RISC-V для учебных целей

Форк проекта RARS, который, в свою очередь является форком проекта MARS.

GUI-приложение, реализованное на Java.

Этот форк должен содержать изменения, которые описаны в файле [FORK-CHANGES.md](FORK-CHANGES.md).

## Сборка и валидация

- Сборка выполняется через gradle скриптом [gradle-build.sh](gradle-build.sh): он проверяет, что клонирован сабмодуль JSoftFloat (иначе — `git submodule update --init`), запускает `gradle build --no-daemon` и копирует готовый `build/libs/rars-cu-<ГГГГММДД>.jar` в корень проекта.
- **При валидации изменений используйте этот скрипт** (`sh gradle-build.sh`), а не чистый `javac`. Для быстрой проверки одной компиляции допустим `gradle compileJava --no-daemon`, для запуска тестов — `gradle runTests --no-daemon`.
