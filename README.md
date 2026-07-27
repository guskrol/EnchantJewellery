# EnchantJewellery

EpicBot OSRS script for P2P jewellery enchant profit loops.

Current enabled methods:

- Opal bracelet -> Expeditious bracelet
- Opal necklace -> Dodgy necklace
- Sapphire ring -> Ring of recoil
- Sapphire necklace -> Games necklace(8)
- Emerald ring -> Ring of dueling(8)
- Jade necklace -> Necklace of passage(5)

Stable tag: `stable-v0.1.26-panel-relative-spellbook-clicks`.

Current build: `v0.1.32-strict-spell-confirmation`.

This build keeps the stable casting flow isolated while adding conservative Lvl-1/Lvl-2 method selection:

- checks method profit using EpicBot client pricing;
- filters by Magic level and per-method minimum profit;
- favors more liquid methods with higher selector weights;
- uses smaller restock batches for lower-liquidity outputs;
- sells produced output at the end of each rotation before selecting or preparing the next method;
- places output sell offers with aggressive instant-sell pricing;
- buys missing jewellery and Cosmic runes through the Grand Exchange;
- prepares inventory from the bank;
- uses the live side-panel bounds for `218.15`, `218.16`, and `218.11`, only translating widget coordinates when they are outside that panel;
- clicks the jewellery material once to process the full inventory;
- banks/sells produced outputs.

`v0.1.27` keeps the stable casting flow isolated and adds one rule for the future dynamic selector: whenever the script switches to another enchant method, it must sell the previous method's produced output before preparing or buying the next item.

`v0.1.28` adds the next price-check countdown to the in-game overlay.

`v0.1.29` enables selected Lvl-1 and Lvl-2 market-friendly methods and refreshes method selection every 20-30 minutes.

`v0.1.30` forces a full output sale when each rotation ends, using aggressive Grand Exchange sell pricing before the next method is prepared.

`v0.1.31` was reverted because trusting a spell-widget click without API confirmation could cast the wrong spell.

`v0.1.32` restores strict spell confirmation: the script only clicks jewellery material when the client confirms a spell is selected, and it pauses immediately on wrong-spell chat.

Runtime decisions are logged to the EpicBot client log with the `[Trace]` prefix.

## Build

```powershell
.\gradlew.bat :enchant-jewellery:build
```

The compiled jar is generated under:

```text
enchant-jewellery/build/libs/enchant-jewellery.jar
```
