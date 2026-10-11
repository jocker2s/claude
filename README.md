# Claude In Game (Fabric 1.20.1)

Команда `/claude <запрос>` в игре: Claude создаёт датапак (рецепты, лут, функции, достижения, теги…) и сразу применяет его через `/reload`. Перезапуск игры не нужен.

## Сборка
Нужны JDK 17 и Gradle 8.5 (под него рассчитан Loom 1.5).
1. Положите папку проекта рядом с gradle wrapper из шаблона https://github.com/FabricMC/fabric-example-mod (скопируйте `gradlew`, `gradlew.bat`, папку `gradle/`) или выполните `gradle wrapper --gradle-version 8.5`.
2. `./gradlew build`
3. Готовый мод: `build/libs/claude-in-game-1.0.0.jar` → в папку `mods` вместе с Fabric API 0.92.2+1.20.1.

## Настройка
Нужен API-ключ из https://console.anthropic.com (оплата за токены, лимиты API есть — «безлимита» нет).
- В одиночной игре: `/claude key sk-ant-...`
- Или вручную в `config/claude-in-game.json` (`apiKey`, `model`, `maxTokens`, `historyMessages`).

## Команды (нужен уровень доступа 2, в одиночной игре — читы включены)
- `/claude <запрос>` — попросить Claude что-то изменить
- `/claude reset` — очистить историю диалога
- `/claude model <имя>` — сменить модель (по умолчанию `claude-sonnet-5-5`)
- `/claude key <ключ>` — сохранить ключ (только в одиночной игре)

## Ограничения
- Датапаки не умеют добавлять новые блоки, предметы и мобов с нуля и менять Java-код — для этого пришлось бы собирать и перезапускать мод. Claude обходится CustomModelData, командами, scoreboard и display-сущностями.
- Файлы пишутся только в `world/datapacks/claude_generated`, только `data/...`, расширения `.json`, `.mcfunction`, `.txt`.
- Датапак выполняет команды с правами сервера: просите Claude только то, чему доверяете, и проверяйте созданные файлы.
