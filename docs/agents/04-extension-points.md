# Точки расширения (для задач форка)

Все три задачи из `FORK-CHANGES.md` **реализованы** (разделы 1–3 описывают фактическое устройство). Раздел 4 — общие сценарии расширения. Пути — относительно `src/`.

## 1. Комментарий `//` (как в gcc) в дополнение к `#` — реализовано

Распознают оба лексера:

1. **Ассемблер**: `rars/assembler/Tokenizer.java`, `tokenizeLine(...)`, общий кейс `case '#': case '/':` — при `/` проверяется следующий символ: `//` захватывает остаток строки как один токен, одиночный `/` — обычный символ. Классификация — `rars/assembler/TokenTypes.matchTokenType()`: строка, начинающаяся с `#` или `//`, получает тип `TokenTypes.COMMENT`.
2. **GUI-подсветка**: `rars/venus/editors/jeditsyntax/tokenmarker/RISCVTokenMarker.java`, `markTokensImpl()` — кейс `case '/'` с заглядыванием вперёд окрашивает `//`-комментарий как `Token.COMMENT1` (аналогично `#`).

`Tokenizer.processEqv` и `Assembler.stripComment()` правок не требовали — оперируют уже типизированным токеном. Комментарий `//` учитывается и в поиске Си-сигнатуры функции (`FunctionRunSupport.findSignature`, см. раздел 3).

## 2. Отображение регистров в signed/unsigned/hex — реализовано

Таблицы «Registers» и «Control and Status» показывают три value-колонки: **Value (Signed)**, **Value (Unsigned)**, **Value (HEX)**. Окно «Floating Point» не затронуто — у него одна колонка `Value`.

Ключевое в `rars/venus/registers/RegisterBlockWindow.java`:

- Флаг `dualValueColumns` (4-аргументный конструктор; `true` в `RegistersWindow` и `ControlAndStatusWindow`, 3-аргументный делегирует с `false` для FP-окна).
- `formatUnsignedRegister()` — беззнаковое десятичное: `Long.toUnsignedString` (RV64) или `NumberDisplayBaseChooser.formatUnsignedInteger` (RV32).
- `RegTableModel.setValueAt()` — любая правка value-колонки пишет значение в регистр (под `Globals.memoryAndRegistersLock`) и **перечитывает из регистра все три представления** (колонки всегда синхронны). В беззнаковой колонке дополнительно принимаются значения за пределами знакового диапазона (`parseUserValue` → `Long.parseUnsignedLong`). Невалидный ввод помечается `INVALID`.
- `updateRegisters()` (обновления от эмулятора) обновляет все три колонки.
- Публичный `getTableModel()` — для тестов.

Пункт меню «Settings → Values displayed in hexadecimal» **удалён** из `VenusUI` (поле/action/геттер `getValueDisplayBaseMenuItem`). Сама настройка `Settings.Bool.DISPLAY_VALUES_IN_HEX` оставлена — её использует чекбокс «Hexadecimal Values» на `ExecutePane` (теперь влияет только на Data Segment) и пост-мортем в CLI.

## 3. Запуск первой функции с аргументами, если нет `main`/`_start` — реализовано

Новые классы:

- **`rars/simulator/FunctionRun.java`** — статическое состояние режима: `active`, `functionName`, `arguments`, `hasReturnValue`, `returnAddress` (sentinel = `Memory.textBaseAddress - 4`). Сбрасывается `clear()` из `RunAssembleAction` и `RunResetAction`.
- **`rars/venus/run/FunctionRunSupport.java`** — вся логика GUI:
  - `prepareFunctionRunIfNeeded(VenusUI)`: если в `Globals.symbolTable` нет текстовых символов `main` и `_start`, точка входа — первый текстовый символ глобальной таблицы (порядок возникновения в коде). Показывает модальный диалог с аргументами; отмена возвращает `false` и запуск прекращается. Вызывается из `RunGoAction`/`RunStepAction` при `!mainUI.getStarted()`.
  - Диалог в двух видах: при найденной Си-сигнатуре — фиксированный набор именованных параметров (все обязательны); иначе — произвольный список целых чисел через пробел (может быть пустым). Валидация — `Long.decode`, при ошибке повторный ввод.
  - `findSignature(label)` читает исходники из `RunAssembleAction.getProgramsToAssemble()` и ищет комментарий (`#` или `//`) с сигнатурой `ret_type name(params)` на ближайшей непустой строке перед меткой; имя в сигнатуре должно совпадать с меткой.
  - `applyFunctionCall`: PC → адрес функции, `ra` → sentinel, аргументы → `a0`–`a7` (по соглашению о вызовах RISC-V), сверх 8 — на стек через `sp` (`Memory.setDoubleWord`).
  - `reportFunctionResult(VenusUI)`: после `NORMAL_TERMINATION` пишет в **Run I/O** (`postRunMessage`) строку `Execution Done: name(args) = RET` (для `void` — без `= RET`); вызывается из `RunGoAction.stopped()` и `RunStepAction.stepped()`.

**Механизм корректного `ret`**: в `Simulator.SimThread.run()` перед выборкой инструкции проверяется `FunctionRun.active && pc == FunctionRun.returnAddress` → `stopExecution(true, Reason.NORMAL_TERMINATION)` вместо ошибки `Instruction load access error`.

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
