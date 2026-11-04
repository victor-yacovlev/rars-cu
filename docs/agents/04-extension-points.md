# Точки расширения (для задач форка)

Задачи из `FORK-CHANGES.md` (на момент написания не реализованы) и общие сценарии расширения. Пути — относительно `src/`.

## 1. Комментарий `//` (как в gcc) в дополнение к `#`

Изменить нужно **два независимых лексера**, иначе подсветка и ассемблер разойдутся:

1. **Ассемблер**: `rars/assembler/Tokenizer.java`, метод `tokenizeLine(...)`, `switch (c) { case '#': ... }` (~L300). Добавить распознавание `/` + следующий `/` до конца строки как `TokenTypes.COMMENT` (кейс `case '/'` сейчас не обрабатывается — смотреть `line[linePos+1]`).
2. **GUI-подсветка**: `rars/venus/editors/jeditsyntax/tokenmarker/RISCVTokenMarker.java`, `markTokensImpl()` (сейчас COMMENT1 — только `#`) + при необходимости примеры в `getRISCVTokenExamples()`.

Сопутствующий код правки **не требует**: `Tokenizer.processEqv` и `Assembler.stripComment()` (L517) оперируют уже типизированным токеном `TokenTypes.COMMENT`.

## 2. Отображение регистров одновременно в hex и dec

Основные точки (все в `rars/venus/registers/`):

- `RegisterBlockWindow.java`: `RegTableModel.columnNames` (сейчас 3 колонки: Name/Number/Value), `isCellEditable`, `setValueAt`, `setupWindow` (L117), `updateRegisters` (L152–157), ширины колонок в конструкторе. **Добавление второй value-колонки** — вариант с наибольшим объёмом правок, но чистый.
- `formatRegister()` в наследниках: `RegistersWindow` (L65–71), `FloatingPointWindow`, `ControlAndStatusWindow`.
- Альтернатива без новой колонки — менять форматтер `NumberDisplayBaseChooser.formatNumber` (вернуть «0x… / 123»), но он переиспользуется `TextSegmentWindow` и `DataSegmentWindow`, и сломается редактирование значений (`setValueAt` парсит через `Binary.stringToInt`).
- Переключатель режима: `Settings.Bool.DISPLAY_VALUES_IN_HEX`, чекбокс `VenusUI.settingsValueDisplayBase`, `ExecutePane.valueDisplayBase`.
- CLI-вывод (пост-мортем) — отдельный код: `rars/Launch.java`, `displayRegistersPostMortem()` / `formatIntForDisplay()`.

## 3. Запрос аргументов первой функции, если нет `main`/`start`

Связанные места (проверены):

- Метка старта: `rars/assembler/SymbolTable.java` — `private static String startLabel = "main"`, `getStartLabel()` (L274).
- Настройка: `rars/Settings.java` — `enum Bool.START_AT_MAIN` (L127).
- Установка PC: `rars/riscv/hardware/RegisterFile.java` — `initializeProgramCounter(boolean startAtMain)` (L180): если метка найдена (`Globals.symbolTable.getAddress(getStartLabel()) != SymbolTable.NOT_FOUND`) и адрес в тексте — PC = адрес метки, иначе `textBaseAddress`; `resetRegisters()` (L244) — та же логика.
- Подача аргументов: `rars/simulator/ProgramArgumentList.java` — `storeProgramArguments()` (L128): argc→`a0`, argv→`a1` (строки и указатели кладутся на стек). Вызывается из `rars/api/Program.setup()` (L140) и из GUI: `rars/venus/run/RunGoAction.processProgramArgumentsIfAny()` / `RunStepAction` (гейт `Settings.Bool.PROGRAM_ARGUMENTS`); CLI — опция `pa` в `Launch.parseCommandArgs()`.

Сценарий реализации: после сборки проверить `Globals.symbolTable.getAddress("main")` (и `start`), при отсутствии — интерактивно запросить аргументы (GUI — диалог из `venus`; CLI — `Launch`; программно — `api.Program`), затем передать их в `ProgramArgumentList.storeProgramArguments()`.

## 4. Общие сценарии расширения

### Новая инструкция

Класс в `rars.riscv.instructions`, наследующий подходящий базовый класс (например `Arithmetic`) и `BasicInstruction`: конструктор задаёт пример, `BasicInstructionFormat` и 32-символьную маску (`f/s/t` — позиции операндов), реализуется `simulate(ProgramStatement)`. Автоматически подхватывается `InstructionSet.addBasicInstructions()` (рефлексивное сканирование каталога).

### Новая псевдоинструкция

Правка ресурсов `src/PseudoOps.txt` (RV32) и/или `src/PseudoOps-64.txt` (RV64). Формат: `мнемоника шаблон ;перевод1 ;перевод2 ;#описание`; в переводах маркеры `RGn` (регистр-операнд), `LLn/LHn` (imm), `PCLn` (PC-relative) и т.п. Загружается в `InstructionSet.populate()`.

### Новый syscall

Класс `rars.riscv.syscalls.SyscallFoo extends AbstractSyscall` (номер в конструкторе суперкласса **обязательно `-1`**, логика в `simulate(ProgramStatement)`, номер сервиса приходит в `a7`) + строка `Foo = <номер>` в `src/Syscall.properties`. Загрузка — `SyscallLoader` (static-блок, рефлексия + overrides из properties).

### Новый инструмент (меню Tools)

Класс в `rars.tools`, реализующий `Tool` (или наследующий `AbstractToolAndApplication` для режима «tool + standalone»). Меню строится автоматически `ToolLoader.buildToolsMenu()`.

### Новая настройка

Элемент `Settings.Bool` (или строковая константа) в `rars/Settings.java` + при необходимости дефолт в `src/Settings.properties` + чекбокс через `SettingsAction` в `VenusUI.createActionObjects()`/`setUpMenuBar()`. Изменения рассылаются GUI через `Observable` (`setChanged(); notifyObservers()`).

### Конфигурационные константы

`src/Config.properties`: `MessageLimit`, `ErrorLimit`, `BackstepLimit`, расширения файлов ассемблера (`Extensions`), ASCII-таблица. Читается в `Globals.initialize()`.

## 5. Инварианты, которые нельзя нарушать

- `Globals.initialize()` обязателен до любого использования ядра.
- Доступ к памяти/регистрам вне потока симуляции — только под `Globals.memoryAndRegistersLock`.
- Номера syscall назначаются только через `Syscall.properties` (не хардкодить в классах — загрузчик завершит JVM при рассинхроне).
- `NumberDisplayBaseChooser.formatNumber*` — общее форматирование для регистров **и** сегментных окон; менять семантику — только с проверкой всех мест использования.
- Симуляция всегда в потоке `SimThread` (не SwingWorker, вопреки старым комментариям); обновление Swing — через `invokeLater`.
- Ресурсы (`*.properties`, `PseudoOps*.txt`, `help/`, `images/`) должны попадать в classpath/JAR рядом с классами — `build-jar.sh` копирует все не-`.java` файлы из `src/`.
