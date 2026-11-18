# QA_REPORT — mcfd_compat 1.0.0-26.1.2 (Minecraft 26.1.2 / NeoForge 26.1.2.126)

Дата: 2026-11-18 · Дерево: `/root/home/forks/colonial-addons/minecolonies-farmers-delight-compat` · JAR: `build/libs/mcfd_compat-1.0.0-26.1.2.jar`

## Среда и воспроизведение

- Java 25.0.5 (`/usr/lib/jvm/java-25-openjdk-amd64`); Gradle 9.4.0; ModDevGradle 2.0.148; NeoForge 26.1.2.126.
- Стек рана: MineColonies 1.1.1397-26.1.2 (mavenLocal), Structurize 0.0.1-26.1.2, BlockUI 0.0.1-26.1.2, Domum Ornamentum 1.0.0-local, Multipiston 0.0.1-26.1.2, Farmer's Delight 1.3.4-2 (наш порт, `worktrees/farmersdelight-26.1.2`).
- Сборка: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew build` — PASS (2 warnings о deprecated `ItemStackHandler` в NeoForge items API, только в аудите; флот использует этот класс и в других модулях).
- Smoke: `OUT=... QA_OUT=/root/home/forks/.port-tools/qa-out bash /root/home/forks/.port-tools/qa_server.sh /root/home/forks/colonial-addons/minecolonies-farmers-delight-compat mcfd_compat-smoke` (RCON-команды через `CMDS`).

## Прогоны

| Прогон | Что делал | Результат |
|---|---|---|
| smoke-1/2 | Сервер + стартовый аудит | Старт OK; **11 ложных срабатываний** «hook not applied» + `Unknown item: multipiston:multipistonblock`. Диагностика через `mixin.debug.export`: хуки применены, но sponge-mixin 0.17.4 (FML 11) переименовывает их (`handler$zzb000$mcfd_compat$mcfdCompat$…`, `redirect$…`) — рефлексия-проверка аудита учила суффиксное совпадение. multipiston добавлен в runtime-стек (ошибка — данные MineColonies, не наша) |
| smoke-3 | Чистый сервер + `/mcfd_audit` через RCON | **STARTED, saved=yes, errors=0**. Стартовый аудит: `passed: 89 resource recipes, crafters {chef_smelting=8, chef_crafting=61, farmer_crafting=3, fletcher_crafting=1, baker_crafting=13, planter_crafting=3}`. `/mcfd_audit`: **PASS (6 crafters used)** |
| smoke-4 | `/reload` + повторный `/mcfd_audit` | Reload датапаков OK (`[Rcon: Reloading!]`, reload логи MineColonies); после перезагрузки **audit PASS** — рецепты перечитываются без дублей/потерь |
| smoke-5 (reopen) | Второй запуск **того же мира** (`run/server`, level `qa-11180828`), RCON `/mcfd_audit`, stop | Мир открыт заново, **audit PASS**, `All dimensions are saved`, ошибок 0 |

## Что подтверждено

| Проверка | Результат / область |
|---|---|
| Загрузка мода | В Mod List сервера; зависимости minecolonies/farmersdelight резолвятся (required, ordering AFTER) |
| Стартовый само-аудит (`ServerStartedEvent`) | PASS: 89 JSON статически валидны (крафтер-ключи, inputs/result, chef_smelting→minecraft:furnace), 89 распарсены CustomRecipeManager, все 6 крафтеров покрыты, у каждого FD-блюда есть рецепт |
| Применение миксинов | 8 common-хуков подтверждены рефлексией в audit + байткод-экспортом (`handler$`/`redirect$` имена) на FoodUtils, ItemStackUtils, RecipeStorage, Stack, WorkerUtil, AbstractEntityAICrafting, EntityAIWorkFarmer |
| Live-crafting | Все 89 рецептов скрафтились настоящим `RecipeStorage.fullfillRecipeAndCopy` (entity-free перегрузка, пустой loot-контекст): инвентарь собирался из inputs+tools, первичный выход совпал (`isSameItemSameComponents`) |
| Food values | `FoodUtils.getFoodTier/getFoodValue` на живом сервере используют совместимые значения (равны `FarmerDelightMealValues.getMinecoloniesTier/getCitizenFoodValue`) |
| Эквивалентность ингредиентов | Прямая (IngredientEquivalents) и через MineColonies `Stack.matches` в обе стороны: rice/tomato/onion/cabbage/wheat_dough↔bread_dough |
| Команда `/mcfd_audit` | Через RCON (консоль) — полный PASS; регистрация LEVEL_GAMEMASTERS |
| `/reload` | Аудит PASS после перезагрузки датапаков |
| save/reopen | Мир пересоздан→сохранён→открыт повторно: аудит PASS, ошибок 0 |

## Границы подтверждения (не подтверждено)

- **Живой ресторан/повар/курьер в работающей колонии**: не тестировалось — серверный smoke без клиента/колонии. Пути RestaurantMenuModule/доставки ингредиентов курьером (`WorkerUtil.keepEquivalentIngredient`, `AbstractEntityAICrafting.countEquivalentIngredient`, `RecipeStorage.consumeEquivalentIngredient`) проверены применением хуков и live-crafting-ом, но не реальной колонией.
- **Клиентские GUI-миксины** (меню ресторана, список рецептов, окно поля): скомпилированы против BlockUI/Structurize, применяются только в клиенте (Env=SERVER их пропускает) — визуальный прогон не выполнялся.
- **Rich Soil / Rich Soil Farmland / Rice в мире**: canSurvive-сценарии (посадка риса в воду / rice_panicles, посадка «специальных» культур на Rich Soil, поведение фермерского AI) — только аудиторские проверки констант, не in-game поведение.
- **Nourishment в реальном поедании**: FoodValues.ConsumableValues порога FD-порта (600/1200/3600/6000 тиков) сверены 1:1, но фактическое поедание блюда гражданином не игралось.
- **Клиентские тултипы** (`ClientFoodTooltips`): не проверялись (нет клиента).
- **Tool-damage ветка live-crafting** (`citizen != null && isTool`): entity-free путь её не покрывает (только порча инструментов граждан, не совместимость).
