# Архитектурная документация RARS (форк rars-cu)

Документация предназначена для ИИ-агентов, работающих с кодовой базой.
Написана по результату анализа исходников; все упомянутые классы и пути проверены.

## Файлы

| Файл | Содержание |
|---|---|
| [01-overview.md](01-overview.md) | Обзор проекта, структура каталогов, сборка, запуск, тесты, глобальные сущности |
| [02-core-pipeline.md](02-core-pipeline.md) | Ассемблирование и симуляция: `rars.assembler`, `rars.riscv`, `rars.simulator`, `rars.api`, память/регистры, syscall'ы, исключения |
| [03-gui-venus.md](03-gui-venus.md) | GUI: `rars.venus` (+редактор, регистры, run-экшены, настройки), `rars.tools`, `rars.util` |
| [04-extension-points.md](04-extension-points.md) | Точки расширения под задачи форка (FORK-CHANGES.md) и общие сценарии расширения |

## Краткая сводка

- **Что это**: GUI/CLI-эмулятор ассемблера RISC-V на Java Swing (форк [thethirdone/rars](https://github.com/thethirdone/rars), ранее MARS). ~337 Java-файлов.
- **Точка входа**: `rars.Launch#main` (`src/rars/Launch.java`). Без аргументов — GUI (`rars.venus.VenusUI`), с аргументами — headless-режим через `rars.api.Program`.
- **Конвейер**: исходник → `Tokenizer` → двухпроходный `Assembler` → `ProgramStatement` (машинный код) → `Memory` → `Simulator` (поток `SimThread`, по инструкции на класс в `rars.riscv.instructions`).
- **Глобальное состояние**: `rars.Globals` (синглтоны `instructionSet`, `memory`, `symbolTable`, `settings`, `memoryAndRegistersLock`). Обязательная инициализация — `Globals.initialize()`.
- **Задачи форка**: все задачи `FORK-CHANGES.md` реализованы — функциональные изменения (разделы 1–3 в [04-extension-points.md](04-extension-points.md)) и улучшения UI (раздел 6 там же).
- **Сборка**: `./gradle-build.sh` → `build/libs/rars-cu-<дата>.jar` (требуется `git submodule update --init` для JSoftFloat). Тесты: `javac -cp <jar> test/Test.java && java -cp test:<jar> Test`.
