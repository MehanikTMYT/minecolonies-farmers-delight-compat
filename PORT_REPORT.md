# PORT_REPORT — Farmer's Delight × MineColonies Compatibility Patch → Minecraft 26.1.2 / NeoForge 26.1.2.126 (Java 25)

Дата: 2026-11-18 · Дерево: `/root/home/forks/colonial-addons/minecolonies-farmers-delight-compat`
Upstream: `https://github.com/anclyakeks/minecolonies-farmers-delight-compat` (GPL-3.0, 18 Java-источников, клон-образец: `/root/home/forks/.agent-sandbox/mc-fd-compat/upstream/`)
Наш модуль: modId `mcfd_compat`, версия `1.0.0-26.1.2`, пакет `dev.anclyakeks.compat.mcfd`, лицензия **GPL-3.0-only** (текст GPL-3.0 включён в JAR)
JAR: `build/libs/mcfd_compat-1.0.0-26.1.2.jar` (152 записи, ~83 KB)

## 1. Итог

Самостоятельный GPL-3.0 модуль совместимости Farmer's Delight ↔ MineColonies
(перевод FD-продуктов в систему питания MineColonies: крафтеры пекарни/кухни/фермы/
портного/плантера готовят 89 FD-блюд, рестораны принимают их как полноценные меню,
ингредиенты FD эквивалентны «родным» продуктам колонии, Rich Soil и рис интегрированы
в фермерский AI). Это **не** полный порт мода, а патч-порт: upstream не имеет собственных
блоков/предметов — его содержимое это data-рецепты (89 JSON), 12 миксинов и связи.

Сборка: ноль ошибок компиляции, JAR собран, серверный smoke-прогон чистый (errors=0),
само-аудит PASS (см. `QA_REPORT.md`).

**Важно (лицензия):** upstream — GPL-3.0. По правилам флота GPL-код **не вливается** в
MIT/LGPL-компендиумы (Culinary/Colonial Compendium) — модуль живёт отдельно в
`colonial-addons/` со своим деревом и своим JAR. Атрибуция автора upstream сохранена
(`dev.anclyakeks.*` пакет, автор в mods.toml).

## 2. Состав модуля

### Data (89 crafterrecipes + теги + lang)

| Раздел | Состав |
|---|---|
| `data/mcfd_compat/crafterrecipes/` | **89 рецептов**: baker 13, chef_crafting 61, chef_smelting 8, farmer 3, fletcher 1, planter 3 — все 92 `farmersdelight:*` id блюд/ингредиентов есть в FD-порте 1.3.4-2 |
| `data/c/tags/item/` | 6 тегов (`foods/*`) — эквивалентность FD ↔ ванильные ингредиенты |
| `data/minecolonies/tags/item/compostables_rich/` | 1 тег — Rich Soil/Farmland в компост колонии |
| `assets/mcfd_compat/lang/` | `en_us.json`, `ru_ru.json` (названия блюд для меню ресторана) |

Рецепты в системе питания MineColonies, не дубли: каждое FD-блюдо имеет ровно один
крафтер-рецепт; стоимостной баланс сохранён (inputs FD-рецептов, без дешёвых замен).

### Java (19 файлов)

| Пакет | Содержимое |
|---|---|
| `McfdCompatMod` | входная точка (mod-constructor, инжект IEventBus) |
| `config/McfdCompatConfig` | ModConfigSpec: переключатели совместимости питания/эквивалентности |
| `food/FarmerDelightMealValues` | маппинг FD-блюд → ценность/тир MineColonies (пороги 24/16 от базового значения, floor 12, cap 26; Nourishment-бонус: 6000+ тиков → 4.0, 3600 → 3.0, 1200 → 2.0, >0 → 1.0 — 1:1 с FD-портом) |
| `ingredient/IngredientEquivalents` | эквивалентность FD ↔ minecolonies/c-теги (RICE, TOMATO, ONION, CABBAGE, WHEAT_DOUGH→BREAD_DOUGH и др.) |
| `client/ClientFoodTooltips` | тултипы блюд: тир/ценность для граждан (ItemTooltipEvent) |
| `mixin/` | 8 common-миксинов (см. §3) |
| `mixin/client/` | 4 client-миксина (GUI меню ресторана, список рецептов, окно поля) |
| `audit/` | `McfdCompatAudit` + `McfdAuditEvents` — само-аудит вместо upstream-гаметестов (§4) |

## 3. Адаптации при портировании

### API26-дельты (NeoForge 26.1.2 / Mojang-маппинг 26.1)

| Upstream 1.21.x | Порт 26.1.2 |
|---|---|
| `ResourceLocation` | `net.minecraft.resources.Identifier` (`fromNamespaceAndPath`/`parse`) |
| `FoodProperties` (nutrition/saturation поля + getFoodProperties) | record `nutrition()`/`saturation()`; еда — пара FoodProperties + `DataComponents.FOOD`/`CONSUMABLE` |
| Nourishment-детект по food effects | `stack.get(DataComponents.CONSUMABLE)` → `Consumable.onConsumeEffects()` → `ApplyStatusEffectsConsumeEffect.effects()` → сравнение `instance.getEffect().value() == ModEffects.NOURISHMENT.value()` |
| `ForgeRegistries.ITEMS.getValue()` | `BuiltInRegistries.ITEM.getValue(Identifier)` (nullable; проверка на null/AIR) |
| `FoodUtils.getFoodValue(stack, food, research)`/`getFoodTier(stack)` | сигнатуры с ItemStack; nourishment-бонус из CONSUMABLE |
| `@EventBusSubscriber`/`ModConfigSpec`/`sendSuccess(Supplier,…)` | актуальные NeoForge-формы; команды: `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` (permission-level система 26.x, `hasPermission(int)` больше нет) |
| `FarmBlock` | `FarmlandBlock` (переименование маппинга) |

### Canvas и ключи рецептов

- Upstream ставил canvas `fletcher_custom` — в 26.1.2 такого крафтера нет; переведён на
  `fletcher_crafting` (флотский прецедент). Ключи рецептов = `jobEntry.getPath()+"_"+getId()`,
  все 89 ключей уникальны и разбираются CustomRecipeManager.

### Ретаргет лямбд миксинов (javap-верифицировано по нашему jar)

| Цель | Upstream | Наш jar |
|---|---|---|
| `RecipeStorage` | `lambda$fullfillRecipeAndCopy$4` (3-параметровая) | `lambda$fullfillRecipeAndCopy$2` |
| `RecipeStorage` | `lambda$fullfillRecipeAndCopy$2` (4-параметровая: ItemStack,ItemStorage,boolean,ItemStack) | `lambda$fullfillRecipeAndCopy$0` |
| `AbstractEntityAICrafting` | `lambda$checkForItems$4` | `lambda$checkForItems$0` (getRecipe$2/$3 совпадают) |

Остальные цели (FoodUtils.getFoodValue/getFoodTier, ItemStackUtils.consumeFood, WorkerUtil.
isPartOfRecipe, Stack.matches/getStack, EntityAIWorkFarmer все 8 методов, walkToSafePos как
единственный вызов → @Redirect owner подтверждён байткодом, RestaurantMenuModuleWindow,
WindowField, WindowSelectRes 5-arg ctor, WindowCitizenInventory.activeCitizenInventory,
ICitizenHappinessHandler-конструкторы) совпадают 1:1.

### Аудит вместо гаметестов

Upstream `McfdCompatTests` использовал `net.neoforged.neoforge.gametest.GameTestHolder`,
которого нет в neoforge 26.1.2.126 (флотская находка). Заменён на:
- **load-time само-аудит** (`ServerStartedEvent`): статика 89 JSON (крафтер-ключи, inputs/
  result/intermediate, chef_smelting → furnace), покрытие блюд, parse-count CustomRecipeManager,
  применённость хуков миксинов (рефлексия), наличие обязательных предметов в реестрах,
  **live-crafting всех 89 рецептов** через настоящий `RecipeStorage.fullfillRecipeAndCopy`
  (entity-free Level-перегрузка: пустой loot-контекст, как в консольных путях MineColonies —
  работает и без игроков; citizen-only ветка порчи инструмента не проверяется этим путём);
- **`/mcfd_audit`** (LEVEL_GAMEMASTERS, работает из консоли/RCON): всё вышеперечисленное +
  FoodUtils tier/value эквивалентность + эквивалентность ингредиентов (прямая и через
  `Stack.matches`) + live-crafting.

## 4. Инфраструктура сборки

- Gradle 9.4.0, ModDevGradle 2.0.148, Java 25 (`/usr/lib/jvm/java-25-openjdk-amd64`), NeoForge 26.1.2.126.
- `gradle.properties`: диапазоны точные — `minecolonies [1.1.1397-26.1.2,)`, `farmersdelight [1.3.4-2,)` (флотская ловушка 46: maven-range ≠ internal version).
- Зависимости (флотский паттерн `MineColonies_Compatibility`, `files()` без транзитивности POM):
  - `minecolonies` jar из mavenLocal (`com.ldtteam:minecolonies:1.1.1397-26.1.2`) — POM-транзитивность отключена намеренно (POM тянет `flat:journeymap-neoforge`, которого нет);
  - FD 1.3.4-2 — собранный jar нашего порта `worktrees/farmersdelight-26.1.2` (MIT; НЕ 1.3.3-fix7 из Compendium);
  - structurize/blockui — compileOnly (клиентские GUI-цели миксинов);
  - runtime-стек ранов: structurize, blockui, domum-ornamentum (`1.0.0-local-main`), **multipiston** (без него research-loader MineColonies падает `Unknown item: multipiston:multipistonblock` — чисто MineColonies-данные, не наша).
- `pack.mcmeta` не создаётся (флотский прецедент 26.1.2).
- `mixin.debug.export=true` в ранах — экспорт transformed-классов для проверки применения миксинов.

## 5. Смежные модули

- **Не объединяется** с Culinary/Colonial Compendium (GPL vs MIT) — отдельный модуль.
- Собственный FD 1.3.4-2 порт — единственная FD-зависимость; `MineColonies_Compatibility`'s
  FD 1.3.3-fix7 **не используется**.
- Совместим с флотской EMI-политикой: у модуля нет JEI/REI-кода (upstream имел только
  гаматесты и данные, ничего EMI-специфичного).

## 6. Непроверенное (честный список)

См. `QA_REPORT.md` § «Границы подтверждения»: живой ресторан/курьер в работающей колонии,
визуальный GUI-прогон клиентских миксинов, Rich Soil/рис-сценарии canSurvive в мире,
вентиляция тултипов в клиенте — серверный smoke этого не подтверждает.
