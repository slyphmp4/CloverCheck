# MODERNIZATION VERDICT

**Verdict: MOSTLY MODERN** — аудит 2026-09-12, режим AUDIT + FIX.

## Target summary

- Minecraft/Paper: 26.2, API `26.2.build.121-stable`.
- Дополнительная платформа: Cardboard 26.2.19+ согласно README; Folia не заявлена.
- Минимальная/основная Java: 25; локально Temurin 25.0.4+7.
- Сборка: Gradle Wrapper 9.7.1, Kotlin DSL, Java, один модуль.
- Артефакт: `build/libs/CloverCheck-1.1.1.jar`, Shadow; SQLite внутри, Paper/Adventure с сервера. Исходная версия: 1.1.0.
- CI: Ubuntu, Java 25, Gradle Wrapper. Публикация — artifact в GitHub Actions.
- Аудит выполнен из исходного архива без Git. Резервная копия сохранена локально; перед публикацией baseline сверён с main.
- Baseline: `gradlew.bat clean build` PASS; 26 тестов, 0 ошибок, предупреждений компилятора нет.

## Blockers

Блокеров исходной сборки нет. Наличие работающего Paper/Cardboard-сервера в этой папке не установлено. Утверждение README о прошлых smoke-тестах не является проверкой текущих изменений.

## Dependency updates

| Dependency | Current | Scope / Embedded | Latest verified | Status / Action |
|---|---|---|---|---|
| Paper API | 26.2.build.121-stable | compileOnly + test / нет | сервер 26.2 build 123, версия API отдельно не подтверждена | Сохранить целевой API; номер server build не доказывает наличие нового API |
| sqlite-jdbc | 3.53.2.1 | implementation / да | 3.53.4.0 | UPDATE AVAILABLE, исправляющее обновление |
| JUnit BOM/Jupiter/launcher | 6.1.3 | test / нет | 6.1.3 | CURRENT |
| Shadow | 9.6.1 | build plugin / нет | 9.6.1 | CURRENT |
| Gradle Wrapper | 9.7.1 | build / нет | 9.7.1 | CURRENT, SHA256 дистрибутива закреплён |
| Mockito | отсутствует | добавляется только в test / нет | 5.23.0 | Для регрессионных тестов событий и сервисов Bukkit |

Источники: [Gradle metadata](https://services.gradle.org/versions/current), [Shadow](https://plugins.gradle.org/plugin/com.gradleup.shadow), [SQLite changelog](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0), [JUnit](https://github.com/junit-team/junit-framework/releases/tag/r6.1.3), [Mockito](https://github.com/mockito/mockito/releases/tag/v5.23.0), [Paper API downloads](https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest).

Все текущие зависимости используются. SQLite исключает SLF4J, предоставляемый сервером. Ненужного shading серверных API, HTTP-репозиториев, динамических Maven-версий, NMS и внешних сетевых запросов в игровом коде нет.

## Confirmed bugs / reliability / performance

| ID | Category / Priority | Component / Trigger | Root cause / Impact | Recommended change / Verification |
|---|---|---|---|---|
| CC-01 | BUG / IMPORTANT | ActionService.execute: сторонняя команда бросает RuntimeException | Исключение выходит из обработки результата/тика; остальные команды и проверки в этом тике не обрабатываются | Перехватить ошибку отдельной команды, логировать и аудитировать, продолжить; тест со сбойным dispatcher |
| CC-02 | BUG / IMPORTANT | confirmConfess после отключения confess.enabled при reload | Проверка флага есть только при запросе подтверждения; возможно наказание после отключения функции | Повторно проверить флаг, сбросить подтверждения; регрессионный тест |
| CC-03 | RELIABILITY / IMPORTANT | SQLite shutdown: ошибка записи последнего snapshot | close не вызывается; shutdownNow при тайм-ауте может выбросить ещё не запущенную задачу закрытия | Закрывать connection через try-with-resources; дренировать очередь; возвращать failed future после shutdown; тесты ошибок и повторного shutdown |
| CC-04 | PERFORMANCE / NORMAL | /check history <name>, запуск игроком с правом history | lower(player_name) не соответствует индексу player_name; полный проход/сортировка растущей истории | Дополнительный expression index по lower(player_name), started_at; EXPLAIN QUERY PLAN и тест поиска без учёта регистра |
| CC-05 | BUG / NORMAL | CheckUiService + handleQuit: выход и завершение проверки офлайн | hide убирает только BossBar; snapshot Blindness остаётся до disable, может пережить следующую проверку | Очистить принадлежащие проверке UI/эффект при выходе; тест |
| CC-06 | BUG / NORMAL | hideAll / UI reload | При disable очищаются Title/ActionBar всех онлайн-игроков; отключение Title при reload оставляет длинный Title | Отслеживать игроков, которым плагин показал Title/ActionBar; убирать только свой UI и при отключении опций; тесты |
| CC-07 | BUG / NORMAL | CheckProtectionListener: повторная проверка / разрешённый teleport | Anchor привязан только к UUID игрока; может пережить смену сессии, teleport или отключение movement | Привязать anchor к ID сессии, сбрасывать/обновлять при изменении условий; тесты движения/teleport |
| CC-08 | BUG / NORMAL | handleJoin после восстановления STARTING офлайн | Ветка join не активирует STARTING: ticker не обновляет UI, quit не переводит в DISCONNECTED | Активировать STARTING при входе; регрессионный тест |
| CC-09 | BUG / IMPORTANT | reloadPlugin при одном некорректном YAML | Остальные файлы уже применялись, хотя сообщение обещало сохранить прежние настройки | Подготовка всех файлов перед публикацией настроек; тест отказа/успеха и сохранения DB file до restart |
| CC-10 | BUG / NORMAL | ConfigService.readSettings с некорректным config v4 | Миграция записывала файл до проверки остальных значений | Записывать миграцию после успешной валидации; тест побайтовой сохранности некорректного файла |
| CC-11 | RELIABILITY / NORMAL | callback SQLite/chat одновременно с disable | Между isEnabled и runTask плагин может отключиться; исключение из scheduler | Общий PluginTasks; подавлять IllegalPluginAccessException только если плагин уже отключён; 3 теста |
| CC-12 | RELIABILITY / NORMAL | onDisable при исключении в очистке UI | Закрытие repository пропускалось | Закрытие в finally, включая дренирование ранее поставленных записей; тест |

Уверенность перечисленных находок: высокая, подтверждены чтением соответствующих ветвей. Существующий формат конфигурации, записей БД, команды, права и политики наказания сохраняются.

## Deprecated/removed APIs

Компилятор с `-Xlint:deprecation`, `-Xlint:removal`, `-Xlint:unchecked` не сообщил проблем на целевом API. Legacy AsyncPlayerChatEvent загружается отражением только для Cardboard; это намеренная совместимость, не кандидат на механическую замену. Статус удаления этого API в будущих Cardboard не подтверждён.

## Legacy compatibility code

| Area | Still needed | Action / Confidence |
|---|---|---|
| LegacyCheckChatBridge | Да, заявленный Cardboard | Сохранить; не использовать Paper-only chat на всех платформах |
| Пересоздание BossBar и клиентский Blindness | Да, заявленная совместимость Cardboard | Сохранить; нужен живой smoke-тест после изменений |
| Миграция config v4 → v5 | Да, существующие конфигурации | Сохранить пользовательские настройки и путь обновления |
| /check start | Совместимый алиас и способ проверить игрока с именем подкоманды | Сохранить |
| LegacyMiniMessageCompat | Шаблоны ресурсов используют &-цвета | Сохранить |

## Dead code / unused configuration

- `MessageService.rawLines`, `SessionRegistry.byId/byModerator`, `CheckSession.reason`: нет внутренних вызовов production; POSSIBLY DEAD — REVIEW, публичные методы не удалять без политики внешнего API.
- Методы обработчиков событий — NOT DEAD: регистрируются Bukkit; enum-значения используются в config/SQLite.
- `locale`: READ-ONLY metadata, валидируется, но не переключает сообщения. Сохранить ключ совместимости и пояснить в config.
- `chat.yml.version`: метаданные, не участвуют в миграции. `config.yml.version`: используется миграцией.
- Подтверждённого мёртвого private-кода, требующего удаления, нет.

## Execution / database / resource review

- JDBC выполняется на отдельном последовательном executor, PreparedStatement/ResultSet закрываются, history ограничена 1–100 строками. N+1-запросов и запросов каждый тик нет.
- Игровой ticker один, раз в 20 тиков; отменяется при shutdown. Bukkit-вызовы UI/игрового мира остаются в серверном потоке; async chat передаёт доставку в scheduler. Registry/Session синхронизированы.
- Чтение YAML при startup/reload синхронное; редкое административное действие. Ожидание SQLite при disable ограничено 5 секундами; после истечения задача закрытия должна получить возможность выполниться.
- Очередь JDBC не ограничена; history/info доступны staff. При реальном массовом использовании полезны замеры и лимит запросов, сейчас не менять контракт отказов без данных о нагрузке.
- История и аудит не имеют retention-policy. Удаление старых записей без согласованного срока хранения не добавлять.
- SQLite writes и команды наказания не образуют общую транзакцию: crash между записью и внешней командой не обеспечивает exactly-once. Требуется отдельный дизайн idempotency/outbox с учётом punishment-плагина; не обещать гарантию.
- Повреждённая строка активной сессии может остановить восстановление всего плагина. Автоматическое пропускание могло бы молча снять проверку: сохранить отказ с диагностикой, не удалять данные.

## Build-system / maintainability findings

- CI actions закреплены major-тегами, не SHA. Это возможность дополнительной фиксации сборки; не блокер.
- Wrapper проверяет SHA256 дистрибутива. Воспроизводимость JAR и его содержимое нужно проверить после исправлений.
- Исходный `reloadPlugin` перезагружал три файла независимо; CC-09 исправляет применение runtime-настроек при ошибке валидации. Это не транзакция файловой системы: администратор по-прежнему редактирует YAML самостоятельно.
- Исходное окно disable между isEnabled и runTask закрыто CC-11. Неожиданные ошибки scheduler при включённом плагине не скрываются.

## Change buckets / prioritized plan

1. **REVIEW BEFORE FIXING**: CC-01–12; review проведён по текущему коду и контрактам, пользователь разрешил исправления. Проверено регрессионными тестами storage/anchor/UI/services/reload; живые сетевые эффекты требуют smoke-теста.
2. **SAFE AUTOMATIC FIXES**: SQLite patch update 3.53.4.0; пояснение неиспользуемой locale; отсутствие ложных предупреждений saveResource на повторном старте.
3. **DO NOT AUTOMATICALLY CHANGE**: минимальная платформа/Java, удаление Cardboard-совместимости, формат базы, retention, exactly-once наказания, публичные неиспользуемые методы.

## Expected behavior changes

Сбой отдельной команды не обрывает следующие команды; отключённое признание не подтверждается; UI очищается при выходе и не затрагивает посторонних игроков; движение привязано к текущей проверке; восстановленная STARTING-сессия активируется при входе. Некорректный reload сохраняет прежние runtime-настройки целиком, некорректная v4-конфигурация не переписывается миграцией. History возвращает те же результаты, используя подходящий индекс. При тайм-ауте shutdown задача закрытия БД продолжает дренировать очередь в daemon-потоке; это не гарантирует сохранение при принудительном завершении JVM.

## Current-version checks not verified

Отдельная последняя версия Paper API, текущие исправления Cardboard и latest всех транзитивных зависимостей не подтверждены. Совместимость вне заявленного диапазона не проверялась.

# IMPLEMENTATION RESULT

## Changes applied / Bugs fixed

Исправлены CC-01–12. Версия плагина повышена с 1.1.0 до 1.1.1. При повторном запуске saveResource больше не выдаёт ложные предупреждения о существующих messages.yml/chat.yml. Обновлены README и пояснение locale.

## Dependencies updated

- sqlite-jdbc: 3.53.2.1 → 3.53.4.0. Изменения upstream: SQLite 3.53.4 и исправление границы UTF-8 reader в extension-functions; major-миграции нет.
- Mockito 5.23.0 добавлен только для тестов, запускается явным Java agent; в production JAR отсутствует.
- Paper, Gradle, Shadow, JUnit и Java target сохранены.

## Deprecated APIs migrated / Legacy code removed / Dead code removed

Нет: компилятор не выявил требующих замены API; compatibility paths и публичные контракты сохранены. Миграция config v4 → v5 сохранена, изменён момент записи после валидации.

## Performance/reliability changes

Индекс `idx_checks_player_name_lower` создаётся через IF NOT EXISTS; таблицы и значения существующих записей не преобразуются. EXPLAIN QUERY PLAN подтвердил использование индекса и отсутствие временной сортировки. Snapshot-ошибка при shutdown не мешает попытке сохранить остальные snapshots и закрыть connection. Повторный shutdown безопасен; новые запросы после него возвращают exceptional future.

## Build result

**PASS** — Java 25.0.4, `gradlew.bat clean build --refresh-dependencies --console=plain --warning-mode all` после обновления SQLite; затем `clean build --console=plain --warning-mode all` для финальной 1.1.1. Предупреждений deprecation/removal/unchecked нет.

## Test result

**PASS: 46 тестов, 0 failures, 0 errors** (baseline 26, добавлено 20).

- SQLite: чтение/запись/восстановление, поиск по имени/UUID, лимит/порядок, план запроса, сбой последней записи, повторный shutdown, тайм-аут с последующим закрытием.
- Bukkit mocks: продолжение действий после исключения/необработанной команды, блокировка отключённого признания, STARTING → ACTIVE → DISCONNECTED, очистка UI при выходе, смена сессии/настроек/teleport для freeze anchor, адресная очистка Title/ActionBar, reload, гонка отключения с callback.
- Реальные YAML: некорректная v4-конфигурация не переписывается; смена имени файла БД откладывается до рестарта.
- Отдельно запущен JDBC/native smoke из production JAR: ServiceLoader находит org.sqlite.JDBC, in-memory SQLite открывается, `sqlite_version()` возвращает 3.53.4. В classpath добавлен только серверный SLF4J API.
- JAR: нет дубликатов записей, Paper/Bukkit/Adventure/Mockito/JUnit не упакованы; JDBC provider, 20 native-библиотек, plugin.yml 1.1.1 / api-version 26.2 на месте; class major 69 (Java 25).
- Повторная сборка Shadow с `--rerun-tasks` дала идентичный SHA256: `b79ec5e56bcc23a093bdd0702a4c442478498f539582dcfb7ba01c39c6c351af`.
- Diff с исходной копией проверен; `git diff --no-index --check` без замечаний. Код завершения 1 у diff --stat означает наличие изменений, не ошибку сборки.

## Remaining warnings

В тестах Mockito JVM сообщает об ограничении CDS после добавления bootstrap classpath; это не предупреждение плагина. Изолированный JDBC smoke без серверного logging backend сообщает SLF4J NOP fallback. Ошибки команд/SQLite, намеренно создаваемые тестами, попадают в тестовый лог и не означают падение тестов.

## Deferred changes / Manual review required

Живой Paper/Cardboard smoke-тест в этой среде не проводился. На сервере проверить два клиента: start, camera/freeze, приватный чат, clean/cancel, quit/rejoin, подтверждение признания, timeout, reload и restart с активной проверкой. Для проверки наказаний использовать тестовую конфигурацию команд, исключающую реальные баны.

Взаимодействие с другими плагинами, меняющими Blindness/Title во время проверки, не эмулировалось; общие Bukkit-эффекты не имеют владельца. Существующая модель восстановления Blindness сохранена. Защита не обещает совместимость с плагинами, которые отменяют решения CloverCheck на более поздних обработчиках событий.

Не вводились новая support-policy, удаление истории, exactly-once внешних наказаний, major-обновления, удаление публичных методов и Cardboard bridges. Причины и границы перечислены выше.

## Compatibility status / Release readiness

**READY WITH NOTES**: собирается на прежнем целевом API/Java, конфигурация и данные совместимы, автоматические проверки проходят. Окончательная эксплуатационная проверка на конкретном Paper/Cardboard и наборе сторонних плагинов остаётся за живым smoke-тестом.
