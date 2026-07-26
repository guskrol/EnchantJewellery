# EnchantJewellery

EpicBot OSRS script for profitable P2P jewellery enchants.

Current stability build: the OSRS Wiki Prices selector is disabled by default, so the script only runs the stable Sapphire ring fallback path:

- Sapphire ring -> Ring of recoil

The dynamic method list is still present in the code behind `ENABLE_WIKI_PRICE_API`, but these methods are not eligible while that flag is disabled:

- Sapphire necklace -> Games necklace(8)
- Opal bracelet -> Expeditious bracelet
- Opal necklace -> Dodgy necklace
- Emerald ring -> Ring of dueling(8)

The script prepares inventory/bank supplies, buys missing Sapphire rings and Cosmic runes through the Grand Exchange, sells enchanted outputs, and paints runtime/profit status.

## Dynamic Pricing

In `v0.2.4-safe-enchant-click`, live Wiki API calls are disabled by default to isolate EpicBot client stability issues. With `ENABLE_WIKI_PRICE_API = false`, only methods with a client-price fallback are considered, currently Sapphire rings.

When re-enabled, the selector uses the OSRS Wiki Prices API endpoints:

- `https://prices.runescape.wiki/api/v1/osrs/latest`
- `https://prices.runescape.wiki/api/v1/osrs/5m`
- `https://prices.runescape.wiki/api/v1/osrs/1h`

It calculates conservative profit using quick-buy input/cosmic prices, quick-sell output prices, and GE tax. Methods are filtered by minimum profit and 1h liquidity. If the Wiki API is unavailable, only the stable Sapphire ring method can fall back to EpicBot client pricing.

## Diagnostics

If the script catches a runtime error, it writes a diagnostic file under the EpicBot working directory:

```text
enchant-jewellery-crashes/enchant-jewellery-crash-YYYYMMDD-HHMMSS.txt
```

## Build

```powershell
.\gradlew.bat :enchant-jewellery:build
```

The compiled jar is generated under:

```text
enchant-jewellery/build/libs/enchant-jewellery.jar
```
