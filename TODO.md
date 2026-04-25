# Hafen Console Client — TODO

## Реализовано ✓

- GamepadManager (jinput, DualSense positional button mapping, volatile snapshot)
- DirectMovement: левый стик → клики wdgmsg("click"), угол камеры, деадзона
- SmartTarget: конус, приоритеты, боевой режим, гейз из последнего движения
- RadialPicker: открывается по R1 hold, навигация RS, подтверждение R1 release / A
- Камера: L2+RS вращает (gpRotate реализован во всех типах камер: SOrthoCam, SimpleCam, FreeCam, OrthoCam), RS-click сбрасывает угол
- ABXY → hotbar слоты 1-4 / 5-8 (L2 модификатор)
- L1 → LMB эмуляция
- HUD: бейдж ⌖ GAMEPAD + ⚔ COMBAT + RS/cam debug
- Боевой режим: автодетект из Fightview.lsrel
- Подсветка цели: голубое кольцо, 200 мс grace period
- Предотвращение выхода за цель (maxClickWU)
- FlowerMenu (серверное контекстное меню): RS выбор лепестка, A/R1 подтвердить, B отменить

---

## 1. Меню интерфейса (Options/Start) — следующее

Кнопка Options → радиальное меню: Персонаж / Экипировка / Навыки / Крафт / Карта / Кин.
Открывает соответствующие виджеты `CharWnd`, `Equipory`, `MapWnd` и т.д. через `gui.*`.

Новый файл: `src/haven/gamepad/InterfaceMenu.java` (extends RadialPicker или отдельный Widget).

---

## 2. D-pad навигация инвентарём

D-pad ↑↓←→ → перемещение фокуса по слотам `Inventory`.
A → interact (правый клик по слоту), B → закрыть/снять фокус.
Смена фокуса между несколькими открытыми инвентарями — кнопка (LS нажатие или Select).

---

## 3. Полировка

- [ ] Удалить RS/cam debug badge из HUD (строки ~96-111 в GamepadDispatcher.drawHUDBadges)
- [ ] Скрывать OS-курсор при активном левом стике
- [ ] Вкладка «Gamepad» в PreferencesWnd (деадзоны, чувствительность)
- [ ] Тест: мышь + стик одновременно без конфликтов
