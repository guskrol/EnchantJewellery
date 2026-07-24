# EnchantJewellery

EpicBot OSRS script for rotating profitable P2P jewellery enchants:

- Sapphire ring -> Ring of recoil
- Jade amulet -> Amulet of chemistry
- Topaz bracelet -> Bracelet of slaughter

The script selects between available methods with weighted randomness, checks current GE margins, prepares inventory/bank supplies, buys missing supplies through the Grand Exchange, sells output jewellery, and paints runtime/profit status.

## Build

```powershell
.\gradlew.bat :enchant-jewellery:build
```

The compiled jar is generated under:

```text
enchant-jewellery/build/libs/enchant-jewellery.jar
```

## Diagnostics

Watchdog reports are created only when the script watchdog detects inactivity or a loop and stops/logs out the account:

```text
watchdog-reports/enchant-jewellery-watchdog-YYYYMMDD-HHmmss.txt
```

If the EpicBot client or injection layer crashes before the watchdog runs, there will be no watchdog report. For that case, every script run also writes a live diagnostic log while it is running:

```text
live-logs/enchant-jewellery-live-YYYYMMDD-HHmmss.txt
```

If the script cannot write in the current working directory, it falls back to:

```text
%USERPROFILE%\enchant-jewellery-live-logs\
%USERPROFILE%\enchant-jewellery-watchdog-reports\
```
