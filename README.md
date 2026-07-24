# EnchantJewellery

EpicBot OSRS script for rotating profitable P2P jewellery enchants with OSRS Wiki Prices data:

- Sapphire ring -> Ring of recoil
- Sapphire necklace -> Games necklace(8)
- Opal bracelet -> Expeditious bracelet
- Opal necklace -> Dodgy necklace
- Emerald ring -> Ring of dueling(8)

The script checks current GE margins, prepares inventory/bank supplies, buys missing jewellery and Cosmic runes through the Grand Exchange, sells enchanted outputs, and paints runtime/profit status.

## Dynamic Pricing

The selector uses the OSRS Wiki Prices API endpoints:

- `https://prices.runescape.wiki/api/v1/osrs/latest`
- `https://prices.runescape.wiki/api/v1/osrs/5m`
- `https://prices.runescape.wiki/api/v1/osrs/1h`

It calculates conservative profit using quick-buy input/cosmic prices, quick-sell output prices, and GE tax. Methods are filtered by minimum profit and 1h liquidity. If the Wiki API is unavailable, only the stable Sapphire ring method can fall back to EpicBot client pricing.

## Build

```powershell
.\gradlew.bat :enchant-jewellery:build
```

The compiled jar is generated under:

```text
enchant-jewellery/build/libs/enchant-jewellery.jar
```
