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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

@ScriptManifest(name = "Enchant Jewellery Profit", gameType = GameType.OS)
public class EnchantJewelleryProfitScript extends Script {
    private static final String SCRIPT_VERSION = "v0.1.24-force-21815-confirm-spell";
    private static final Tile GRAND_EXCHANGE_TILE = new Tile(3164, 3487, 0);
    private static final int FIXED_CANVAS_WIDTH = 765;
    private static final int FIXED_CANVAS_HEIGHT = 503;
    private static final int FIXED_INVENTORY_GRID_X = 563;
    private static final int FIXED_INVENTORY_GRID_Y = 213;
    private static final int FIXED_INVENTORY_GRID_WIDTH = 174;
    private static final int FIXED_INVENTORY_GRID_HEIGHT = 252;
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
    private static final int SPELLBOOK_GROUP = 218;
    private static final int JEWELLERY_ENCHANTMENTS_CHILD = 15;
    private static final int LEVEL_1_ENCHANT_CHILD = 16;
    private static final long METHOD_REFRESH_MS = 4 * 60_000L;
    private static final long TRACE_THROTTLE_MS = 2_500L;
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
    private boolean enchantInventoryCycleActive;
    private EnchantMethod enchantCycleMethod;
    private int enchantCycleLastInputCount;
    private int enchantCycleLastOutputCount;
    private long enchantCycleLastProgressAt;
    private long nextMethodRefreshAt;
    private long nextGeCollectAt;
    private long nextIdleLogAt;
    private long nextRowTeleportAttemptAt;
    private long nextTraceAt;
    private String lastTraceSignature = "";
    private long lastSpellWidgetClickAt;
    private int consecutiveSpellSelectionFailures;
    private boolean forceSpellSelectionForNextInventory;
    private EnchantPhase enchantPhase = EnchantPhase.IDLE;
    private boolean wrongSpellDetected;
    private boolean stoppedForNoProfit;

    @Override
    public boolean onStart(String... args) {
        stats = new Stats();
        addTask(new EnchantTask());
        log("Enchant Jewellery Profit " + SCRIPT_VERSION + " started");
        getLogger().info("[Trace] enabled version=" + SCRIPT_VERSION);
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
        if (lower.contains("you need a magic level")
                && lower.contains("to cast this spell")) {
            wrongSpellDetected = true;
            forceSpellSelectionForNextInventory = true;
            lastSpellWidgetClickAt = 0L;
            setEnchantPhase(EnchantPhase.RECOVERING);
            stats.setStatus("Wrong spell selected; resetting enchant flow");
            getLogger().info("[Trace] wrong-spell-chat phase=" + enchantPhase.label
                    + " message='" + message + "'");
        }
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
        paint.drawText("Method: " + (activeMethod == null ? "-" : activeMethod.label)
                + " | Phase: " + enchantPhase.label, x + 12, line, new Color(220, 235, 255), 12);
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
        setEnchantPhase(EnchantPhase.IDLE);
        clearClientInteractionState();
        getLogger().info("Enchant Jewellery Profit " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        resetEnchantCycle();
        setEnchantPhase(EnchantPhase.IDLE);
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

            if (activeMethod != null && equipmentRingMatches(ctx, activeMethod.inputItem)) {
                trace(ctx, activeMethod, "run:recover-equipped-material-before-anything");
                recoverAccidentalRingEquip(ctx, activeMethod);
                return;
            }

            if (enchantInventoryCycleActive && activeMethod != null) {
                trace(ctx, activeMethod, "run:continue-active-cycle");
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
                trace(ctx, readyMethod, "run:ready-inventory-before-ge");
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
        setEnchantPhase(EnchantPhase.BANKING);
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

    private boolean recoverAccidentalRingEquip(APIContext ctx, EnchantMethod method) {
        ItemWidget equippedRing = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        if (equippedRing == null || equippedRing.getName() == null) {
            return false;
        }

        if (!namesMatch(equippedRing.getName(), method.inputItem)) {
            return false;
        }

        setEnchantPhase(EnchantPhase.RECOVERING);
        forceSpellSelectionForNextInventory = true;
        lastSpellWidgetClickAt = 0L;
        trace(ctx, method, "recover:ring-slot-has-material");

        ItemWidget row = inventoryChargedRingOfWealth(ctx);
        if (row != null) {
            stats.setStatus("Recovering ring slot: re-equipping Ring of wealth");
            String rowName = row.getName();
            trace(ctx, method, "recover:wear-row-from-inventory:" + rowName);
            if (row.interact("Wear")
                    || row.interact("Wear", rowName)
                    || ctx.inventory().interactItem("Wear", rowName)) {
                Time.sleep(700, 1200,
                        () -> !equipmentRingMatches(ctx, method.inputItem)
                                && ctx.inventory().contains(method.inputItem),
                        100);
                return true;
            }
        }

        if (ctx.inventory().isFull()) {
            if (!ctx.bank().isOpen()) {
                stats.setStatus("Recovering ring slot: opening bank for space");
                trace(ctx, method, "recover:inventory-full-open-bank");
                openBank(ctx, "recovering accidental Sapphire ring equip");
                return true;
            }

            stats.setStatus("Recovering ring slot: depositing 1 " + method.inputItem);
            trace(ctx, method, "recover:deposit-one-material-for-space");
            ctx.bank().deposit(1, method.inputItem);
            Time.sleep(600, 1000, () -> !ctx.inventory().isFull(), 100);
            return true;
        }

        if (!ctx.inventory().isFull()) {
            stats.setStatus("Recovering ring slot: removing " + equippedRing.getName());
            trace(ctx, method, "recover:remove-equipped-material");
            if (equippedRing.interact("Remove") || equippedRing.interact("Remove", equippedRing.getName())) {
                Time.sleep(700, 1200,
                        () -> !equipmentRingMatches(ctx, method.inputItem)
                                && ctx.inventory().contains(method.inputItem),
                        100);
                return true;
            }
        }

        stats.setStatus("Recover ring slot failed; retrying");
        trace(ctx, method, "recover:failed-retrying");
        Time.sleep(1200, 1800);
        return true;
    }

    private ItemWidget inventoryChargedRingOfWealth(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (isChargedRingOfWealth(item)) {
                return item;
            }
        }
        return null;
    }

    private boolean equipmentRingMatches(APIContext ctx, String itemName) {
        ItemWidget ring = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        return ring != null && ring.getName() != null && namesMatch(ring.getName(), itemName);
    }

    private void enchantInventory(APIContext ctx, EnchantMethod method) {
        if (ctx.bank().isOpen()) {
            closeBank(ctx, "Ready to enchant " + method.label);
            return;
        }

        if (recoverAccidentalRingEquip(ctx, method)) {
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
        wrongSpellDetected = false;
        setEnchantPhase(EnchantPhase.CASTING);
        stats.setStatus("Casting " + method.spell.getSpellName() + " on " + method.inputItem);
        trace(ctx, method, "enchant:start");
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
            trace(ctx, method, "enchant:converted=" + converted);
        }

        int currentInput = ctx.inventory().getCount(method.inputItem);
        int currentOutput = ctx.inventory().getCount(method.outputItem);
        if (cast || converted > 0 || ctx.localPlayer().isAnimating()) {
            startEnchantCycle(method, currentInput, currentOutput);
            setEnchantPhase(EnchantPhase.PROCESSING);
            stats.setStatus("Enchanting inventory: " + currentInput + " " + method.inputItem + " left");
            trace(ctx, method, "enchant:cycle-start");
            return;
        }

        if (!cast) {
            stats.setStatus("Enchant cast did not start for " + method.label);
            trace(ctx, method, "enchant:cast-did-not-start");
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
            setEnchantPhase(EnchantPhase.BANKING);
            trace(ctx, method, "cycle:inventory-finished");
            Time.sleep(350, 650);
            return true;
        }

        long idleMs = System.currentTimeMillis() - enchantCycleLastProgressAt;
        if (idleMs > ENCHANT_BATCH_STALL_MS && !ctx.localPlayer().isAnimating()) {
            stats.setStatus("Enchant batch stalled; restarting " + method.label);
            trace(ctx, method, "cycle:stalled-reset");
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
        if (enchantPhase == EnchantPhase.PROCESSING) {
            setEnchantPhase(EnchantPhase.IDLE);
        }
    }

    private void setEnchantPhase(EnchantPhase phase) {
        enchantPhase = phase == null ? EnchantPhase.IDLE : phase;
    }

    private boolean selectSpellAndClickItem(APIContext ctx, EnchantMethod method) {
        if (magicLevel(ctx) < method.requiredMagic) {
            stats.setStatus("Magic level " + method.requiredMagic + " required for " + method.spell.getSpellName());
            trace(ctx, method, "cast:level-too-low");
            return false;
        }

        if (ctx.magic().isSpellSelected()) {
            setEnchantPhase(EnchantPhase.CLICKING_MATERIAL);
            trace(ctx, method, "cast:preselected-spell-click-material");
            if (!openInventoryTab(ctx)) {
                trace(ctx, method, "cast:preselected-open-inventory-failed");
                return false;
            }
            humanItemPause();
            return clickEnchantMaterial(ctx, method);
        }

        if (!selectSpellByPrimitiveWidgetClick(ctx, method) || wrongSpellDetected) {
            trace(ctx, method, "cast:primitive-widget-select-failed wrongSpell=" + wrongSpellDetected);
            return false;
        }

        if (!openInventoryTab(ctx)) {
            trace(ctx, method, "cast:primitive-open-inventory-failed");
            return false;
        }
        humanItemPause();
        setEnchantPhase(EnchantPhase.CLICKING_MATERIAL);
        trace(ctx, method, "cast:primitive-click-material-after-218-" + method.spellWidgetChild);
        return clickEnchantMaterial(ctx, method);
    }

    private boolean selectSpellByPrimitiveWidgetClick(APIContext ctx, EnchantMethod method) {
        if (ctx.inventory().getCount(true, method.inputItem) <= 0
                || ctx.inventory().getCount(true, COSMIC_RUNE) <= 0) {
            stats.setStatus("Missing enchant inventory before spell selection");
            trace(ctx, method, "spell:missing-inventory-before-widget");
            return false;
        }

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY) && !openInventoryTab(ctx)) {
            return failSpellSelection(ctx, method, "spell:open-inventory-before-panel-failed");
        }
        Rectangle inventoryPanel = inventoryGridBounds(ctx);
        trace(ctx, method, "spell:inventory-panel-before-magic panel=" + rectangleText(inventoryPanel));

        setEnchantPhase(EnchantPhase.SELECTING_SPELL);
        if (!openMagicTab(ctx)) {
            return failSpellSelection(ctx, method, "spell:open-magic-tab-failed");
        }

        stats.setStatus("Opening Jewellery Enchantments via primitive 218." + JEWELLERY_ENCHANTMENTS_CHILD);
        if (!clickSpellbookWidgetPrimitive(ctx, JEWELLERY_ENCHANTMENTS_CHILD, "Jewellery Enchantments", inventoryPanel)) {
            return failSpellSelection(ctx, method, "spell:open-jewellery-primitive-click-failed");
        }
        Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS,
                () -> isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild)),
                100);

        WidgetChild spellWidget = ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild);
        if (!isVisibleWidget(spellWidget)) {
            stats.setStatus("Enchant widget missing after opening: 218." + method.spellWidgetChild);
            return failSpellSelection(ctx, method, "spell:target-widget-missing-after-open");
        }

        stats.setStatus("Selecting " + method.spell.getSpellName() + " via primitive 218." + method.spellWidgetChild);
        boolean clicked = clickSpellbookWidgetPrimitive(ctx, method.spellWidgetChild, method.spell.getSpellName(), inventoryPanel);
        Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS,
                () -> ctx.magic().isSpellSelected() || wrongSpellDetected,
                100);

        if (wrongSpellDetected) {
            forceSpellSelectionForNextInventory = true;
            lastSpellWidgetClickAt = 0L;
            return failSpellSelection(ctx, method, "spell:wrong-spell-after-primitive-click");
        }

        if (ctx.magic().isSpellSelected()) {
            lastSpellWidgetClickAt = System.currentTimeMillis();
            forceSpellSelectionForNextInventory = false;
            consecutiveSpellSelectionFailures = 0;
            trace(ctx, method, "spell:selected-after-primitive-click clicked=" + clicked);
            return true;
        }

        forceSpellSelectionForNextInventory = true;
        lastSpellWidgetClickAt = 0L;
        return failSpellSelection(ctx, method, "spell:not-selected-after-primitive-click clicked=" + clicked);
    }

    private boolean clickEnchantMaterial(APIContext ctx, EnchantMethod method) {
        ItemWidget item = ctx.inventory().getItem(method.inputItem);
        if (item == null) {
            stats.setStatus("Missing material in inventory: " + method.inputItem);
            return false;
        }

        stats.setStatus("Clicking material after spell: " + method.inputItem);
        humanItemPause();
        long millisSinceSpellClick = System.currentTimeMillis() - lastSpellWidgetClickAt;
        boolean directClickExpected = ctx.magic().isSpellSelected()
                || (lastSpellWidgetClickAt > 0L && millisSinceSpellClick < 8_000L);
        if (!directClickExpected) {
            stats.setStatus("No recent Lvl-1 Enchant click; refusing material click");
            forceSpellSelectionForNextInventory = true;
            lastSpellWidgetClickAt = 0L;
            trace(ctx, method, "item:refused-no-recent-spell-click");
            return false;
        }

        int inputBefore = ctx.inventory().getCount(true, method.inputItem);
        int outputBefore = ctx.inventory().getCount(true, method.outputItem);
        boolean clicked;
        if (ctx.magic().isSpellSelected()) {
            clicked = clickInventoryItemByMouse(ctx, item) || item.click(false);
        } else {
            clicked = ctx.menu().interact("Cast", method.inputItem, item, false)
                    || ctx.menu().interact("Cast", item, false)
                    || item.interact("Cast");
        }
        trace(ctx, method, "item:clicked=" + clicked
                + " apiSpellSelected=" + ctx.magic().isSpellSelected()
                + " sinceSpellMs=" + millisSinceSpellClick
                + " inputBefore=" + inputBefore
                + " outputBefore=" + outputBefore);

        Time.sleep(HUMAN_ITEM_MIN_MS, HUMAN_ITEM_MAX_MS,
                () -> ctx.localPlayer().isAnimating()
                        || ctx.inventory().getCount(true, method.outputItem) > outputBefore
                        || ctx.inventory().getCount(true, method.inputItem) < inputBefore
                        || wrongSpellDetected,
                100);
        trace(ctx, method, "item:after-click inputNow=" + ctx.inventory().getCount(true, method.inputItem)
                + " outputNow=" + ctx.inventory().getCount(true, method.outputItem)
                + " animating=" + ctx.localPlayer().isAnimating());
        return clicked;
    }

    private boolean clickSpellbookWidgetPrimitive(APIContext ctx, int child, String label, Rectangle inventoryPanel) {
        WidgetChild widget = ctx.widgets().get(SPELLBOOK_GROUP, child);
        if (!isVisibleWidget(widget)) {
            return false;
        }

        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            trace(ctx, activeMethod, "primitive-widget:invalid-bounds child=" + child + " label=" + label);
            return false;
        }

        Point rawPoint = randomPointInside(bounds, 6);
        if (rawPoint == null) {
            trace(ctx, activeMethod, "primitive-widget:no-point child=" + child + " label=" + label);
            return false;
        }
        Point point = translateFixedSpellbookPoint(ctx, rawPoint, inventoryPanel);
        if (!isSpellbookPanelPoint(ctx, point, inventoryPanel)) {
            trace(ctx, activeMethod, "primitive-widget:refused-outside-spellbook-panel child=" + child
                    + " label='" + label + "'"
                    + " raw=" + rawPoint.x + "," + rawPoint.y
                    + " translated=" + point.x + "," + point.y
                    + " panel=" + rectangleText(inventoryPanel)
                    + " canvas=" + ctx.client().getCanvasWidth() + "x" + ctx.client().getCanvasHeight());
            return false;
        }

        trace(ctx, activeMethod, "primitive-widget:before-move child=" + child
                + " label='" + label + "'"
                + " rawPoint=" + rawPoint.x + "," + rawPoint.y
                + " translatedPoint=" + point.x + "," + point.y
                + " bounds=" + bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height
                + " panel=" + rectangleText(inventoryPanel)
                + " canvas=" + ctx.client().getCanvasWidth() + "x" + ctx.client().getCanvasHeight());
        if (!ctx.mouse().move(point)) {
            trace(ctx, activeMethod, "primitive-widget:move-failed child=" + child + " label=" + label);
            return false;
        }
        Time.sleep(450, 950);

        trace(ctx, activeMethod, "primitive-widget:before-current-click child=" + child + " label=" + label);
        boolean clicked = ctx.mouse().click(false);
        trace(ctx, activeMethod, "primitive-widget:after-current-click child=" + child
                + " label=" + label
                + " clicked=" + clicked
                + " mouse=" + ctx.mouse().getX() + "," + ctx.mouse().getY());
        return clicked;
    }

    private Point translateFixedSpellbookPoint(APIContext ctx, Point rawPoint, Rectangle inventoryPanel) {
        int canvasWidth = Math.max(1, ctx.client().getCanvasWidth());
        int canvasHeight = Math.max(1, ctx.client().getCanvasHeight());
        int x = rawPoint.x;
        int y = rawPoint.y;

        if (inventoryPanel != null
                && rawPoint.x <= FIXED_CANVAS_WIDTH
                && rawPoint.y <= FIXED_CANVAS_HEIGHT) {
            double xRatio = (double) (rawPoint.x - FIXED_INVENTORY_GRID_X) / FIXED_INVENTORY_GRID_WIDTH;
            double yRatio = (double) (rawPoint.y - FIXED_INVENTORY_GRID_Y) / FIXED_INVENTORY_GRID_HEIGHT;
            x = inventoryPanel.x + (int) Math.round(xRatio * inventoryPanel.width);
            y = inventoryPanel.y + (int) Math.round(yRatio * inventoryPanel.height);
        } else if (canvasWidth > FIXED_CANVAS_WIDTH + 100
                && canvasHeight > FIXED_CANVAS_HEIGHT + 100
                && rawPoint.x <= FIXED_CANVAS_WIDTH
                && rawPoint.y <= FIXED_CANVAS_HEIGHT) {
            x += canvasWidth - FIXED_CANVAS_WIDTH;
            y += canvasHeight - FIXED_CANVAS_HEIGHT;
        }

        x = Math.max(0, Math.min(canvasWidth - 1, x));
        y = Math.max(0, Math.min(canvasHeight - 1, y));
        return new Point(x, y);
    }

    private boolean isSpellbookPanelPoint(APIContext ctx, Point point, Rectangle inventoryPanel) {
        if (point == null) {
            return false;
        }
        int canvasWidth = Math.max(1, ctx.client().getCanvasWidth());
        int canvasHeight = Math.max(1, ctx.client().getCanvasHeight());

        if (inventoryPanel != null) {
            int left = Math.max(0, inventoryPanel.x - 36);
            int top = Math.max(0, inventoryPanel.y - 140);
            int right = Math.min(canvasWidth, inventoryPanel.x + inventoryPanel.width + 100);
            int bottom = Math.min(canvasHeight, inventoryPanel.y + inventoryPanel.height + 90);
            return point.x >= left && point.x < right && point.y >= top && point.y < bottom;
        }

        if (canvasWidth <= FIXED_CANVAS_WIDTH + 100 || canvasHeight <= FIXED_CANVAS_HEIGHT + 100) {
            return point.x >= 520
                    && point.x < canvasWidth
                    && point.y >= 160
                    && point.y < canvasHeight;
        }

        int panelLeft = Math.max(0, canvasWidth - 280);
        int panelTop = Math.max(0, canvasHeight - 380);
        return point.x >= panelLeft
                && point.x < canvasWidth
                && point.y >= panelTop
                && point.y < canvasHeight;
    }

    private boolean failSpellSelection(APIContext ctx, EnchantMethod method, String event) {
        consecutiveSpellSelectionFailures++;
        forceSpellSelectionForNextInventory = true;
        lastSpellWidgetClickAt = 0L;
        trace(ctx, method, event + " failures=" + consecutiveSpellSelectionFailures);
        if (consecutiveSpellSelectionFailures >= 3) {
            String reason = "Enchant spell selection failed 3x; paused to avoid loop";
            stats.setStatus(reason);
            trace(ctx, method, "spell:paused-after-selection-failures");
            ctx.script().pause(reason);
        }
        return false;
    }

    private Rectangle inventoryGridBounds(APIContext ctx) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int count = 0;

        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null) {
                continue;
            }
            Rectangle bounds = item.getBounds();
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                continue;
            }
            minX = Math.min(minX, bounds.x);
            minY = Math.min(minY, bounds.y);
            maxX = Math.max(maxX, bounds.x + bounds.width);
            maxY = Math.max(maxY, bounds.y + bounds.height);
            count++;
        }

        if (count < 4) {
            return null;
        }
        return new Rectangle(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    private String rectangleText(Rectangle rectangle) {
        if (rectangle == null) {
            return "null";
        }
        return rectangle.x + "," + rectangle.y + "," + rectangle.width + "," + rectangle.height;
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

        return clickWidgetByMouse(ctx, widget)
                || clickWidgetCenter(ctx, widget)
                || clickWidgetByRandomPoint(ctx, widget)
                || widget.click();
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
        setEnchantPhase(EnchantPhase.GRAND_EXCHANGE);
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

    private boolean clickWidgetByMouse(APIContext ctx, WidgetChild widget) {
        if (ctx == null || !isVisibleWidget(widget)) {
            return false;
        }
        try {
            return ctx.mouse().click(widget, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean clickWidgetByRandomPoint(APIContext ctx, WidgetChild widget) {
        if (ctx == null || !isVisibleWidget(widget)) {
            return false;
        }
        try {
            Rectangle bounds = widget.getBounds();
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                return false;
            }
            Point point = randomPointInside(bounds, 4);
            return point != null && ctx.mouse().click(point, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean clickInventoryItemByMouse(APIContext ctx, ItemWidget item) {
        if (item == null) {
            return false;
        }
        Rectangle bounds = item.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return false;
        }
        Point point = randomPointInside(bounds, 5);
        return ctx.mouse().click(point, false);
    }

    private Point randomPointInside(Rectangle bounds, int margin) {
        int marginX = Math.min(Math.max(0, margin), Math.max(0, bounds.width / 3));
        int marginY = Math.min(Math.max(0, margin), Math.max(0, bounds.height / 3));
        int left = bounds.x + marginX;
        int right = bounds.x + bounds.width - marginX - 1;
        int top = bounds.y + marginY;
        int bottom = bounds.y + bounds.height - marginY - 1;

        if (right < left) {
            left = bounds.x + bounds.width / 2;
            right = left;
        }
        if (bottom < top) {
            top = bounds.y + bounds.height / 2;
            bottom = top;
        }

        int x = left == right ? left : ThreadLocalRandom.current().nextInt(left, right + 1);
        int y = top == bottom ? top : ThreadLocalRandom.current().nextInt(top, bottom + 1);
        return new Point(x, y);
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

    private void trace(APIContext ctx, EnchantMethod method, String event) {
        long now = System.currentTimeMillis();
        String signature = enchantPhase.label + "|" + event;
        if (signature.equals(lastTraceSignature) && now < nextTraceAt) {
            return;
        }
        lastTraceSignature = signature;
        nextTraceAt = now + TRACE_THROTTLE_MS;

        try {
            String methodKey = method == null ? "-" : method.key;
            String input = method == null ? "-" : method.inputItem;
            String output = method == null ? "-" : method.outputItem;
            String equippedRing = "-";
            ItemWidget ring = ctx == null ? null : ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
            if (ring != null && ring.getName() != null) {
                equippedRing = ring.getName();
            }

            getLogger().info("[Trace] event=" + event
                    + " phase=" + enchantPhase.label
                    + " method=" + methodKey
                    + " status='" + (stats == null ? "-" : stats.status) + "'"
                    + " loc=" + locationText(ctx)
                    + " invInput=" + countInventory(ctx, input)
                    + " invOutput=" + countInventory(ctx, output)
                    + " cosmics=" + countInventory(ctx, COSMIC_RUNE)
                    + " invFull=" + (ctx != null && ctx.inventory().isFull())
                    + " bankOpen=" + (ctx != null && ctx.bank().isOpen())
                    + " geOpen=" + (ctx != null && ctx.grandExchange().isOpen())
                    + " spellSelected=" + (ctx != null && ctx.magic().isSpellSelected())
                    + " itemSelected=" + (ctx != null && ctx.inventory().isItemSelected())
                    + " equippedRing='" + equippedRing + "'"
                    + " forceSpell=" + forceSpellSelectionForNextInventory
                    + " wrongSpell=" + wrongSpellDetected);
        } catch (RuntimeException ex) {
            getLogger().info("[Trace] event=" + event
                    + " phase=" + enchantPhase.label
                    + " traceError=" + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
        }
    }

    private int countInventory(APIContext ctx, String itemName) {
        if (ctx == null || itemName == null || itemName.isBlank() || "-".equals(itemName)) {
            return 0;
        }
        try {
            return ctx.inventory().getCount(true, itemName);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private String locationText(APIContext ctx) {
        if (ctx == null) {
            return "ctx-null";
        }
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

    private enum EnchantPhase {
        IDLE("idle"),
        BANKING("banking"),
        GRAND_EXCHANGE("ge"),
        CASTING("casting"),
        SELECTING_SPELL("spell"),
        CLICKING_MATERIAL("item"),
        PROCESSING("processing"),
        RECOVERING("recover");

        private final String label;

        EnchantPhase(String label) {
            this.label = label;
        }
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
