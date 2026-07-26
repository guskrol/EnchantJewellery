# EnchantJewellery

EpicBot OSRS script for the P2P Sapphire ring enchant loop:

- Sapphire ring -> Ring of recoil

Stable tag: `stable-v0.1.26-panel-relative-spellbook-clicks`.

Current build: `v0.1.27-sell-output-on-method-switch`.

This build intentionally removes the dynamic OSRS Wiki price selector, extra jewellery methods, watchdog, and file diagnostics while we isolate the client crash. It uses the older sapphire-only core flow:

- checks Sapphire ring profit using EpicBot client pricing;
- buys missing Sapphire rings and Cosmic runes through the Grand Exchange;
- prepares inventory from the bank;
- uses the live side-panel bounds for `218.15` and `218.16`, only translating widget coordinates when they are outside that panel;
- clicks the Sapphire ring once to process the full inventory;
- banks/sells Ring of recoil outputs.

`v0.1.27` keeps the stable casting flow isolated and adds one rule for the future dynamic selector: whenever the script switches to another enchant method, it must sell the previous method's produced output before preparing or buying the next item.

Runtime decisions are logged to the EpicBot client log with the `[Trace]` prefix.

## Build

```powershell
.\gradlew.bat :enchant-jewellery:build
```

The compiled jar is generated under:

```text
enchant-jewellery/build/libs/enchant-jewellery.jar
```
