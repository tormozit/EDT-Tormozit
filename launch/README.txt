ВОССТАНОВЛЕНИЕ (EDT не стартует, нерешённые бандлы)
====================================================
  powershell -File "C:\VC\EDT.Comfort\launch\restore-main.ps1"

Скрипт останавливает eclipse.exe, копирует OSGi из backup, правит launch.

КРИТИЧНО в Run Configurations → Eclipse Application:
  [ ] Clear configuration before launching  (clearConfig)
  [ ] Generate OSGi profile                 (generateProfile)

Обе галочки должны быть СНЯТЫ. Иначе PDE пересобирает bundles.info
(249 КБ вместо 262 КБ) и EDT падает на jdt.core.compiler.batch.

После restore-main bundles.info и config.ini — read-only (защита от перезаписи).
