<div align="center">

<img src="desktop/src/main/resources/icons/javajournal.png" alt="Логотип JavaJournal" width="112">

# JavaJournal

**Учебный журнал в одном приложении.**

Неофициальный клиент электронного журнала для студентов **Академии ТОП**: расписание, домашние задания, оценки, посещаемость и календарь.

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
![Windows](https://img.shields.io/badge/Windows-10%2F11-3478C0?style=flat-square)
![macOS](https://img.shields.io/badge/macOS-Intel%20%2F%20Apple%20Silicon-595959?style=flat-square)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)

[**Скачать JavaJournal**](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases) · [**Сообщить об ошибке**](https://github.com/PaCcBeTu3aKaT/JavaJournal/issues) · **Discord:** `@kitrr1x`

</div>

---

## Возможности

| Раздел | Возможности |
| :-- | :-- |
| **Главная** | Ближайшее занятие, активные задания, средний балл и посещаемость. |
| **Расписание** | Просмотр по дням и неделям, переключение дат и быстрый возврат к сегодняшнему дню. |
| **Домашние задания** | Фильтрация по статусам, сортировка по предметам и срокам, просмотр описаний и комментариев преподавателей. |
| **Вложения** | Скачивание доступных материалов преподавателя и ранее отправленных работ. |
| **Оценки** | Оценки по предметам и статистика за месяц или учебный год. |
| **Посещаемость** | Статистика присутствия и пропусков занятий. |
| **Календарь** | Обзор месяца с занятиями, посещаемостью и оценками по датам. |
| **Профиль** | Информация о студенте, группе и версии приложения. |

Доступны светлая и тёмная темы. На Android предусмотрено восстановление сессии при повторном запуске приложения.

## Скачать

Готовые сборки публикуются в **[GitHub Releases](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases)**. Наличие конкретной сборки зависит от опубликованных файлов релиза.

| Платформа | Формат | Запуск |
| :-- | :-- | :-- |
| **Windows 10/11 (x64)** | [Портативный ZIP](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases) | Распакуйте архив целиком и откройте `JavaJournal.exe`. |
| **macOS — Intel** | [ZIP с приложением](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases) | Распакуйте и откройте `JavaJournal.app`. |
| **macOS — Apple Silicon** | [ZIP для ARM64](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases) | Скачайте сборку для Apple Silicon и откройте `JavaJournal.app`. |
| **Android 8.0+** | [APK](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases) | Скачайте файл на телефон и установите приложение. |

Настольные сборки включают необходимые компоненты Java. На macOS неподписанное приложение может потребовать дополнительного подтверждения открытия в настройках безопасности.

## Начало работы

1. Скачайте сборку для своей платформы из [Releases](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases).
2. Запустите JavaJournal и войдите с учётной записью Академии ТОП.
3. Перейдите к расписанию, заданиям, оценкам, посещаемости или календарю.

Для получения актуальных учебных данных необходимо подключение к интернету.

## Версия 2.0.0

Крупное обновление мобильного интерфейса и календаря. На Android переработаны главная страница и навигация, добавлены полноэкранный просмотр домашнего задания и фоновое обновление без отдельной заставки. Календарь доступен как на телефоне, так и на компьютере.

Полный список изменений — в [описании релиза 2.0.0](https://github.com/PaCcBeTu3aKaT/JavaJournal/releases).

## Ограничения

- Отправка домашних заданий, удаление отправленной работы и запрос на пересдачу пока не подключены к API.
- Отдельное отображение лабораторных заданий ещё не реализовано.
- Типы посещаемости, в том числе опоздания, отображаются только при наличии достоверных данных сервера. Неизвестные статусы не интерпретируются произвольно.
- Доступность вложений и их исходных имён зависит от ответа сервера.
- Новые версии можно получить через GitHub Releases; автоматической установки обновлений нет.

## Технологии

JavaJournal разрабатывается на **Java**: настольный интерфейс — **JavaFX**, мобильный — **Android SDK**. Обработка данных и взаимодействие с API вынесены в общий модуль `core`.

<details>
<summary><b>Сборка из исходников</b></summary>

Для сборки необходим **JDK 21**.

### Windows

Запустите `BUILD_WINDOWS.bat` в корне проекта или выполните:

```powershell
.\gradlew.bat :desktop:packageWindows
```

Готовый портативный архив: `desktop/build/distributions/`.

### macOS

На Mac запустите `BUILD_MAC.command` или выполните:

```bash
./gradlew :desktop:packageMac
```

Готовые файлы: `desktop/build/distributions/`. Сборка создаётся для архитектуры текущего Mac.

### Android

Откройте папку `android-studio/` в Android Studio. Соседние модули `android/` и `core/` подключаются через Gradle. Нужна Android SDK Platform 35.

Из каталога `android-studio/` выполните:

```powershell
.\gradlew.bat :android:assembleDebug
```

Отладочный APK: `android/build/outputs/apk/debug/android-debug.apk` относительно корня репозитория. Для публичного выпуска требуется отдельно подписанная релизная сборка.

</details>

## Статус проекта

**JavaJournal развивается как независимый проект.** Ошибки и предложения можно оставить в [GitHub Issues](https://github.com/PaCcBeTu3aKaT/JavaJournal/issues) или написать разработчику в Discord: **`@kitrr1x`**.

JavaJournal не является официальным приложением **Академии ТОП** и не разрабатывается от её имени.
