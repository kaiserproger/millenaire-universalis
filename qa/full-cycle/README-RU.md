# Dedicated QA полного цикла Millenaire Armies

Один запуск проверяет реальный NeoForge dedicated server, настоящее поселение и жителей
Millenaire, Carpet fake players и production-команды аддона:

```bash
army-full-cycle-qa/bin/run.sh rc-smoke
```

Если complete runtime находится не в основном worktree:

```bash
BANNEROK_QA_RUNTIME_ROOT=/path/to/stopped/server army-full-cycle-qa/bin/run.sh rc-smoke
```

Сценарий изолирован в `army-full-cycle-qa/runs/<id>` и fail-closed: одновременно требуются
JVM-флаг `millenairearmies.qa.enabled`, флаг stress harness и sandbox marker. Одной командой он
создаёт player-controlled поселение Millenaire, назначает его владельцем обычного (не OP)
Carpet-игрока через публичный API Village, пополняет настоящий сундук ратуши изумрудами и
вызывает только release-gameplay путь `millarmies raise 3`. Успех не использует legacy
`create`, сырой UUID-recruit или временную выдачу OP.

После формирования сценарий проверяет минимум три membership и сохранённый stable dimension
армии (`minecraft:overworld`), выполняет move/rally/hold/logistics, требует физического движения,
unload/reload чанка, затем clean save, stop и второй запуск того же мира. После рестарта снова
проверяются dimension/membership/order, продолжение исполнения, запрет чужому игроку и
отклонение malformed payload.

Результаты:

- `result.json` — machine-readable итог runner;
- `server/army-full-cycle-result.json` — итог in-server state machine;
- `artifacts/pre-restart-state.json` — assertions первой половины до clean restart;
- `artifacts/server-console.log` — оба lifecycle в одном логе;
- `artifacts/static.json` — JSON/resources/translations/placeholder audit.

Любой `ERROR`, `FATAL`, `NullPointerException`, server-tick exception или crash marker проваливает
приёмку, даже если gameplay assertions успели пройти.
