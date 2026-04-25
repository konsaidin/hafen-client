# Hafen Console Client — TODO

## Реализовано ✓

- GamepadManager (jinput, DualSense, volatile snapshot)
- DirectMovement: левый стик → клики wdgmsg("click"), угол камеры, деадзона, maxClickWU
- SmartTarget: конус + ближний радиус, приоритеты, боевой режим, гейз из последнего движения
- RadialPicker: R1 hold открывает, RS навигация, R1 release подтверждает, B отменяет
- Камера: L2+RS вращает (все типы камер), RS-click сбрасывает угол
- ABXY → hotbar слоты 1-4 / 5-8 (L2 модификатор)
- L1 → LMB эмуляция
- HUD бейджи: ⌖ GAMEPAD + ⚔ COMBAT
- Боевой режим: автодетект из Fightview.lsrel
- Индикатор цели: стрелка-указатель над гобом + название гоба рядом, 200 мс grace period
- FlowerMenu: RS выбор лепестка, D-pad навигация, R1/R2 подтвердить, B отменить
- InterfaceMenu (R2): радиальное меню Карта/Персонаж/Кин/Инвентарь/Экипировка, центрирование FlowerMenu-style
- D-pad приоритеты: FlowerMenu > InterfaceMenu > RadialPicker > FocusedWindow > MenuGrid
- MenuGrid: D-pad навигация (линейный порядок для ←→, по строкам для ↑↓), Select подтверждает, курсор сбрасывается при смене подкатегории
- Кнопочная схема: R1 tap=SmartClick/FlowerConfirm, R2=open InterfaceMenu/confirm menus, Select=Inventory/MenuGrid confirm, B=cancel/close, ABXY=hotbar
- Защита от двойного открытия FlowerMenu при подтверждении через R1 (r1PressedWithFlower)
- Окна (Window): автофокус на последнем открытом, D-pad навигирует инвентарь/скроллит, Select активирует слот, B сначала снимает выделение потом закрывает окно

---

## 1. Полировка

- [ ] Скрывать OS-курсор при активном левом стике
- [ ] Вкладка «Gamepad» в PreferencesWnd (деадзоны, чувствительность)
- [ ] Тест: мышь + стик одновременно без конфликтов
- [ ] Смена фокуса между несколькими открытыми инвентарями (сейчас фокус только на последнем открытом)
