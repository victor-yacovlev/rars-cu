# RARS - форк с изменениями для курса АКОС в ЦУ

Исходный проект здесь: https://github.com/thethirdone/rars

Список изменений по сранению с оригиналом - в файле [FORK-CHANGES.md](FORK-CHANGES.md)

## Сборка (Gradle)

Требуется JDK 21+ и Gradle. Проект зависит от submodule JSoftFloat - перед первой сборкой выполните:

```bash
git submodule update --init
```

Сборка и запуск:

```bash
./gradle-build.sh          # соберёт rars-cu-YYYYMMDD.jar в корне проекта
java -jar rars-cu-YYYYMMDD.jar   # запуск GUI (java -jar ... h - справка по CLI)
```

Имя jar-файла содержит дату сборки в формате `rars-cu-YYYYMMDD.jar`; скрипт выводит имя созданного файла. Целевая версия Java - 21 (сборка идёт любым JDK 21+ с `--release 21`). Прогон тестов: `gradle runTests`.
