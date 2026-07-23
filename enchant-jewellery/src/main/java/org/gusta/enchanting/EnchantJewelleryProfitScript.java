package org.gusta.enchanting;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.ItemDetail;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Spell;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.script.task.ScriptTask;
import com.epicbot.api.shared.util.paint.PaintContext;
import com.epicbot.api.shared.util.time.Time;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

@ScriptManifest(name = "Enchant Jewellery Profit", gameType = GameType.OS)
public class EnchantJewelleryProfitScript extends Script {
    private static final String SCRIPT_VERSION = "v0.1.0-random-enchants";
    private static final Tile GRAND_EXCHANGE_TILE = new Tile(3164, 3487, 0);
    private static final int GE_MIN_X = 3150;
    private static final int GE_MAX_X = 3190;
    private static final int GE_MIN_Y = 3465;
    private static final int GE_MAX_Y = 3505;
    private static final int INVENTORY_INPUT_AMOUNT = 27;
    private static final int MIN_OUTPUTS_TO_SELL = 350;
    private static final int MIN_COINS_RESERVE = 5_000;
    private static final int MIN_PROFIT_PER_CAST = 25;
    private static final int MIN_BATCH_CASTS = 160;
    private static final int MAX_BATCH_CASTS = 420;
    private static final int RESTOCK_MIN_CASTS = 540;
    private static final int RESTOCK_MAX_CASTS = 1080;
    private static final int SPELLBOOK_GROUP = 218;
    private static final int JEWELLERY_ENCHANTMENTS_CHILD = 15;
    private static final int LEVEL_1_ENCHANT_CHILD = 16;
    private static final int LEVEL_2_ENCHANT_CHILD = 11;
    private static final int LEVEL_3_ENCHANT_CHILD = 13;
    private static final int LEVEL_4_ENCHANT_CHILD = 14;
    private static final long METHOD_REFRESH_MS = 4 * 60_000L;
    private static final double BUY_MARKUP = 1.10D;
    private static final double SELL_MARKDOWN = 0.98D;
    private static final double GE_TAX_RATE = 0.02D;
    private static final String COINS = "Coins";
    private static final String COSMIC_RUNE = "Cosmic rune";

    private static final EnchantMethod[] METHODS = {
            new EnchantMethod(
                    "sapphire_rings",
                    "Sapphire rings",
                    7,
                    Spell.Modern.LEVEL_1_ENCHANT,
                    LEVEL_1_ENCHANT_CHILD,
                    "Staff of water",
                    "Sapphire ring",
                    "Ring of recoil",
                    377,
                    830,
                    35,
                    5
            ),
            new EnchantMethod(
                    "jade_amulets",
                    "Jade amulets",
                    27,
                    Spell.Modern.LEVEL_2_ENCHANT,
                    LEVEL_2_ENCHANT_CHILD,
                    "Staff of air",
                    "Jade amulet",
                    "Amulet of chemistry",
                    953,
                    1735,
                    80,
                    7
            ),
            new EnchantMethod(
                    "topaz_bracelets",
                    "Topaz bracelets",
                    49,
                    Spell.Modern.LEVEL_3_ENCHANT,
                    LEVEL_3_ENCHANT_CHILD,
                    "Staff of fire",
                    "Topaz bracelet",
                    "Bracelet of slaughter",
                    2771,
                    3319,
                    100,
                    4
            )
    };

    private final Queue<GeAction> pendingGeActions = new ArrayDeque<>();
    private final List<GeAction> placedGeActions = new ArrayList<>();
    private final Pricing pricing = new Pricing();

    private Stats stats;
    private EnchantMethod activeMethod;
    private EnchantMethod previousMethod;
    private Quote activeQuote;
    private int activeBatchTargetCasts;
    private int activeBatchCasts;
    private long nextMethodRefreshAt;
    private long nextGeCollectAt;
    private long nextIdleLogAt;
    private boolean stoppedForNoProfit;

    @Override
    public boolean onStart(String... args) {
        stats = new Stats();
        addTask(new EnchantTask());
        log("Enchant Jewellery Profit " + SCRIPT_VERSION + " started");
        return true;
    }

    @Override
    protected void onChatMessage(ChatMessageEvent event) {
        if (event == null || event.getMessage() == null || stats == null) {
            return;
        }
        String message = event.getMessage();
        stats.lastChat = message;
        String lower = message.toLowerCase();
        if (lower.contains("you do not have enough")
                || lower.contains("not enough")
                || lower.contains("you can't")
                || lower.contains("nothing interesting happens")) {
            log("Game message: " + message);
        }
    }

    @Override
    protected void onPaint(PaintContext paint, APIContext ctx) {
        if (paint == null || stats == null) {
            return;
        }
        stats.startExperienceIfNeeded(ctx);

        int x = 8;
        int y = 8;
        int width = 330;
        int height = 228;
        paint.fill(new Rectangle(x, y, width, height), new Color(18, 22, 28, 190));
        paint.draw(new Rectangle(x, y, width, height), new Color(230, 235, 245, 210), 1);

        int line = y + 20;
        paint.drawText("Enchant Jewellery " + SCRIPT_VERSION, x + 12, line, Color.WHITE, 14);
        line += 18;
        paint.drawText("Runtime: " + stats.runtimeText(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Status: " + shortText(stats.status, 42), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("Method: " + (activeMethod == null ? "-" : activeMethod.label), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Magic: " + magicLevel(ctx) + " | XP: " + stats.xpGained(ctx)
                + " (" + stats.xpPerHour(ctx) + "/h)", x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Profit/cast: " + (activeQuote == null ? "-" : activeQuote.profitPerCast + " gp")
                + " | est/h: " + (activeQuote == null ? "-" : activeQuote.profitPerHour + " gp"),
                x + 12, line, new Color(245, 228, 160), 12);
        line += 16;
        paint.drawText("Casts: " + stats.casts + " | batch " + activeBatchCasts + "/" + activeBatchTargetCasts
                + " | GE " + pendingGeActions.size() + "/" + placedGeActions.size(),
                x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Last GE: " + shortText(stats.lastGeAction, 43), x + 12, line, new Color(245, 228, 160), 11);
        line += 16;
        paint.drawText("Last chat: " + shortText(stats.lastChat, 43), x + 12, line, new Color(195, 210, 230), 11);
    }

    @Override
    protected void onStop() {
        clearClientInteractionState();
        getLogger().info("Enchant Jewellery Profit " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        clearClientInteractionState();
    }

    private class EnchantTask implements ScriptTask {
        @Override
        public boolean shouldExecute() {
            return true;
        }

        @Override
        public void run() {
            APIContext ctx = getAPIContext();
            if (ctx == null) {
                Time.sleep(600, 900);
                return;
            }

            stats.startExperienceIfNeeded(ctx);

            if (!pendingGeActions.isEmpty() || !placedGeActions.isEmpty()) {
                handleGrandExchange(ctx);
                return;
            }

            if (ctx.grandExchange().isOpen()) {
                stats.setStatus("Closing GE before enchanting");
                ctx.grandExchange().close();
                Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
                return;
            }

            if (!selectMethod(ctx)) {
                return;
            }

            if (hasEnchantInventory(ctx, activeMethod)) {
                enchantInventory(ctx, activeMethod);
                return;
            }

            prepareInventoryOrRestock(ctx, activeMethod);
        }
    }

    private boolean selectMethod(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (activeMethod != null
                && activeQuote != null
                && now < nextMethodRefreshAt
                && magicLevel(ctx) >= activeMethod.requiredMagic
                && activeQuote.profitPerCast >= activeMethod.minProfit
                && !finishedActiveBatch(ctx)) {
            return true;
        }

        List<Quote> quotes = viableQuotes(ctx);
        if (quotes.isEmpty()) {
            stoppedForNoProfit = true;
            activeMethod = null;
            activeQuote = null;
            stats.setStatus("No profitable enchant method found");
            logOccasionally("No profitable jewellery enchant available. Magic=" + magicLevel(ctx)
                    + " margins=" + marginSummary(ctx));
            Time.sleep(2500, 4000);
            nextMethodRefreshAt = now + METHOD_REFRESH_MS;
            return false;
        }

        stoppedForNoProfit = false;
        Quote selected = pickWeightedQuote(ctx, quotes);
        previousMethod = activeMethod;
        activeMethod = selected.method;
        activeQuote = selected;
        activeBatchTargetCasts = ThreadLocalRandom.current().nextInt(MIN_BATCH_CASTS, MAX_BATCH_CASTS + 1);
        activeBatchCasts = 0;
        nextMethodRefreshAt = now + METHOD_REFRESH_MS;

        log("Selected enchant: " + activeMethod.label
                + " profit/cast=" + activeQuote.profitPerCast
                + " target~" + activeBatchTargetCasts);
        return true;
    }

    private boolean finishedActiveBatch(APIContext ctx) {
        return activeBatchTargetCasts > 0
                && activeBatchCasts >= activeBatchTargetCasts
                && activeMethod != null
                && ctx.inventory().getCount(activeMethod.inputItem) == 0;
    }

    private List<Quote> viableQuotes(APIContext ctx) {
        List<Quote> quotes = new ArrayList<>();
        int level = magicLevel(ctx);
        for (EnchantMethod method : METHODS) {
            if (level < method.requiredMagic) {
                continue;
            }
            Quote quote = pricing.quote(ctx, method);
            if (quote.hasPrices()
                    && quote.profitPerCast >= Math.max(MIN_PROFIT_PER_CAST, method.minProfit)) {
                quotes.add(quote);
            }
        }
        quotes.sort(Comparator.comparingLong((Quote quote) -> quote.profitPerHour).reversed());
        return quotes;
    }

    private Quote pickWeightedQuote(APIContext ctx, List<Quote> quotes) {
        int totalWeight = 0;
        for (Quote quote : quotes) {
            totalWeight += quoteWeight(quote);
        }

        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, totalWeight));
        int cursor = 0;
        for (Quote quote : quotes) {
            cursor += quoteWeight(quote);
            if (roll < cursor) {
                return quote;
            }
        }
        return quotes.get(0);
    }

    private int quoteWeight(Quote quote) {
        int weight = quote.method.baseWeight + Math.max(0, (int) quote.profitPerCast / 150);
        if (previousMethod != null && previousMethod.key.equals(quote.method.key)) {
            weight = Math.max(1, weight / 3);
        }
        return Math.max(1, weight);
    }

    private void prepareInventoryOrRestock(APIContext ctx, EnchantMethod method) {
        if (!openBank(ctx, "preparing " + method.label)) {
            return;
        }

        if (depositInventoryIfNeeded(ctx, method)) {
            return;
        }

        if (shouldSellOutput(ctx, method)) {
            prepareOutputSale(ctx, method);
            return;
        }

        if (prepareEnchantInventoryFromBank(ctx, method)) {
            return;
        }

        planRestock(ctx, method);
    }

    private boolean depositInventoryIfNeeded(APIContext ctx, EnchantMethod method) {
        if (ctx.inventory().isEmpty()) {
            return false;
        }

        if (inventoryReadyForEnchant(ctx, method)) {
            return false;
        }

        if (ctx.inventory().contains(method.outputItem)) {
            stats.setStatus("Depositing enchanted jewellery");
            ctx.bank().depositAllExcept(COINS, COSMIC_RUNE, method.staff);
            Time.sleep(650, 1000);
            return true;
        }

        if (inventoryOnlyContains(ctx, COINS, COSMIC_RUNE, method.staff, method.inputItem)) {
            return false;
        }

        stats.setStatus("Depositing extra items");
        ctx.bank().depositAllExcept(COINS, COSMIC_RUNE, method.staff);
        Time.sleep(650, 1000);
        return true;
    }

    private boolean prepareEnchantInventoryFromBank(APIContext ctx, EnchantMethod method) {
        if (!ensureStaff(ctx, method)) {
            return true;
        }

        int bankInputs = ctx.bank().getCount(method.inputItem);
        int bankCosmics = ctx.bank().getCount(COSMIC_RUNE);
        int invInputs = ctx.inventory().getCount(method.inputItem);
        int invCosmics = ctx.inventory().getCount(true, COSMIC_RUNE);
        int availableCasts = Math.min(bankInputs + invInputs, bankCosmics + invCosmics);

        if (availableCasts <= 0) {
            return false;
        }

        if (!inventoryOnlyContains(ctx, COINS, COSMIC_RUNE, method.staff, method.inputItem)) {
            stats.setStatus("Clearing inventory for " + method.label);
            ctx.bank().depositAllExcept(COINS, COSMIC_RUNE, method.staff);
            Time.sleep(650, 1000);
            return true;
        }

        if (invInputs > INVENTORY_INPUT_AMOUNT) {
            stats.setStatus("Normalising enchant inventory");
            ctx.bank().depositAll(method.inputItem);
            Time.sleep(600, 900);
            return true;
        }

        ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
        if (invCosmics <= 0) {
            stats.setStatus("Withdrawing cosmic runes");
            ctx.bank().withdrawAll(COSMIC_RUNE);
            Time.sleep(600, 900);
            return true;
        }

        if (invInputs <= 0) {
            int amount = Math.min(INVENTORY_INPUT_AMOUNT, bankInputs);
            stats.setStatus("Withdrawing " + amount + "x " + method.inputItem);
            if (amount >= bankInputs) {
                ctx.bank().withdrawAll(method.inputItem);
            } else {
                ctx.bank().withdraw(amount, method.inputItem);
            }
            Time.sleep(600, 900);
            return true;
        }

        closeBank(ctx, "Ready to enchant " + method.label);
        return true;
    }

    private boolean ensureStaff(APIContext ctx, EnchantMethod method) {
        if (ctx.equipment().contains(method.staff)) {
            return true;
        }

        if (ctx.inventory().contains(method.staff)) {
            stats.setStatus("Equipping " + method.staff);
            ctx.inventory().interactItem("Wield", method.staff);
            Time.sleep(600, 1000, () -> ctx.equipment().contains(method.staff), 100);
            return false;
        }

        if (ctx.bank().contains(method.staff) || ctx.bank().getItem(method.staff) != null) {
            stats.setStatus("Withdrawing " + method.staff);
            ctx.bank().withdraw(1, method.staff);
            Time.sleep(600, 900);
            return false;
        }

        queueSupplyBuy(ctx, method.staff, 1, pricing.quickBuyPrice(ctx, method.staff, 1500L));
        closeBank(ctx, "Going to GE for " + method.staff);
        return false;
    }

    private void planRestock(APIContext ctx, EnchantMethod method) {
        if (activeQuote == null || !activeQuote.profitable()) {
            activeMethod = null;
            activeQuote = null;
            nextMethodRefreshAt = 0L;
            stats.setStatus("Enchant method no longer profitable; refreshing selection");
            return;
        }

        if (shouldSellOutput(ctx, method)) {
            prepareOutputSale(ctx, method);
            return;
        }

        int targetCasts = ThreadLocalRandom.current().nextInt(RESTOCK_MIN_CASTS, RESTOCK_MAX_CASTS + 1);
        int inputsAvailable = ctx.inventory().getCount(method.inputItem) + ctx.bank().getCount(method.inputItem);
        int cosmicsAvailable = ctx.inventory().getCount(true, COSMIC_RUNE) + ctx.bank().getCount(COSMIC_RUNE);
        int inputsToBuy = Math.max(0, targetCasts - inputsAvailable);
        int cosmicsToBuy = Math.max(0, targetCasts - cosmicsAvailable);

        long cost = (long) inputsToBuy * activeQuote.inputBuyPrice
                + (long) cosmicsToBuy * activeQuote.cosmicBuyPrice;
        long coins = ctx.inventory().getCount(true, COINS) + ctx.bank().getCount(COINS);
        long availableCoins = Math.max(0L, coins - MIN_COINS_RESERVE);
        while (cost > availableCoins && targetCasts > INVENTORY_INPUT_AMOUNT) {
            targetCasts = Math.max(INVENTORY_INPUT_AMOUNT, (int) Math.floor(targetCasts * 0.8D));
            inputsToBuy = Math.max(0, targetCasts - inputsAvailable);
            cosmicsToBuy = Math.max(0, targetCasts - cosmicsAvailable);
            cost = (long) inputsToBuy * activeQuote.inputBuyPrice
                    + (long) cosmicsToBuy * activeQuote.cosmicBuyPrice;
        }

        if (inputsToBuy <= 0 && cosmicsToBuy <= 0) {
            stats.setStatus("Waiting for bank/inventory state to update");
            Time.sleep(900, 1400);
            return;
        }

        if (cost > availableCoins) {
            stats.setStatus("Not enough coins to restock " + method.label);
            logOccasionally("Not enough coins to restock " + method.label
                    + ". Coins=" + coins + " cost=" + cost);
            Time.sleep(1800, 2800);
            return;
        }

        withdrawCoinsForGe(ctx, cost);
        if (inputsToBuy > 0) {
            queueSupplyBuy(ctx, method.inputItem, inputsToBuy, pricing.quickBuyPrice(ctx, method.inputItem, activeQuote.inputBuyPrice));
        }
        if (cosmicsToBuy > 0) {
            queueSupplyBuy(ctx, COSMIC_RUNE, cosmicsToBuy, pricing.quickBuyPrice(ctx, COSMIC_RUNE, activeQuote.cosmicBuyPrice));
        }
        closeBank(ctx, "Going to GE for " + method.label + " restock");
    }

    private void queueSupplyBuy(APIContext ctx, String itemName, int quantity, int price) {
        if (quantity <= 0) {
            return;
        }
        pendingGeActions.add(GeAction.buy(itemName, quantity, price));
        stats.lastGeAction = "Queued buy " + quantity + "x " + itemName + " @ " + price;
        log(stats.lastGeAction);
    }

    private boolean shouldSellOutput(APIContext ctx, EnchantMethod method) {
        int inventoryOutput = ctx.inventory().getCount(true, method.outputItem);
        int bankOutput = ctx.bank().isOpen() ? ctx.bank().getCount(method.outputItem) : 0;
        int totalOutput = inventoryOutput + bankOutput;
        if (totalOutput <= 0) {
            return false;
        }

        boolean hasMaterials = ctx.inventory().getCount(method.inputItem) > 0
                || (ctx.bank().isOpen() && ctx.bank().getCount(method.inputItem) > 0);
        int coins = ctx.inventory().getCount(true, COINS)
                + (ctx.bank().isOpen() ? ctx.bank().getCount(COINS) : 0);
        return totalOutput >= MIN_OUTPUTS_TO_SELL || (!hasMaterials && coins < MIN_COINS_RESERVE);
    }

    private void prepareOutputSale(APIContext ctx, EnchantMethod method) {
        int inventoryOutput = ctx.inventory().getCount(true, method.outputItem);
        if (inventoryOutput <= 0) {
            stats.setStatus("Withdrawing " + method.outputItem + " as notes to sell");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            if (ctx.bank().withdrawAll(method.outputItem)) {
                Time.sleep(700, 1100, () -> ctx.inventory().contains(method.outputItem), 100);
            }
            inventoryOutput = ctx.inventory().getCount(true, method.outputItem);
        }

        if (inventoryOutput <= 0) {
            stats.setStatus("Wanted to sell output, but no " + method.outputItem + " was found");
            return;
        }

        int sellPrice = activeQuote == null
                ? pricing.quickSellPrice(ctx, method.outputItem, method.fallbackOutputSell)
                : pricing.quickSellPrice(ctx, method.outputItem, activeQuote.outputSellPrice);
        pendingGeActions.add(GeAction.sell(method.outputItem, inventoryOutput, sellPrice));
        stats.lastGeAction = "Queued sale " + inventoryOutput + "x " + method.outputItem + " @ " + sellPrice;
        log(stats.lastGeAction);
        closeBank(ctx, "Going to GE to sell " + method.outputItem);
    }

    private boolean hasEnchantInventory(APIContext ctx, EnchantMethod method) {
        return inventoryReadyForEnchant(ctx, method) && !ctx.bank().isOpen();
    }

    private boolean inventoryReadyForEnchant(APIContext ctx, EnchantMethod method) {
        return ctx.inventory().getCount(method.inputItem) > 0
                && ctx.inventory().getCount(true, COSMIC_RUNE) > 0
                && ctx.equipment().contains(method.staff);
    }

    private void enchantInventory(APIContext ctx, EnchantMethod method) {
        if (ctx.bank().isOpen()) {
            closeBank(ctx, "Ready to enchant " + method.label);
            return;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus(method.label + " enchanting in progress");
            Time.sleep(500, 800);
            return;
        }

        if (ctx.magic().getCurrentSpellBook() != Spell.Book.MODERN) {
            stats.setStatus("Standard spellbook required");
            logOccasionally("Cannot enchant jewellery outside the standard spellbook.");
            Time.sleep(1800, 2800);
            return;
        }

        if (!ctx.magic().canCast(method.spell)) {
            stats.setStatus("Cannot cast " + method.spell.getSpellName() + "; refreshing setup");
            activeMethod = null;
            activeQuote = null;
            Time.sleep(900, 1400);
            return;
        }

        int beforeInput = ctx.inventory().getCount(method.inputItem);
        int beforeOutput = ctx.inventory().getCount(method.outputItem);
        stats.setStatus("Enchanting " + method.inputItem + " -> " + method.outputItem);
        boolean cast = selectSpellAndClickItem(ctx, method);
        if (!cast) {
            cast = ctx.magic().cast(method.spell, method.inputItem);
        }

        Time.sleep(
                700,
                1300,
                () -> ctx.inventory().getCount(method.inputItem) < beforeInput
                        || ctx.inventory().getCount(method.outputItem) > beforeOutput
                        || ctx.localPlayer().isAnimating(),
                100
        );

        int converted = Math.max(
                beforeInput - ctx.inventory().getCount(method.inputItem),
                ctx.inventory().getCount(method.outputItem) - beforeOutput
        );
        if (converted > 0) {
            stats.casts += converted;
            activeBatchCasts += converted;
            return;
        }

        if (!cast) {
            stats.setStatus("Enchant cast did not start for " + method.label);
        }
        Time.sleep(600, 900);
    }

    private boolean selectSpellAndClickItem(APIContext ctx, EnchantMethod method) {
        if (!ctx.magic().isSpellSelected()) {
            if (!selectJewelleryEnchantSpell(ctx, method)) {
                return false;
            }
            Time.sleep(400, 700, () -> ctx.magic().isSpellSelected(), 100);
        }

        if (!openInventoryTab(ctx)) {
            return false;
        }

        ItemWidget item = ctx.inventory().getItem(method.inputItem);
        if (item == null) {
            return false;
        }
        return item.click(false)
                || ctx.menu().interact("Cast", item, false)
                || ctx.menu().interact("Cast", method.inputItem, item, false);
    }

    private boolean selectJewelleryEnchantSpell(APIContext ctx, EnchantMethod method) {
        if (!openMagicTab(ctx)) {
            return false;
        }

        if (!isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild))) {
            WidgetChild jewelleryEnchantments = ctx.widgets().get(SPELLBOOK_GROUP, JEWELLERY_ENCHANTMENTS_CHILD);
            if (!isVisibleWidget(jewelleryEnchantments)) {
                stats.setStatus("Jewellery Enchantments widget missing: 218." + JEWELLERY_ENCHANTMENTS_CHILD);
                return false;
            }

            stats.setStatus("Opening Jewellery Enchantments");
            clickWidgetCenter(ctx, jewelleryEnchantments);
            Time.sleep(
                    450,
                    750,
                    () -> isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild)),
                    100
            );
        }

        WidgetChild spellWidget = ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild);
        if (!isVisibleWidget(spellWidget)) {
            stats.setStatus("Enchant widget missing: 218." + method.spellWidgetChild);
            return false;
        }

        stats.setStatus("Selecting " + method.spell.getSpellName() + " via 218." + method.spellWidgetChild);
        boolean clicked = clickWidgetCenter(ctx, spellWidget)
                || spellWidget.interact("Cast")
                || spellWidget.click();
        Time.sleep(450, 800, () -> ctx.magic().isSpellSelected(), 100);
        if (!ctx.magic().isSpellSelected()) {
            stats.setStatus("Spell was not selected: 218." + method.spellWidgetChild);
        }
        return clicked && ctx.magic().isSpellSelected();
    }

    private boolean openMagicTab(APIContext ctx) {
        if (ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC)) {
            return true;
        }
        ctx.tabs().open(ITabsAPI.Tabs.MAGIC);
        Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC), 100);
        return ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC);
    }

    private boolean openInventoryTab(APIContext ctx) {
        if (ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            return true;
        }
        ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
        Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
        return ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY);
    }

    private void handleGrandExchange(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }

        if (!isAtGrandExchange(ctx)) {
            stats.setStatus("Walking to GE for enchant trade");
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkTo(GRAND_EXCHANGE_TILE);
            Time.sleep(1200, 1800);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            stats.setStatus("Opening Grand Exchange");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (confirmGeWarning(ctx)) {
            return;
        }

        if (!placedGeActions.isEmpty()) {
            handlePlacedGeActions(ctx);
            return;
        }

        GeAction action = pendingGeActions.poll();
        if (action == null) {
            stats.setStatus("Collecting GE leftovers");
            try {
                ctx.grandExchange().collectToBank();
            } catch (RuntimeException ignored) {
                // Collection is harmless to retry.
            }
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        placeGeAction(ctx, action);
    }

    private void placeGeAction(APIContext ctx, GeAction action) {
        if (action.quantity <= 0) {
            return;
        }

        stats.lastGeAction = action.describe();
        stats.setStatus(action.describe());
        boolean placed;
        if (action.type == GeActionType.BUY) {
            placed = ctx.grandExchange().placeBuyOffer(action.itemName, action.quantity, action.price);
        } else {
            int inventoryCount = ctx.inventory().getCount(true, action.itemName);
            int quantity = Math.min(action.quantity, inventoryCount);
            if (quantity <= 0) {
                stats.setStatus("No inventory item to sell: " + action.itemName);
                Time.sleep(700, 1100);
                return;
            }
            placed = ctx.grandExchange().placeSellOffer(action.itemName, quantity, action.price);
        }

        Time.sleep(1000, 1500);
        if (!placed) {
            if (!confirmGeWarning(ctx)) {
                stats.setStatus("GE offer was not placed: " + action.describe());
                pendingGeActions.add(action);
                Time.sleep(1200, 1800);
            }
            return;
        }

        placedGeActions.add(action);
        nextGeCollectAt = System.currentTimeMillis() + 4_000L;
    }

    private void handlePlacedGeActions(APIContext ctx) {
        if (System.currentTimeMillis() < nextGeCollectAt) {
            stats.setStatus("Waiting for GE offer to fill");
            Time.sleep(800, 1200);
            return;
        }

        int waiting = 0;
        for (GeAction action : placedGeActions) {
            GrandExchangeSlot slot = findSlot(ctx, action);
            if (slot != null && !slot.isCompleted() && !slot.canCollect()) {
                waiting++;
            }
        }

        if (waiting > 0) {
            stats.setStatus("GE offer still pending (" + waiting + ")");
            nextGeCollectAt = System.currentTimeMillis() + 6_000L;
            Time.sleep(900, 1400);
            return;
        }

        stats.setStatus("Collecting completed GE offer(s) to bank");
        try {
            ctx.grandExchange().collectToBank();
        } catch (RuntimeException ignored) {
            // Collection is harmless to retry.
        }
        Time.sleep(900, 1400);
        boolean soldOutput = placedGeActions.stream().anyMatch(action -> action.type == GeActionType.SELL);
        placedGeActions.clear();
        if (soldOutput) {
            activeMethod = null;
            activeQuote = null;
            nextMethodRefreshAt = 0L;
            stats.setStatus("Finished sale cycle; refreshing enchant selector");
        }
    }

    private GrandExchangeSlot findSlot(APIContext ctx, GeAction action) {
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }
            GrandExchangeOffer offer = slot.getOffer();
            if (!namesMatch(offer.getItemName(), action.itemName)) {
                continue;
            }
            boolean buyState = slot.getState().name().contains("BUY") || slot.getState().name().contains("BOUGHT");
            boolean sellState = slot.getState().name().contains("SELL") || slot.getState().name().contains("SOLD");
            if ((action.type == GeActionType.BUY && buyState)
                    || (action.type == GeActionType.SELL && sellState)) {
                return slot;
            }
        }
        return null;
    }

    private boolean confirmGeWarning(APIContext ctx) {
        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null) {
            return false;
        }

        String text = allWidgetText(ctx).toLowerCase();
        if (!text.contains("much higher") && !text.contains("are you sure")) {
            return false;
        }

        stats.setStatus("Confirming GE price warning");
        if (clickWidgetCenter(ctx, yes)
                || yes.interact("Continue")
                || yes.interact("Yes")
                || yes.click()) {
            Time.sleep(1000, 1500);
            return true;
        }
        Time.sleep(600, 900);
        return true;
    }

    private boolean openBank(APIContext ctx, String reason) {
        if (ctx.bank().isOpen()) {
            return true;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (!ctx.bank().isReachable()) {
            stats.setStatus("Walking to nearest bank: " + reason);
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkToBank();
            Time.sleep(1200, 1800);
            return false;
        }

        stats.setStatus("Opening bank: " + reason);
        ctx.bank().open();
        Time.sleep(1000, 1600, () -> ctx.bank().isOpen(), 100);
        return ctx.bank().isOpen();
    }

    private void closeBank(APIContext ctx, String status) {
        stats.setStatus(status);
        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
    }

    private void withdrawCoinsForGe(APIContext ctx, long coinsNeeded) {
        int inventoryCoins = ctx.inventory().getCount(true, COINS);
        int bankCoins = ctx.bank().getCount(COINS);
        int targetInventoryCoins = clampToInt(Math.min(Integer.MAX_VALUE, coinsNeeded));
        if (inventoryCoins >= targetInventoryCoins || bankCoins <= 0) {
            return;
        }

        int toWithdraw = Math.min(targetInventoryCoins - inventoryCoins, bankCoins);
        stats.setStatus("Withdrawing " + toWithdraw + " coins for GE");
        ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
        ctx.bank().withdraw(toWithdraw, COINS);
        Time.sleep(600, 900);
    }

    private boolean inventoryOnlyContains(APIContext ctx, String... names) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (!matchesAny(item.getName(), names)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAny(String actual, String... names) {
        for (String name : names) {
            if (namesMatch(actual, name)) {
                return true;
            }
        }
        return false;
    }

    private boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private String normalizedName(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ")
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");
    }

    private String visibleText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (widget.getName() != null) {
            text.append(' ').append(widget.getName());
        }
        if (widget.getText() != null) {
            text.append(' ').append(widget.getText());
        }
        if (widget.getRawText() != null) {
            text.append(' ').append(widget.getRawText());
        }
        return text.toString().replaceAll("<[^>]+>", " ");
    }

    private WidgetChild findVisibleWidgetByText(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }
            return text.equalsIgnoreCase(cleanWidgetText(candidate.getText()))
                    || text.equalsIgnoreCase(cleanWidgetText(candidate.getRawText()));
        })) {
            return widget;
        }
        WidgetChild queried = ctx.widgets().query().textContains(text).results().first();
        return isVisibleWidget(queried) ? queried : null;
    }

    private String cleanWidgetText(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", " ").trim();
    }

    private String allWidgetText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : ctx.widgets().getAllChildren(this::isVisibleWidget)) {
            text.append(' ').append(visibleText(widget));
        }
        return text.toString();
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private boolean isAtGrandExchange(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null || tile.getPlane() != 0) {
            return false;
        }
        return tile.getX() >= GE_MIN_X
                && tile.getX() <= GE_MAX_X
                && tile.getY() >= GE_MIN_Y
                && tile.getY() <= GE_MAX_Y;
    }

    private int magicLevel(APIContext ctx) {
        if (ctx == null) {
            return 0;
        }
        return ctx.skills().get(Skill.Skills.MAGIC).getRealLevel();
    }

    private String marginSummary(APIContext ctx) {
        StringBuilder summary = new StringBuilder();
        for (EnchantMethod method : METHODS) {
            Quote quote = pricing.quote(ctx, method);
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(method.key).append('=').append(quote.profitPerCast);
        }
        return summary.toString();
    }

    private void clearClientInteractionState() {
        APIContext ctx = getAPIContext();
        if (ctx == null) {
            return;
        }
        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only; stopping must not throw.
        }
    }

    private void log(String message) {
        if (stats != null) {
            stats.setStatus(message);
        }
        getLogger().info(message);
    }

    private void logOccasionally(String message) {
        long now = System.currentTimeMillis();
        if (now < nextIdleLogAt) {
            return;
        }
        log(message);
        nextIdleLogAt = now + 15_000L;
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private int clampToInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    private static class EnchantMethod {
        private final String key;
        private final String label;
        private final int requiredMagic;
        private final Spell spell;
        private final int spellWidgetChild;
        private final String staff;
        private final String inputItem;
        private final String outputItem;
        private final long fallbackInputBuy;
        private final long fallbackOutputSell;
        private final int minProfit;
        private final int baseWeight;

        private EnchantMethod(
                String key,
                String label,
                int requiredMagic,
                Spell spell,
                int spellWidgetChild,
                String staff,
                String inputItem,
                String outputItem,
                long fallbackInputBuy,
                long fallbackOutputSell,
                int minProfit,
                int baseWeight
        ) {
            this.key = key;
            this.label = label;
            this.requiredMagic = requiredMagic;
            this.spell = spell;
            this.spellWidgetChild = spellWidgetChild;
            this.staff = staff;
            this.inputItem = inputItem;
            this.outputItem = outputItem;
            this.fallbackInputBuy = fallbackInputBuy;
            this.fallbackOutputSell = fallbackOutputSell;
            this.minProfit = minProfit;
            this.baseWeight = baseWeight;
        }
    }

    private class Pricing {
        private Quote quote(APIContext ctx, EnchantMethod method) {
            ItemDetail input = itemDetail(ctx, method.inputItem);
            ItemDetail cosmic = itemDetail(ctx, COSMIC_RUNE);
            ItemDetail output = itemDetail(ctx, method.outputItem);
            int inputBuy = firstPositive(highPrice(input), lowPrice(input), method.fallbackInputBuy);
            int cosmicBuy = firstPositive(highPrice(cosmic), lowPrice(cosmic), 113L);
            int outputSell = firstPositive(lowPrice(output), highPrice(output), method.fallbackOutputSell);
            long cost = (long) inputBuy + cosmicBuy;
            long profit = outputSell <= 0 || inputBuy <= 0 || cosmicBuy <= 0
                    ? Long.MIN_VALUE
                    : taxedSellValue(outputSell) - cost;
            long profitPerHour = profit == Long.MIN_VALUE ? Long.MIN_VALUE : profit * 1600L;
            return new Quote(method, inputBuy, cosmicBuy, outputSell, cost, profit, profitPerHour);
        }

        private int quickBuyPrice(APIContext ctx, String itemName, long fallbackPrice) {
            ItemDetail detail = itemDetail(ctx, itemName);
            long market = firstPositive(highPrice(detail), lowPrice(detail), fallbackPrice);
            return clampToInt(Math.max(1L, Math.round(Math.ceil(market * BUY_MARKUP))));
        }

        private int quickSellPrice(APIContext ctx, String itemName, long fallbackPrice) {
            ItemDetail detail = itemDetail(ctx, itemName);
            long market = firstPositive(lowPrice(detail), highPrice(detail), fallbackPrice);
            return clampToInt(Math.max(1L, Math.round(Math.floor(market * SELL_MARKDOWN))));
        }

        private ItemDetail itemDetail(APIContext ctx, String itemName) {
            if (ctx == null || itemName == null || itemName.isBlank()) {
                return null;
            }
            try {
                return ctx.pricing().get(itemName);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private int highPrice(ItemDetail detail) {
            return detail == null ? 0 : Math.max(0, detail.getHighestPrice());
        }

        private int lowPrice(ItemDetail detail) {
            return detail == null ? 0 : Math.max(0, detail.getLowestPrice());
        }

        private int firstPositive(long first, long second, long third) {
            if (first > 0) {
                return clampToInt(first);
            }
            if (second > 0) {
                return clampToInt(second);
            }
            return clampToInt(Math.max(1L, third));
        }

        private long taxedSellValue(long sellPrice) {
            long tax = (long) Math.floor(sellPrice * GE_TAX_RATE);
            return Math.max(0L, sellPrice - tax);
        }
    }

    private static class Quote {
        private final EnchantMethod method;
        private final int inputBuyPrice;
        private final int cosmicBuyPrice;
        private final int outputSellPrice;
        private final long costPerCast;
        private final long profitPerCast;
        private final long profitPerHour;

        private Quote(
                EnchantMethod method,
                int inputBuyPrice,
                int cosmicBuyPrice,
                int outputSellPrice,
                long costPerCast,
                long profitPerCast,
                long profitPerHour
        ) {
            this.method = method;
            this.inputBuyPrice = inputBuyPrice;
            this.cosmicBuyPrice = cosmicBuyPrice;
            this.outputSellPrice = outputSellPrice;
            this.costPerCast = costPerCast;
            this.profitPerCast = profitPerCast;
            this.profitPerHour = profitPerHour;
        }

        private boolean hasPrices() {
            return profitPerCast != Long.MIN_VALUE;
        }

        private boolean profitable() {
            return hasPrices() && profitPerCast > 0;
        }
    }

    private enum GeActionType {
        BUY,
        SELL
    }

    private static class GeAction {
        private final GeActionType type;
        private final String itemName;
        private final int quantity;
        private final int price;

        private GeAction(GeActionType type, String itemName, int quantity, int price) {
            this.type = type;
            this.itemName = itemName;
            this.quantity = quantity;
            this.price = price;
        }

        private static GeAction buy(String itemName, int quantity, int price) {
            return new GeAction(GeActionType.BUY, itemName, quantity, price);
        }

        private static GeAction sell(String itemName, int quantity, int price) {
            return new GeAction(GeActionType.SELL, itemName, quantity, price);
        }

        private String describe() {
            return type.name().toLowerCase() + " " + quantity + "x " + itemName + " @ " + price;
        }
    }

    private static class Stats {
        private final long startedAt = System.currentTimeMillis();
        private int startingMagicXp = -1;
        private int casts;
        private String status = "Starting";
        private String lastChat = "-";
        private String lastGeAction = "-";

        private void startExperienceIfNeeded(APIContext ctx) {
            if (ctx == null || startingMagicXp >= 0) {
                return;
            }
            startingMagicXp = ctx.skills().get(Skill.Skills.MAGIC).getExperience();
        }

        private int xpGained(APIContext ctx) {
            if (ctx == null || startingMagicXp < 0) {
                return 0;
            }
            return Math.max(0, ctx.skills().get(Skill.Skills.MAGIC).getExperience() - startingMagicXp);
        }

        private int xpPerHour(APIContext ctx) {
            long elapsed = Math.max(1L, System.currentTimeMillis() - startedAt);
            return (int) Math.round(xpGained(ctx) * 3_600_000D / elapsed);
        }

        private String runtimeText() {
            long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
            long hours = seconds / 3600L;
            long minutes = (seconds % 3600L) / 60L;
            long secs = seconds % 60L;
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        }

        private void setStatus(String status) {
            this.status = status == null ? "-" : status;
        }
    }
}
