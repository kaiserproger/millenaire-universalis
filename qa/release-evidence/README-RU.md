# Release evidence: Millenaire Realm & Simulation

Текущий проверенный срез находится в `current/`.

## Что подтверждено физическим dedicated-run

Run `final9-20260805T071000Z` дважды запускает один и тот же NeoForge-мир с реальным Millenaire beta.2 и production JAR Millenaire Armies. В первом lifecycle обычный не-OP игрок получает подконтрольное поселение, ратушные ресурсы и поднимает три реальные сущности `MillVillager`. Проверяются физические `MOVE` и `RALLY`, создание армии второй культуры, двусторонние реальные цели, нанесение физического урона, `HOLD`, logistics, выгрузка и повторная загрузка чанка. Затем выполняются clean save/stop и второй запуск мира, где проверяются восстановление controller/membership/dimension/order, продолжение исполнения, отказ чужому игроку, принятие команды владельца и отклонение malformed payload.

Machine result: `current/full-cycle-result.json`. Все его проверки равны `true`, `failure=null`, fatal/error/crash markers отсутствуют.

## Что подтверждено детерминированными проверками

`current/readiness.json` разделяет физически проверенные пункты от механизмов, покрытых pure/self-test и интеграционными тестами: историческая Simulation без force-load, динамические цены, появление/упадок поселений и NPC-государств, формы правления, зависимости и tribute, secession, физическая осада/захват, защита блоков, upkeep, классы войск и феодальные лидеры/ополчения.

## Артефакты

- `full-cycle-result.json` — итог runner с единым набором release checks.
- `pre-restart-state.json` — assertions физической первой половины.
- `post-restart-state.json` — assertions после второго старта.
- `static-audit.json` — ресурсы, переводы, placeholders и fail-closed QA.
- `runtime-key-events.log` — сокращённый поток ключевых runtime events.
- `readiness.json` — честная матрица покрытия исходного gameplay-запроса.
- `SHA256SUMS` — хэши модулей и evidence.

Запрошенный handoff `gpt-5.3-codex-spark` был вызван через CodexPro handoff, но локальные Codex credits были исчерпаны до 8 августа 2026 года. Direct `codex exec` не использовался. Поэтому итоговая игровая валидация выполнена reproducible dedicated harness, а не агентом Spark.
