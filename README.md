# EnchantJewellery

EpicBot OSRS script for the P2P Sapphire ring enchant loop:

- Sapphire ring -> Ring of recoil

Current quarantine build: `v0.1.25-resizable-lvl1-fallback`.

This build intentionally removes the dynamic OSRS Wiki price selector, extra jewellery methods, watchdog, and file diagnostics while we isolate the client crash. It uses the older sapphire-only core flow:

- checks Sapphire ring profit using EpicBot client pricing;
- buys missing Sapphire rings and Cosmic runes through the Grand Exchange;
- prepares inventory from the bank;
- adds a resizeable-client fallback click for the visible `Lvl-1 Enchant` icon when widget bounds do not select the spell;
- clicks the Sapphire ring once to process the full inventory;
- banks/sells Ring of recoil outputs.

Runtime decisions are logged to the EpicBot client log with the `[Trace]` prefix.

## Build

```powershell
.\gradlew.bat :enchant-jewellery:build
```

The compiled jar is generated under:

```text
enchant-jewellery/build/libs/enchant-jewellery.jar
```
