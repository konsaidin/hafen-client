# Hafen Console Client — TODO

## Реализовано ✓

- GamepadManager (jinput, DualSense, volatile snapshot)
- DirectMovement: левый стик → клики wdgmsg("click"), угол камеры, деадзона, maxClickWU
- SmartTarget: конус + ближний радиус, приоритеты, боевой режим, гейз из последнего движения
- RadialPicker: R1 hold открывает, RS навигация, R1 release подтверждает, B отменяет
- Камера: L2+RS вращает (все типы камер), RS-click сбрасывает угол
- ABXY → hotbar слоты 1-4 / 5-8 (L2 модификатор)
- L1 → LMB эмуляция
- HUD бейджи: ⌖ GAMEPAD + ⚔ COMBAT + RS/cam debug
- Боевой режим: автодетект из Fightview.lsrel
- Подсветка цели: голубое кольцо, 200 мс grace period
- FlowerMenu: RS выбор лепестка, D-pad навигация, R1/R2 подтвердить, B отменить
- InterfaceMenu (R2): радиальное меню Карта/Персонаж/Кин/Инвентарь/Экипировка, центрирование FlowerMenu-style
- D-pad приоритеты: FlowerMenu > InterfaceMenu > RadialPicker > MenuGrid
- MenuGrid: D-pad навигация (линейный порядок для ←→, по строкам для ↑↓), Select подтверждает, курсор сбрасывается при смене подкатегории
- Кнопочная схема: R1 tap=SmartClick/FlowerConfirm, R2=open InterfaceMenu/confirm menus, Select=MenuGrid confirm, B=cancel, ABXY=hotbar
- Защита от двойного открытия FlowerMenu при подтверждении через R1 (r1PressedWithFlower)

---

## 1. D-pad навигация инвентарём

D-pad ↑↓←→ → перемещение фокуса по слотам `Inventory`.
A → interact (правый клик по слоту), B → закрыть/снять фокус.
Смена фокуса между несколькими открытыми инвентарями.

---

## 2. Полировка

- [ ] Удалить RS/cam debug badge из HUD (GamepadDispatcher.drawHUDBadges ~96-111)
- [ ] Скрывать OS-курсор при активном левом стике
- [ ] Вкладка «Gamepad» в PreferencesWnd (деадзоны, чувствительность)
- [ ] Тест: мышь + стик одновременно без конфликтов
