# EnchantJewellery

EpicBot OSRS script for the P2P Sapphire ring enchant loop:

- Sapphire ring -> Ring of recoil

Current quarantine build: `v0.1.22-primitive-magic-click`.

This build intentionally removes the dynamic OSRS Wiki price selector, extra jewellery methods, watchdog, and file diagnostics while we isolate the client crash. It uses the older sapphire-only core flow:

- checks Sapphire ring profit using EpicBot client pricing;
- buys missing Sapphire rings and Cosmic runes through the Grand Exchange;
- prepares inventory from the bank;
- selects `Jewellery Enchantments` and `Lvl-1 Enchant` with slow primitive mouse clicks, avoiding `WidgetChild.interact`;
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
