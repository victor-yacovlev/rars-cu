# GUI: venus, инструменты, утилиты

Пакеты: `rars.venus` (+`editors`, `registers`, `run`, `settings`, `util`), `rars.tools`, `rars.extras`, `rars.util`. Все пути — относительно `src/`.

## 1. Иерархия GUI

```
VenusUI (JFrame)
 ├─ JMenuBar (setUpMenuBar) + JToolBar (setUpToolBar)
 ├─ MainPane (JTabbedPane: "Edit" | "Execute")
 │   ├─ EditTabbedPane → EditPane (по вкладке файла) → Editor (менеджер файлов)
 │   └─ ExecutePane (JDesktopPane)
 │       ├─ TextSegmentWindow (внутр. фрейм)
 │       ├─ DataSegmentWindow (внутр. фрейм)
 │       └─ LabelsWindow (внутр. фрейм)
 ├─ RegistersPane (JTabbedPane)
 │   ├─ RegistersWindow (int + pc)
 │   ├─ FloatingPointWindow
 │   └─ ControlAndStatusWindow
 ├─ MessagesPane (горизонтальный JSplitPane: слева "Messages", справа "Run I/O")
 └─ JSplitPane (splitter / horizonSplitter)
```

| Класс | Назначение |
|---|---|
| `rars/venus/VenusUI.java` | Главное окно: владелец всех экшенов (`createActionObjects()`, L257–505), конечный автомат состояний меню: `setMenuStateInitial/NotEdited/Editing/EditingNew/Runnable/Running/Terminated` (синхронизированы с `FileStatus`) |
| `MainPane.java` | JTabbedPane «Edit»/«Execute» |
| `EditPane.java` | Один открытый файл: обёртка над `TextEditingArea`, номера строк, undo/compound-edit |
| `EditTabbedPane.java` | Вкладки открытых файлов (создаёт `EditPane`) |
| `Editor.java` | Менеджер файлов: Open/Save-каталоги (персистятся через `FileDirectoryPersistence`), делегирует `EditTabbedPane` |
| `FileDirectoryPersistence.java` | Хранение каталогов диалогов Open/Save в `java.util.prefs` (записываются при выборе файла, восстанавливаются при создании `Editor`) |
| `ExecutePane.java` | Окно выполнения: `TextSegmentWindow`, `DataSegmentWindow`, `LabelsWindow` + два `NumberDisplayBaseChooser` (hex/dec для адресов и значений) |
| `TextSegmentWindow.java` | Таблица сегмента `.text`: дизассемблер, брейкпоинты (чекбоксы), подсветка текущей инструкции; также хранит аргументы программы (`getProgramArguments()`) |
| `DataSegmentWindow.java` | Таблица памяти сегмента данных, редактирование значений |
| `LabelsWindow.java` | Таблица символов (метки) |
| `MessagesPane.java` | «Messages» (вывод ассемблера) и «Run I/O» (syscall-ввод/вывод) рядом слева направо (горизонтальный `JSplitPane`); кнопка «Clear» вынесена в контекстное меню каждой области |
| `FileStatus.java` | Статусная машина файла: `EDITING/EDITED/RUNNABLE/RUNNING/TERMINATED/NOT_OPEN` |
| `GuiAction.java` | Базовый `AbstractAction` для пунктов меню |
| `NumberDisplayBaseChooser.java` | JCheckBox hex/dec + статические форматтеры `formatNumber`/`formatUnsignedInteger` (переиспользуются сегментными окнами) |

Доступ из любой точки: `Globals.getGui()` → `getMainPane().getExecutePane()`, `getMessagesPane()`, `getRegistersPane()` и т.д. Регистровые окна создаются в `VenusUI` и передаются **и** в `ExecutePane` (обновление после паузы/останова), **и** в `RegistersPane` (отображение).

## 2. Редактор кода (`rars/venus/editors/`)

- Интерфейс `TextEditingArea.java`; реализации выбираются в конструкторе `EditPane` (L88–92) по `Settings.Bool.GENERIC_TEXT_EDITOR`:
  - `generic/GenericTextArea.java` — `JTextArea` без подсветки;
  - `jeditsyntax/JEditBasedTextArea.java` — на базе `JEditTextArea`: `setTokenMarker(new RISCVTokenMarker())`, шрифт из `Settings.getEditorFont()`, undo через `UndoManager`.
- **Подсветка строки выполнения** — `TextEditingArea.setHighlightedLine(int line)` (1-based; `<=0` снимает) рисует полноширинную фоновую заливку **под** текстом (отдельная UI-операция, не выделение текста). `GenericTextArea` — через `Highlighter` (кастомный `FullLineHighlightPainter`); `JEditBasedTextArea` — через `TextAreaPainter.setExecutionLine(int)` (0-based). `EditPane.setHighlightedLine(int)` делегирует в активный редактор.
- Порт jEdit Syntax: `jeditsyntax/` — `JEditTextArea`, `TextAreaPainter`, `SyntaxDocument`, `TokenMarker`, `SyntaxStyle`, `SyntaxUtilities`, `KeywordMap`, `InputHandler`/`DefaultInputHandler`, `TextAreaDefaults`, `TextUtilities`.
- **Токенайзер подсветки**: `jeditsyntax/tokenmarker/RISCVTokenMarker.java`, метод `markTokensImpl()` (посимвольный разбор). Типы токенов в `Token.java`: `COMMENT1, LITERAL1/2, LABEL, KEYWORD1` (инструкции), `KEYWORD2` (директивы), `KEYWORD3` (регистры), `INVALID, MACRO_ARG`. Комментарий — `#` и `//` (кейс `case '/'` с заглядыванием вперёд).
- Ключевые слова — из `rars.assembler.Directives`, `RegisterFile`, `FloatingPointRegisterFile`, `InstructionSet` (лениво через `getKeywords()`).
- Стили подсветки: `Settings.setEditorSyntaxStyleByPosition()` → `JEditBasedTextArea.updateSyntaxStyles()` → `SyntaxUtilities.getCurrentSyntaxStyles()`; диалог — `venus/settings/SettingsHighlightingAction.java`.

## 3. Панель регистров (`rars/venus/registers/`)

Иерархия: `RegisterBlockWindow` (абстрактный) → `RegistersWindow` / `FloatingPointWindow` / `ControlAndStatusWindow`; контейнер — `RegistersPane`.

Ключевое в `RegisterBlockWindow.java`:

- JTable с моделью `RegTableModel`. Режим задётся флагом `dualValueColumns` (4-аргументный конструктор):
  - `true` (`RegistersWindow`, `ControlAndStatusWindow`) — **5 колонок**: `Name`, `Number`, `Value (Signed)`, `Value (Unsigned)`, `Value (HEX)`;
  - `false` (`FloatingPointWindow`, 3-аргументный конструктор) — 3 колонки `Name/Number/Value`, база — `ExecutePane.getValueDisplayBase()`.
- Форматирование: `formatRegister(reg, base)` (знаковое dec / hex, маскирование до `int` при RV32), `formatUnsignedRegister(reg)` (беззнаковое dec: `Long.toUnsignedString` / `formatUnsignedInteger`).
- Редактирование value-колонок: `RegTableModel.setValueAt()` — парсинг (`Binary.stringToInt`; для беззнаковой колонки дополнительная ветка `Long.parseUnsignedLong` для значений за пределами знакового диапазона), запись в регистр под `Globals.memoryAndRegistersLock`, затем **перечитка всех представлений из регистра** (колонки всегда синхронны); невалидный ввод → `INVALID`.
- `updateRegisters()` — обновление от эмулятора, пишет во все value-колонки. Публичный `getTableModel()` — для тестов.
- Подсветка изменённых: `update(Observable, Object)` — Observer за `Simulator` (start/stop включает/выключает наблюдение за регистрами), за `Settings`, за каждым `Register` (`RegisterAccessNotice.WRITE` → `highlightCellForRegister`).
- Шрифты/цвета строк — `Settings` (`EVEN_ROW_*`, `ODD_ROW_*`, `REGISTER_HIGHLIGHT_*`).

Пункт меню «Values displayed in hexadecimal» удалён из `VenusUI`; настройка `Settings.Bool.DISPLAY_VALUES_IN_HEX` осталась (чекбокс «Hexadecimal Values» на `ExecutePane` теперь управляет только Data Segment).

## 4. Экшены запуска (`rars/venus/run/`)

- `RunAssembleAction.java` — сборка: `Globals.program = new RISCVprogram()`, список файлов по `ASSEMBLE_ALL`/`ASSEMBLE_OPEN`, ассемблирование, сброс `FunctionRun.clear()`, обновление `TextSegmentWindow`, `FileStatus`, `MessagesPane`; главная панель переключается на вкладку «Edit», подсветка строки в редакторе снимается (`clearHighlightedLines()`).
- `RunGoAction.java` — запуск: проверка `FileStatus.isAssembled()`; если `FileStatus == TERMINATED` — сначала авто-Reset (`RunResetAction.performReset(mainUI)`) для перезапуска с начала; `processProgramArgumentsIfAny()` (`TextSegmentWindow.getProgramArguments()` → `new ProgramArgumentList(...).storeProgramArguments()`); при первом запуске — `FunctionRunSupport.prepareFunctionRunIfNeeded(mainUI)` (режим «запуск функции»: если нет `main`/`_start` — диалог аргументов; отмена останавливает запуск); анонимный `Observer` на `Simulator.getInstance()` — по `SimulatorNotice.SIMULATOR_STOP` через `EventQueue.invokeLater` вызывает `paused()` (PAUSE/BREAKPOINT) или `stopped()` (NORMAL/CLIFF/EXCEPTION/STOP/MAX_STEPS; при NORMAL — `FunctionRunSupport.reportFunctionResult()` → строка `Execution Done: ...` в Run I/O); затем `Globals.program.startSimulation(maxSteps, breakPoints)`. В `paused()` подсвечивается строка следующей инструкции в редакторе (`highlightStepSourceLine`), в `stopped()` она снимается (`clearHighlightedLines`).
- **SwingWorker не используется** (упоминания в старых комментариях устарели): симуляция — `new Thread(new SimThread(...), "RISCV")` в `Simulator.startSimulation()`. GUI-поток общается с ней через `SimulatorNotice` и `Globals.memoryAndRegistersLock`.
- `RunStepAction` (maxSteps=1): при первом нажатии F7, когда программа ещё не запущена, — состояние «Prepare»: `FunctionRunSupport.prepareFunctionRunIfNeeded` и подсветка первой строки `highlightStepSourceLine(PC)`, **без исполнения инструкции**; последующие F7 исполняют очередную инструкцию. На шаге подсвечивается строка следующей инструкции, при завершении (`done`/исключение) — `clearHighlightedLines()`. Если `FileStatus == TERMINATED` — перед шагом авто-Reset (`RunResetAction.performReset`). При завершении — `reportFunctionResult`.
- `RunBackstepAction` (`BackStepper.backStep()`; подсвечивает строку в редакторе), `RunResetAction` (сбрасывает и `FunctionRun.clear()`; **логика сброса вынесена в статический `performReset(VenusUI)`** и переиспользуется авто-Reset в `RunGoAction`/`RunStepAction`), `RunClearBreakpointsAction`, `RunSpeedPanel` (слайдер скорости, `speedTable`, `UNLIMITED_SPEED=40`; при unlimited пошаговая подсветка регистров отключается — см. `RegisterBlockWindow.update()`).
- **Подсветка строки выполнения в редакторе** (вкладка «Edit») управляется методами `VenusUI`: `highlightStep(int)` — подсветка в Text Segment (Execute), `highlightStepSourceLine(int)` — подсветка строки в редакторе (адрес→строка через `ProgramStatement.getAddress()/getSourceLine()/getSourceFile()`), `clearHighlightedLines()` — сброс на всех открытых файлах. Ставится на шаге/паузе/backstep, снимается до старта (Assemble/Reset) и после завершения.
- В состоянии `TERMINATED` действия Step (F7) и Go (F5) остаются доступными (`setMenuStateTerminated`), поэтому программу можно перезапустить без повторной сборки; повторный запуск начинается с авто-Reset (`RunResetAction.performReset`).
- **Открытие файла всегда показывает вкладку «Edit»**: `FileOpener.openFile` переключает главную панель на редактор, в том числе для уже открытого файла и при включённой настройке «Assemble on open».
- `FunctionRunSupport.java` — режим «запуск программы как функции» (нет `main`/`_start`): поиск точки входа (первый текстовый символ глобальной таблицы), парсинг Си-сигнатуры из комментария перед меткой, модальные диалоги аргументов, запись аргументов в `a0`–`a7`/стек, PC и sentinel-`ra`. Подробности — [04-extension-points.md](04-extension-points.md), раздел 3.
- В `paused()`/`stopped()` вручную обновляются все окна: `executePane.getRegistersWindow().updateRegisters()`, FP, CSR, `DataSegmentWindow.updateValues()`.

## 5. Настройки GUI (`rars/venus/settings/`)

- `SettingsAction.java` — обёртка `JCheckBoxMenuItem` → `Settings.Bool` → `handler(value)` в `VenusUI`.
- Диалоги: `SettingsEditorAction` (шрифты/цвета редактора), `SettingsHighlightingAction` (цвета подсветки таблиц), `SettingsExceptionHandlerAction`, `SettingsMemoryConfigurationAction`.
- Хранение настроек описано в [01-overview.md](01-overview.md) (Preferences → `Settings.properties` → статические дефолты). `src/Settings.properties` — только дефолты.

## 6. Инструменты (`rars/tools/`) и extras

- Интерфейс `Tool.java`: `getName()` + `action()`; инструмент — Observer `Memory`/`Register` либо прямой доступ под `Globals.memoryAndRegistersLock`.
- `ToolLoader.buildToolsMenu()` — сканирует каталог `rars/tools` по `.class`-файлам (работает и из JAR через ZipFile), рефлексией загружает классы с префиксом `rars.tools.`, реализующие `Tool`, строит меню Tools из `ToolAction`. **Новый инструмент = просто новый класс в пакете, меню построится автоматически.**
- `AbstractToolAndApplication.java` — база «tool + standalone-приложение»: диалог с Assemble/Run (свой поток `CreateAssembleRunProgram`).
- Конкретные: `BitmapDisplay`, `KeyboardAndDisplaySimulator`, `DigitalLabSim`, `TimerTool`, `InstructionCounter`, `InstructionStatistics`, `InstructionMemoryDump`, `MemoryReferenceVisualization`, `FloatRepresentation`, `CacheSimulator`, `BHTSimulator`/`BHTSimGUI`/`BHTEntry`/`BHTableModel` (branch history table).
- `rars/extras/Documentation.java` — консольный генератор markdown-таблиц (директивы, syscall'ы, инструкции basic/extended, rv64-only); отдельный `main()`.

## 7. Утилиты (`rars/util/`)

- `Binary` — конвертация int/hex/ascii; центральное место форматирования чисел (используется GUI, Simulator'ом и CLI-выводом).
- `SystemIO` — файлы для syscall-ввода/вывода (подмена stdin/stdout/stderr в headless-режиме).
- `PropertiesFile` — загрузка `Settings.properties`.
- `FilenameFinder` — сканирование каталогов классов (используется `InstructionSet`, `SyscallLoader`, `ToolLoader`).
- `MemoryDump` — CLI-дамп памяти; `EditorFont` — шрифт редактора.

## 8. Общие принципы GUI

1. **Наблюдаемость**: вся реакция GUI на симуляцию построена на `java.util.Observable` (`Simulator`, `Settings`, `Register`, `Memory`) — новые фичи UI встраивать в ту же модель.
2. **Потокобезопасность**: доступ к памяти/регистрам вне потока симуляции — только под `Globals.memoryAndRegistersLock`; обновление Swing — через `SwingUtilities.invokeLater`/`EventQueue.invokeLater`.
3. **Форматирование чисел** — единая точка `NumberDisplayBaseChooser.formatNumber*` поверх `rars.util.Binary`; учтите, что её используют и сегментные окна (`TextSegmentWindow`, `DataSegmentWindow`), не только регистры.
