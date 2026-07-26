package org.gusta.enchanting;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IEquipmentAPI;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ScriptManifest(name = "Enchant Jewellery Profit", gameType = GameType.OS)
public class EnchantJewelleryProfitScript extends Script {
    private static final String SCRIPT_VERSION = "v0.2.2-enchant-before-walking";
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
    private static final long ENCHANT_BATCH_STALL_MS = 15_000L;
    private static final int HUMAN_WIDGET_MIN_MS = 900;
    private static final int HUMAN_WIDGET_MAX_MS = 1_650;
    private static final int HUMAN_ITEM_MIN_MS = 700;
    private static final int HUMAN_ITEM_MAX_MS = 1_350;
    private static final long ROW_TELEPORT_RETRY_MS = 12_000L;
    private static final long READY_TO_ENCHANT_GRACE_MS = 45_000L;
    private static final int SPELLBOOK_GROUP = 218;
    private static final int JEWELLERY_ENCHANTMENTS_CHILD = 15;
    private static final int LEVEL_1_ENCHANT_CHILD = 16;
    private static final int LEVEL_2_ENCHANT_CHILD = 11;
    private static final long METHOD_REFRESH_MS = 4 * 60_000L;
    private static final long WIKI_PRICE_REFRESH_MS = 3 * 60_000L;
    private static final long WIKI_PRICE_STALE_MS = 12 * 60_000L;
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
                    120,
                    5,
                    1637,
                    2550,
                    250,
                    500,
                    160,
                    700,
                    true
            ),
            new EnchantMethod(
                    "sapphire_necklaces",
                    "Sapphire necklaces",
                    7,
                    Spell.Modern.LEVEL_1_ENCHANT,
                    LEVEL_1_ENCHANT_CHILD,
                    "Staff of water",
                    "Sapphire necklace",
                    "Games necklace(8)",
                    402,
                    799,
                    150,
                    5,
                    1656,
                    3853,
                    500,
                    300,
                    120,
                    500,
                    false
            ),
            new EnchantMethod(
                    "opal_bracelets",
                    "Opal bracelets",
                    7,
                    Spell.Modern.LEVEL_1_ENCHANT,
                    LEVEL_1_ENCHANT_CHILD,
                    "Staff of water",
                    "Opal bracelet",
                    "Expeditious bracelet",
                    1100,
                    1476,
                    180,
                    4,
                    21117,
                    21177,
                    150,
                    500,
                    54,
                    220,
                    false
            ),
            new EnchantMethod(
                    "opal_necklaces",
                    "Opal necklaces",
                    7,
                    Spell.Modern.LEVEL_1_ENCHANT,
                    LEVEL_1_ENCHANT_CHILD,
                    "Staff of water",
                    "Opal necklace",
                    "Dodgy necklace",
                    703,
                    1219,
                    180,
                    4,
                    21090,
                    21143,
                    150,
                    300,
                    54,
                    220,
                    false
            ),
            new EnchantMethod(
                    "emerald_rings",
                    "Emerald rings",
                    27,
                    Spell.Modern.LEVEL_2_ENCHANT,
                    LEVEL_2_ENCHANT_CHILD,
                    "Staff of air",
                    "Emerald ring",
                    "Ring of dueling(8)",
                    558,
                    800,
                    90,
                    3,
                    1639,
                    2552,
                    250,
                    500,
                    120,
                    400,
                    false
            )
    };

    private final Queue<GeAction> pendingGeActions = new ArrayDeque<>();
    private final List<GeAction> placedGeActions = new ArrayList<>();
    private final WikiPriceClient wikiPrices = new WikiPriceClient();
    private final Pricing pricing = new Pricing();

    private Stats stats;
    private EnchantMethod activeMethod;
    private EnchantMethod previousMethod;
    private Quote activeQuote;
    private int activeBatchTargetCasts;
    private int activeBatchCasts;
    private boolean enchantInventoryCycleActive;
    private EnchantMethod enchantCycleMethod;
    private int enchantCycleLastInputCount;
    private int enchantCycleLastOutputCount;
    private long enchantCycleLastProgressAt;
    private long nextMethodRefreshAt;
    private long nextGeCollectAt;
    private long nextIdleLogAt;
    private long nextRowTeleportAttemptAt;
    private long lastSpellWidgetClickAt;
    private long lastReadyToEnchantAt;
    private boolean forceSpellSelectionForNextInventory;
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
        resetEnchantCycle();
        clearClientInteractionState();
        getLogger().info("Enchant Jewellery Profit " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        resetEnchantCycle();
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

            if (enchantInventoryCycleActive && activeMethod != null) {
                debugLog("Continuing active enchant cycle before location gate. method="
                        + activeMethod.label + " location=" + locationText(ctx));
                enchantInventory(ctx, activeMethod);
                return;
            }

            EnchantMethod readyMethod = readyEnchantMethod(ctx);
            if (readyMethod != null) {
                if (activeMethod == null || !activeMethod.key.equals(readyMethod.key)) {
                    activeMethod = readyMethod;
                    activeQuote = pricing.quote(ctx, readyMethod);
                    nextMethodRefreshAt = System.currentTimeMillis() + METHOD_REFRESH_MS;
                }
                debugLog("Enchant inventory ready before location gate. method="
                        + readyMethod.label
                        + " location=" + locationText(ctx)
                        + " inventory=" + inventoryState(ctx, readyMethod));
                enchantInventory(ctx, readyMethod);
                return;
            }

            if (!ensureAtGrandExchangeBeforeActions(ctx)) {
                return;
            }

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
        resetEnchantCycle();
        nextMethodRefreshAt = now + METHOD_REFRESH_MS;

        log("Selected enchant: " + activeMethod.label
                + " profit/cast=" + activeQuote.profitPerCast
                + " source=" + activeQuote.priceSource
                + " vol1h=" + activeQuote.inputVolume1h + "/" + activeQuote.outputVolume1h
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
                    && quote.profitPerCast >= Math.max(MIN_PROFIT_PER_CAST, method.minProfit)
                    && quote.passesLiquidityFilter()) {
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

        int targetCasts = restockTargetCasts(activeQuote);
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

    private int restockTargetCasts(Quote quote) {
        EnchantMethod method = quote == null ? activeMethod : quote.method;
        if (method == null) {
            return ThreadLocalRandom.current().nextInt(RESTOCK_MIN_CASTS, RESTOCK_MAX_CASTS + 1);
        }

        int min = Math.max(INVENTORY_INPUT_AMOUNT, method.restockMinCasts);
        int max = Math.max(min, method.restockMaxCasts);
        if (quote != null && quote.hasWikiVolume()) {
            int bottleneck = Math.min(quote.inputVolume1h, quote.outputVolume1h);
            if (bottleneck > 0) {
                max = Math.min(max, Math.max(min, bottleneck / 2));
            }
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
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

    private EnchantMethod readyEnchantMethod(APIContext ctx) {
        if (ctx == null || ctx.bank().isOpen()) {
            return null;
        }
        if (activeMethod != null && inventoryReadyForEnchant(ctx, activeMethod)) {
            return activeMethod;
        }
        for (EnchantMethod method : METHODS) {
            if (inventoryReadyForEnchant(ctx, method)) {
                return method;
            }
        }
        return null;
    }

    private boolean inventoryReadyForEnchant(APIContext ctx, EnchantMethod method) {
        if (ctx == null || method == null) {
            return false;
        }
        return ctx.inventory().getCount(method.inputItem) > 0
                && ctx.inventory().getCount(true, COSMIC_RUNE) > 0
                && ctx.equipment().contains(method.staff);
    }

    private void enchantInventory(APIContext ctx, EnchantMethod method) {
        if (ctx.bank().isOpen()) {
            closeBank(ctx, "Ready to enchant " + method.label);
            return;
        }

        if (handleActiveEnchantCycle(ctx, method)) {
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

        int beforeInput = ctx.inventory().getCount(method.inputItem);
        int beforeOutput = ctx.inventory().getCount(method.outputItem);
        stats.setStatus("Starting inventory enchant: " + method.inputItem + " -> " + method.outputItem);
        boolean cast = selectSpellAndClickItem(ctx, method);

        Time.sleep(
                900,
                1600,
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
        }

        int currentInput = ctx.inventory().getCount(method.inputItem);
        int currentOutput = ctx.inventory().getCount(method.outputItem);
        if (cast || converted > 0 || ctx.localPlayer().isAnimating()) {
            startEnchantCycle(method, currentInput, currentOutput);
            stats.setStatus("Enchanting inventory: " + currentInput + " " + method.inputItem + " left");
            return;
        }

        if (!cast) {
            stats.setStatus("Enchant cast did not start for " + method.label);
        }
        Time.sleep(600, 900);
    }

    private boolean handleActiveEnchantCycle(APIContext ctx, EnchantMethod method) {
        if (!enchantInventoryCycleActive) {
            return false;
        }

        if (enchantCycleMethod == null || !enchantCycleMethod.key.equals(method.key)) {
            resetEnchantCycle();
            return false;
        }

        int currentInput = ctx.inventory().getCount(method.inputItem);
        int currentOutput = ctx.inventory().getCount(method.outputItem);
        recordEnchantCycleProgress(currentInput, currentOutput);

        if (currentInput <= 0) {
            stats.setStatus("Inventory finished; banking " + method.outputItem);
            resetEnchantCycle();
            Time.sleep(350, 650);
            return true;
        }

        long idleMs = System.currentTimeMillis() - enchantCycleLastProgressAt;
        if (idleMs > ENCHANT_BATCH_STALL_MS && !ctx.localPlayer().isAnimating()) {
            stats.setStatus("Enchant batch stalled; restarting " + method.label);
            resetEnchantCycle();
            Time.sleep(500, 800);
            return false;
        }

        stats.setStatus("Enchanting inventory: " + currentInput + " " + method.inputItem + " left");
        Time.sleep(700, 1100);
        return true;
    }

    private void startEnchantCycle(EnchantMethod method, int currentInput, int currentOutput) {
        enchantInventoryCycleActive = currentInput > 0;
        enchantCycleMethod = enchantInventoryCycleActive ? method : null;
        enchantCycleLastInputCount = currentInput;
        enchantCycleLastOutputCount = currentOutput;
        enchantCycleLastProgressAt = System.currentTimeMillis();
        forceSpellSelectionForNextInventory = false;
    }

    private void recordEnchantCycleProgress(int currentInput, int currentOutput) {
        int converted = Math.max(
                enchantCycleLastInputCount - currentInput,
                currentOutput - enchantCycleLastOutputCount
        );
        if (converted > 0) {
            stats.casts += converted;
            activeBatchCasts += converted;
            enchantCycleLastInputCount = currentInput;
            enchantCycleLastOutputCount = currentOutput;
            enchantCycleLastProgressAt = System.currentTimeMillis();
        }
    }

    private void resetEnchantCycle() {
        enchantInventoryCycleActive = false;
        enchantCycleMethod = null;
        enchantCycleLastInputCount = 0;
        enchantCycleLastOutputCount = 0;
        enchantCycleLastProgressAt = 0L;
    }

    private boolean selectSpellAndClickItem(APIContext ctx, EnchantMethod method) {
        boolean shouldSelectSpell = forceSpellSelectionForNextInventory || !ctx.magic().isSpellSelected();
        if (shouldSelectSpell) {
            if (!selectJewelleryEnchantSpell(ctx, method)) {
                return false;
            }
            forceSpellSelectionForNextInventory = false;
            humanWidgetPause();
        }

        if (!openInventoryTab(ctx)) {
            return false;
        }
        humanItemPause();

        return clickEnchantMaterial(ctx, method);
    }

    private boolean selectJewelleryEnchantSpell(APIContext ctx, EnchantMethod method) {
        if (!openMagicTab(ctx)) {
            return false;
        }

        if (!openJewelleryEnchantments(ctx, method)) {
            return false;
        }

        return clickEnchantSpellWidget(ctx, method);
    }

    private boolean openJewelleryEnchantments(APIContext ctx, EnchantMethod method) {
        WidgetChild expectedEnchant = ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild);
        if (isVisibleWidget(expectedEnchant)) {
            return true;
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            WidgetChild jewelleryEnchantments = ctx.widgets().get(SPELLBOOK_GROUP, JEWELLERY_ENCHANTMENTS_CHILD);
            if (!isVisibleWidget(jewelleryEnchantments)) {
                stats.setStatus("Jewellery Enchantments widget missing: 218." + JEWELLERY_ENCHANTMENTS_CHILD);
                return false;
            }

            stats.setStatus("Opening Jewellery Enchantments via 218." + JEWELLERY_ENCHANTMENTS_CHILD);
            humanWidgetPause();
            boolean opened = interactWidgetActionsOnly(jewelleryEnchantments, "Open", "View", "Cast");
            debugLog("Jewellery menu click. method=" + method.label
                    + " clicked=" + opened
                    + " widget=" + widgetDebug(jewelleryEnchantments)
                    + " location=" + locationText(ctx));
            Time.sleep(
                    HUMAN_WIDGET_MIN_MS,
                    HUMAN_WIDGET_MAX_MS,
                    () -> isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild)),
                    100
            );

            if (isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild))) {
                return true;
            }
        }

        stats.setStatus("Enchant widget missing after opening: 218." + method.spellWidgetChild);
        return false;
    }

    private boolean clickEnchantSpellWidget(APIContext ctx, EnchantMethod method) {
        WidgetChild spellWidget = ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild);
        if (!isVisibleWidget(spellWidget)) {
            stats.setStatus("Enchant widget missing: 218." + method.spellWidgetChild);
            return false;
        }

        stats.setStatus("Selecting " + method.spell.getSpellName() + " via 218." + method.spellWidgetChild);
        humanWidgetPause();
        boolean clicked = interactWidgetActionsOnly(spellWidget, "Cast", method.spell.getSpellName());
        debugLog("Enchant spell widget click. method=" + method.label
                + " clicked=" + clicked
                + " widget=" + widgetDebug(spellWidget)
                + " location=" + locationText(ctx));
        Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS, () -> ctx.magic().isSpellSelected(), 100);

        if (!ctx.magic().isSpellSelected()) {
            humanWidgetPause();
            clicked = interactWidgetActionsOnly(spellWidget, "Cast", method.spell.getSpellName()) || clicked;
            debugLog("Enchant spell widget retry. method=" + method.label
                    + " clicked=" + clicked
                    + " spellSelected=" + ctx.magic().isSpellSelected()
                    + " location=" + locationText(ctx));
            Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS, () -> ctx.magic().isSpellSelected(), 100);
        }

        if (!ctx.magic().isSpellSelected()) {
            stats.setStatus("Spell selection not detected; clicking material anyway");
        }
        if (clicked || ctx.magic().isSpellSelected()) {
            lastSpellWidgetClickAt = System.currentTimeMillis();
        }
        return clicked;
    }

    private boolean clickEnchantMaterial(APIContext ctx, EnchantMethod method) {
        ItemWidget item = ctx.inventory().getItem(method.inputItem);
        if (item == null) {
            stats.setStatus("Missing material in inventory: " + method.inputItem);
            debugLog("Material missing before cast. method=" + method.label
                    + " location=" + locationText(ctx)
                    + " inventory=" + inventoryState(ctx, method));
            return false;
        }

        stats.setStatus("Clicking material after spell: " + method.inputItem);
        humanItemPause();
        boolean directClickExpected = ctx.magic().isSpellSelected()
                || System.currentTimeMillis() - lastSpellWidgetClickAt < 8_000L;
        boolean clicked = false;
        debugLog("Material click attempt. method=" + method.label
                + " item=" + itemDebug(item)
                + " directExpected=" + directClickExpected
                + " spellSelected=" + ctx.magic().isSpellSelected()
                + " inventoryTab=" + ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)
                + " location=" + locationText(ctx));

        if (directClickExpected) {
            clicked = ctx.inventory().interactItem("Cast", method.inputItem)
                    || item.interact("Cast");
        }

        if (!clicked) {
            clicked = ctx.menu().interact("Cast", method.inputItem, item, false)
                    || ctx.menu().interact("Cast", item, false)
                    || item.interact("Cast");
        }

        Time.sleep(HUMAN_ITEM_MIN_MS, HUMAN_ITEM_MAX_MS);
        debugLog("Material click result. method=" + method.label
                + " clicked=" + clicked
                + " input=" + ctx.inventory().getCount(method.inputItem)
                + " output=" + ctx.inventory().getCount(method.outputItem)
                + " moving=" + ctx.localPlayer().isMoving()
                + " animating=" + ctx.localPlayer().isAnimating()
                + " location=" + locationText(ctx));
        return clicked;
    }

    private boolean clickWidgetActions(APIContext ctx, WidgetChild widget, String... actions) {
        if (!isVisibleWidget(widget)) {
            return false;
        }

        for (String action : actions) {
            if (action != null && !action.isBlank() && widget.interact(action)) {
                return true;
            }
        }

        return clickWidgetCenter(ctx, widget) || widget.click();
    }

    private boolean interactWidgetActionsOnly(WidgetChild widget, String... actions) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        for (String action : actions) {
            if (action != null && !action.isBlank() && widget.interact(action)) {
                return true;
            }
        }
        return false;
    }

    private boolean openMagicTab(APIContext ctx) {
        if (ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC)) {
            return true;
        }
        humanWidgetPause();
        ctx.tabs().open(ITabsAPI.Tabs.MAGIC);
        Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC), 100);
        return ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC);
    }

    private boolean openInventoryTab(APIContext ctx) {
        if (ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            return true;
        }
        humanItemPause();
        ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
        Time.sleep(HUMAN_ITEM_MIN_MS, HUMAN_ITEM_MAX_MS, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
        return ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY);
    }

    private void humanWidgetPause() {
        Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS);
    }

    private void humanItemPause() {
        Time.sleep(HUMAN_ITEM_MIN_MS, HUMAN_ITEM_MAX_MS);
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

    private boolean ensureAtGrandExchangeBeforeActions(APIContext ctx) {
        if (isAtGrandExchange(ctx)) {
            nextRowTeleportAttemptAt = 0L;
            return true;
        }

        if (activeMethod != null
                && recentlyPreparedEnchantInventory()
                && inventoryReadyForEnchant(ctx, activeMethod)
                && !ctx.bank().isOpen()
                && !ctx.grandExchange().isOpen()) {
            debugLog("GE gate suppressed after bank prep; inventory is ready for "
                    + activeMethod.label
                    + " location=" + locationText(ctx)
                    + " input=" + ctx.inventory().getCount(activeMethod.inputItem)
                    + " cosmics=" + ctx.inventory().getCount(true, COSMIC_RUNE));
            return true;
        }

        debugLog("GE gate: player is outside GE before action. location=" + locationText(ctx)
                + " bankOpen=" + ctx.bank().isOpen()
                + " geOpen=" + ctx.grandExchange().isOpen()
                + " moving=" + ctx.localPlayer().isMoving()
                + " animating=" + ctx.localPlayer().isAnimating()
                + " readyGrace=" + recentlyPreparedEnchantInventory());

        resetEnchantCycle();

        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before ROW teleport to GE");
            ctx.bank().close();
            Time.sleep(650, 1000, () -> !ctx.bank().isOpen(), 100);
            return false;
        }

        if (ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing GE before ROW teleport retry");
            ctx.grandExchange().close();
            Time.sleep(650, 1000, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting for GE travel");
            Time.sleep(800, 1300, () -> isAtGrandExchange(ctx), 100);
            return false;
        }

        long now = System.currentTimeMillis();
        if (now < nextRowTeleportAttemptAt) {
            stats.setStatus("Waiting before retrying equipped ROW teleport to GE");
            Time.sleep(700, 1100);
            return false;
        }

        ItemWidget equippedRing = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        if (!isChargedRingOfWealth(equippedRing)) {
            stats.setStatus("Equip charged Ring of wealth before starting");
            logOccasionally("Script is outside GE and requires an equipped charged Ring of wealth.");
            Time.sleep(1200, 1800);
            return false;
        }

        nextRowTeleportAttemptAt = now + ROW_TELEPORT_RETRY_MS;
        stats.setStatus("Teleporting to GE with equipped Ring of wealth");
        if (useEquippedRingOfWealthToGe(ctx, equippedRing)) {
            Time.sleep(2500, 5500, () -> isAtGrandExchange(ctx) || ctx.localPlayer().isMoving(), 100);
            return false;
        }

        stats.setStatus("Equipped ROW teleport to GE failed; retrying soon");
        Time.sleep(900, 1400);
        return false;
    }

    private boolean useEquippedRingOfWealthToGe(APIContext ctx, ItemWidget ring) {
        if (ring == null) {
            return false;
        }

        if (interactRingTeleport(ring, "Grand Exchange")
                || interactRingTeleport(ring, "Grand Exchange teleport")
                || interactRingTeleport(ring, "GE")) {
            return true;
        }

        if (!interactRingTeleport(ring, "Rub")) {
            return false;
        }

        Time.sleep(900, 1500,
                () -> isAtGrandExchange(ctx) || findVisibleWidgetByText(ctx, "Grand Exchange") != null,
                100);
        if (isAtGrandExchange(ctx)) {
            return true;
        }

        WidgetChild grandExchange = findVisibleWidgetByText(ctx, "Grand Exchange");
        if (grandExchange == null) {
            return false;
        }

        stats.setStatus("Selecting Grand Exchange on ROW menu");
        return clickWidgetCenter(ctx, grandExchange)
                || grandExchange.interact("Grand Exchange")
                || grandExchange.interact("Continue")
                || grandExchange.interact("Select")
                || grandExchange.click();
    }

    private boolean interactRingTeleport(ItemWidget ring, String action) {
        try {
            String name = ring.getName();
            return ring.interact(action, name) || ring.interact(action);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isChargedRingOfWealth(ItemWidget item) {
        if (item == null || item.getName() == null) {
            return false;
        }
        String normalized = normalizedName(item.getName());
        return normalized.startsWith("ringofwealth")
                && !normalized.equals("ringofwealth0")
                && !normalized.contains("uncharged");
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
        if (status != null && status.startsWith("Ready to enchant")) {
            forceSpellSelectionForNextInventory = true;
            lastSpellWidgetClickAt = 0L;
            lastReadyToEnchantAt = System.currentTimeMillis();
            debugLog("Bank closed with enchant inventory ready. method="
                    + (activeMethod == null ? "-" : activeMethod.label)
                    + " location=" + locationText(ctx)
                    + " inventory=" + inventoryState(ctx, activeMethod));
        }
        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
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
            summary.append(method.key)
                    .append('=').append(quote.profitPerCast)
                    .append("gp/")
                    .append(quote.priceSource)
                    .append(" v1h ")
                    .append(quote.inputVolume1h)
                    .append('/')
                    .append(quote.outputVolume1h);
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

    private void debugLog(String message) {
        getLogger().info("[Flow] " + message);
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

    private boolean recentlyPreparedEnchantInventory() {
        return lastReadyToEnchantAt > 0L
                && System.currentTimeMillis() - lastReadyToEnchantAt <= READY_TO_ENCHANT_GRACE_MS;
    }

    private String locationText(APIContext ctx) {
        try {
            Tile tile = ctx.localPlayer().getLocation();
            if (tile == null) {
                return "unknown";
            }
            return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private String inventoryState(APIContext ctx, EnchantMethod method) {
        if (ctx == null || method == null) {
            return "-";
        }
        return method.inputItem + "=" + safeCount(() -> ctx.inventory().getCount(method.inputItem))
                + "," + method.outputItem + "=" + safeCount(() -> ctx.inventory().getCount(method.outputItem))
                + "," + COSMIC_RUNE + "=" + safeCount(() -> ctx.inventory().getCount(true, COSMIC_RUNE));
    }

    private String itemDebug(ItemWidget item) {
        if (item == null) {
            return "null";
        }
        Rectangle bounds;
        try {
            bounds = item.getBounds();
        } catch (RuntimeException ignored) {
            bounds = null;
        }
        return "{name='" + item.getName()
                + "',idx=" + safeCount(item::getIndex)
                + ",x=" + safeCount(item::getX)
                + ",y=" + safeCount(item::getY)
                + ",w=" + safeCount(item::getWidth)
                + ",h=" + safeCount(item::getHeight)
                + ",bounds=" + bounds
                + "}";
    }

    private String widgetDebug(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        Rectangle bounds;
        try {
            bounds = widget.getBounds();
        } catch (RuntimeException ignored) {
            bounds = null;
        }
        return "{parent=" + safeCount(widget::getParentId)
                + ",child=" + safeCount(widget::getChildId)
                + ",idx=" + safeCount(widget::getIndex)
                + ",w=" + safeCount(widget::getWidth)
                + ",h=" + safeCount(widget::getHeight)
                + ",bounds=" + bounds
                + "}";
    }

    private int safeCount(IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
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
        private final long inputPriceId;
        private final long outputPriceId;
        private final int minInputVolume1h;
        private final int minOutputVolume1h;
        private final int restockMinCasts;
        private final int restockMaxCasts;
        private final boolean allowClientPriceFallback;

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
                int baseWeight,
                long inputPriceId,
                long outputPriceId,
                int minInputVolume1h,
                int minOutputVolume1h,
                int restockMinCasts,
                int restockMaxCasts,
                boolean allowClientPriceFallback
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
            this.inputPriceId = inputPriceId;
            this.outputPriceId = outputPriceId;
            this.minInputVolume1h = minInputVolume1h;
            this.minOutputVolume1h = minOutputVolume1h;
            this.restockMinCasts = restockMinCasts;
            this.restockMaxCasts = restockMaxCasts;
            this.allowClientPriceFallback = allowClientPriceFallback;
        }
    }

    private class Pricing {
        private Quote quote(APIContext ctx, EnchantMethod method) {
            WikiQuote wikiQuote = wikiPrices.quote(method);
            if (wikiQuote != null) {
                long cost = (long) wikiQuote.inputBuyPrice + wikiQuote.cosmicBuyPrice;
                long profit = taxedSellValue(wikiQuote.outputSellPrice) - cost;
                long profitPerHour = profit * 1600L;
                return new Quote(
                        method,
                        wikiQuote.inputBuyPrice,
                        wikiQuote.cosmicBuyPrice,
                        wikiQuote.outputSellPrice,
                        cost,
                        profit,
                        profitPerHour,
                        wikiQuote.inputVolume1h,
                        wikiQuote.outputVolume1h,
                        wikiQuote.inputVolume5m,
                        wikiQuote.outputVolume5m,
                        "wiki"
                );
            }

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
            return new Quote(method, inputBuy, cosmicBuy, outputSell, cost, profit, profitPerHour,
                    0, 0, 0, 0, "client");
        }

        private int quickBuyPrice(APIContext ctx, String itemName, long fallbackPrice) {
            Integer wikiBuy = wikiPrices.quickBuyPrice(itemName);
            if (wikiBuy != null && wikiBuy > 0) {
                return clampToInt(Math.max(1L, Math.round(Math.ceil(wikiBuy * BUY_MARKUP))));
            }

            ItemDetail detail = itemDetail(ctx, itemName);
            long market = firstPositive(highPrice(detail), lowPrice(detail), fallbackPrice);
            return clampToInt(Math.max(1L, Math.round(Math.ceil(market * BUY_MARKUP))));
        }

        private int quickSellPrice(APIContext ctx, String itemName, long fallbackPrice) {
            Integer wikiSell = wikiPrices.quickSellPrice(itemName);
            if (wikiSell != null && wikiSell > 0) {
                return clampToInt(Math.max(1L, Math.round(Math.floor(wikiSell * SELL_MARKDOWN))));
            }

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

    private class WikiPriceClient {
        private static final String USER_AGENT = "EnchantJewelleryProfitScript/0.2 (github.com/guskrol/EnchantJewellery)";
        private static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";
        private static final String FIVE_MIN_URL = "https://prices.runescape.wiki/api/v1/osrs/5m";
        private static final String ONE_HOUR_URL = "https://prices.runescape.wiki/api/v1/osrs/1h";
        private static final long COSMIC_RUNE_ID = 564L;

        private WikiSnapshot cachedSnapshot;
        private long cachedSnapshotAt;

        private WikiQuote quote(EnchantMethod method) {
            WikiSnapshot snapshot = snapshot();
            if (snapshot == null || method == null) {
                return null;
            }

            WikiItemPrice input = snapshot.item(method.inputPriceId);
            WikiItemPrice cosmic = snapshot.item(COSMIC_RUNE_ID);
            WikiItemPrice output = snapshot.item(method.outputPriceId);
            if (input == null || cosmic == null || output == null) {
                return null;
            }

            int inputBuy = firstPositive(input.latestHigh, input.avgHigh5m, input.avgHigh1h, method.fallbackInputBuy);
            int cosmicBuy = firstPositive(cosmic.latestHigh, cosmic.avgHigh5m, cosmic.avgHigh1h, 113L);
            int outputSell = firstPositive(output.latestLow, output.avgLow5m, output.avgLow1h, method.fallbackOutputSell);
            if (inputBuy <= 0 || cosmicBuy <= 0 || outputSell <= 0) {
                return null;
            }

            return new WikiQuote(
                    inputBuy,
                    cosmicBuy,
                    outputSell,
                    input.highVolume1h,
                    output.lowVolume1h,
                    input.highVolume5m,
                    output.lowVolume5m
            );
        }

        private Integer quickBuyPrice(String itemName) {
            Long itemId = priceIdForName(itemName);
            if (itemId == null) {
                return null;
            }
            WikiSnapshot snapshot = snapshot();
            WikiItemPrice price = snapshot == null ? null : snapshot.item(itemId);
            if (price == null) {
                return null;
            }
            int value = firstPositive(price.latestHigh, price.avgHigh5m, price.avgHigh1h);
            return value <= 0 ? null : value;
        }

        private Integer quickSellPrice(String itemName) {
            Long itemId = priceIdForName(itemName);
            if (itemId == null) {
                return null;
            }
            WikiSnapshot snapshot = snapshot();
            WikiItemPrice price = snapshot == null ? null : snapshot.item(itemId);
            if (price == null) {
                return null;
            }
            int value = firstPositive(price.latestLow, price.avgLow5m, price.avgLow1h);
            return value <= 0 ? null : value;
        }

        private WikiSnapshot snapshot() {
            long now = System.currentTimeMillis();
            if (cachedSnapshot != null && now - cachedSnapshotAt <= WIKI_PRICE_REFRESH_MS) {
                return cachedSnapshot;
            }

            try {
                String latest = httpGet(LATEST_URL);
                String fiveMinute = httpGet(FIVE_MIN_URL);
                String oneHour = httpGet(ONE_HOUR_URL);
                WikiSnapshot snapshot = parseSnapshot(latest, fiveMinute, oneHour);
                if (snapshot != null) {
                    cachedSnapshot = snapshot;
                    cachedSnapshotAt = now;
                    return cachedSnapshot;
                }
            } catch (RuntimeException | IOException ignored) {
                // Client pricing remains as a safe fallback for the stable method.
            }

            if (cachedSnapshot != null && now - cachedSnapshotAt <= WIKI_PRICE_STALE_MS) {
                return cachedSnapshot;
            }
            return null;
        }

        private String httpGet(String url) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(2200);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Wiki Prices HTTP " + status);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            } finally {
                connection.disconnect();
            }
            return response.toString();
        }

        private WikiSnapshot parseSnapshot(String latest, String fiveMinute, String oneHour) {
            Map<Long, WikiItemPrice> prices = new HashMap<>();
            for (long itemId : trackedPriceIds()) {
                WikiItemPrice price = new WikiItemPrice();
                parseLatest(latest, itemId, price);
                parseAverage(fiveMinute, itemId, price, true);
                parseAverage(oneHour, itemId, price, false);
                prices.put(itemId, price);
            }
            return new WikiSnapshot(prices);
        }

        private List<Long> trackedPriceIds() {
            List<Long> itemIds = new ArrayList<>();
            itemIds.add(COSMIC_RUNE_ID);
            for (EnchantMethod method : METHODS) {
                itemIds.add(method.inputPriceId);
                itemIds.add(method.outputPriceId);
            }
            return itemIds;
        }

        private void parseLatest(String json, long itemId, WikiItemPrice price) {
            String body = itemObject(json, itemId);
            if (body.isBlank()) {
                return;
            }
            price.latestHigh = number(body, "high");
            price.latestLow = number(body, "low");
        }

        private void parseAverage(String json, long itemId, WikiItemPrice price, boolean fiveMinute) {
            String body = itemObject(json, itemId);
            if (body.isBlank()) {
                return;
            }
            if (fiveMinute) {
                price.avgHigh5m = number(body, "avgHighPrice");
                price.avgLow5m = number(body, "avgLowPrice");
                price.highVolume5m = number(body, "highPriceVolume");
                price.lowVolume5m = number(body, "lowPriceVolume");
            } else {
                price.avgHigh1h = number(body, "avgHighPrice");
                price.avgLow1h = number(body, "avgLowPrice");
                price.highVolume1h = number(body, "highPriceVolume");
                price.lowVolume1h = number(body, "lowPriceVolume");
            }
        }

        private String itemObject(String json, long itemId) {
            if (json == null || json.isBlank()) {
                return "";
            }
            Matcher matcher = Pattern.compile("\"" + itemId + "\"\\s*:\\s*\\{([^}]*)}").matcher(json);
            return matcher.find() ? matcher.group(1) : "";
        }

        private int number(String body, String key) {
            Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(body);
            if (!matcher.find()) {
                return 0;
            }
            try {
                return clampToInt(Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private int firstPositive(long... values) {
            for (long value : values) {
                if (value > 0) {
                    return clampToInt(value);
                }
            }
            return 0;
        }

        private Long priceIdForName(String itemName) {
            if (namesMatch(itemName, COSMIC_RUNE)) {
                return COSMIC_RUNE_ID;
            }
            for (EnchantMethod method : METHODS) {
                if (namesMatch(itemName, method.inputItem)) {
                    return method.inputPriceId;
                }
                if (namesMatch(itemName, method.outputItem)) {
                    return method.outputPriceId;
                }
            }
            return null;
        }
    }

    private static class WikiSnapshot {
        private final Map<Long, WikiItemPrice> prices;

        private WikiSnapshot(Map<Long, WikiItemPrice> prices) {
            this.prices = prices;
        }

        private WikiItemPrice item(long itemId) {
            return prices.get(itemId);
        }
    }

    private static class WikiItemPrice {
        private int latestHigh;
        private int latestLow;
        private int avgHigh5m;
        private int avgLow5m;
        private int highVolume5m;
        private int lowVolume5m;
        private int avgHigh1h;
        private int avgLow1h;
        private int highVolume1h;
        private int lowVolume1h;
    }

    private static class WikiQuote {
        private final int inputBuyPrice;
        private final int cosmicBuyPrice;
        private final int outputSellPrice;
        private final int inputVolume1h;
        private final int outputVolume1h;
        private final int inputVolume5m;
        private final int outputVolume5m;

        private WikiQuote(
                int inputBuyPrice,
                int cosmicBuyPrice,
                int outputSellPrice,
                int inputVolume1h,
                int outputVolume1h,
                int inputVolume5m,
                int outputVolume5m
        ) {
            this.inputBuyPrice = inputBuyPrice;
            this.cosmicBuyPrice = cosmicBuyPrice;
            this.outputSellPrice = outputSellPrice;
            this.inputVolume1h = inputVolume1h;
            this.outputVolume1h = outputVolume1h;
            this.inputVolume5m = inputVolume5m;
            this.outputVolume5m = outputVolume5m;
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
        private final int inputVolume1h;
        private final int outputVolume1h;
        private final int inputVolume5m;
        private final int outputVolume5m;
        private final String priceSource;

        private Quote(
                EnchantMethod method,
                int inputBuyPrice,
                int cosmicBuyPrice,
                int outputSellPrice,
                long costPerCast,
                long profitPerCast,
                long profitPerHour,
                int inputVolume1h,
                int outputVolume1h,
                int inputVolume5m,
                int outputVolume5m,
                String priceSource
        ) {
            this.method = method;
            this.inputBuyPrice = inputBuyPrice;
            this.cosmicBuyPrice = cosmicBuyPrice;
            this.outputSellPrice = outputSellPrice;
            this.costPerCast = costPerCast;
            this.profitPerCast = profitPerCast;
            this.profitPerHour = profitPerHour;
            this.inputVolume1h = inputVolume1h;
            this.outputVolume1h = outputVolume1h;
            this.inputVolume5m = inputVolume5m;
            this.outputVolume5m = outputVolume5m;
            this.priceSource = priceSource == null ? "-" : priceSource;
        }

        private boolean hasPrices() {
            return profitPerCast != Long.MIN_VALUE;
        }

        private boolean profitable() {
            return hasPrices() && profitPerCast > 0;
        }

        private boolean hasWikiVolume() {
            return "wiki".equals(priceSource);
        }

        private boolean passesLiquidityFilter() {
            if (!hasWikiVolume()) {
                return method.allowClientPriceFallback;
            }
            if (method.allowClientPriceFallback) {
                return true;
            }
            if (inputVolume1h < method.minInputVolume1h || outputVolume1h < method.minOutputVolume1h) {
                return false;
            }
            return inputVolume5m > 0 || outputVolume5m > 0 || method.allowClientPriceFallback;
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
