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
