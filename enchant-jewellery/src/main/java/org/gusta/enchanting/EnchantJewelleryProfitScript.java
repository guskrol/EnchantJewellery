package org.gusta.enchanting;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.gameval.VarPlayerID;
import com.epicbot.api.gameval.VarbitID;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

@ScriptManifest(name = "Enchant Jewellery Profit", gameType = GameType.OS)
public class EnchantJewelleryProfitScript extends Script {
    private static final String SCRIPT_VERSION = "v0.1.8-safe-magic-widget-clicks";
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
    private static final long WATCHDOG_WARMUP_MS = 90_000L;
    private static final long WATCHDOG_IDLE_MIN_MS = 2 * 60_000L;
    private static final long WATCHDOG_IDLE_MAX_MS = 3 * 60_000L;
    private static final long WATCHDOG_HARD_MIN_MS = 6 * 60_000L;
    private static final long WATCHDOG_HARD_MAX_MS = 8 * 60_000L;
    private static final long WATCHDOG_INFO_LOG_MS = 60_000L;
    private static final int WATCHDOG_TILE_PROGRESS_DISTANCE = 4;
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
                    "Lvl-1 Enchant",
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
                    "Lvl-2 Enchant",
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
                    "Lvl-3 Enchant",
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
    private final Watchdog watchdog = new Watchdog();

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
    private Path liveDiagnosticPath;
    private String liveDiagnosticFileName;
    private boolean liveDiagnosticUnavailable;
    private boolean stoppedForNoProfit;

    @Override
    public boolean onStart(String... args) {
        stats = new Stats();
        resetLiveDiagnosticLog();
        watchdog.reset();
        addTask(new EnchantTask());
        appendLiveDiagnostic("START version=" + SCRIPT_VERSION
                + " cwd=" + System.getProperty("user.dir", "."));
        log("Enchant Jewellery Profit " + SCRIPT_VERSION + " started");
        logDiagnostic("Live diagnostic log=" + liveDiagnosticPathText());
        return true;
    }

    @Override
    protected void onChatMessage(ChatMessageEvent event) {
        if (event == null || event.getMessage() == null || stats == null) {
            return;
        }
        String message = event.getMessage();
        stats.lastChat = message;
        appendLiveDiagnostic("CHAT " + oneLine(message));
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
        watchdog.reset();
        clearClientInteractionState();
        appendLiveDiagnostic("STOP version=" + SCRIPT_VERSION
                + " runtime=" + (stats == null ? "-" : stats.runtimeText())
                + " status=" + (stats == null ? "-" : stats.status));
        getLogger().info("Enchant Jewellery Profit " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        resetEnchantCycle();
        watchdog.reset();
        clearClientInteractionState();
        appendLiveDiagnostic("PAUSE status=" + (stats == null ? "-" : stats.status));
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

            if (watchdog.checkAndHandle(ctx)) {
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

            if (enchantInventoryCycleActive && activeMethod != null) {
                enchantInventory(ctx, activeMethod);
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
        traceEnchant("start-inventory-enchant", ctx, method);
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
            traceEnchant("cast-did-not-start", ctx, method);
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
        traceEnchant("select-spell-and-item:start", ctx, method);
        if (!ctx.magic().isSpellSelected()) {
            if (!selectJewelleryEnchantSpell(ctx, method)) {
                traceEnchant("select-spell-and-item:spell-failed", ctx, method);
                return false;
            }
            humanWidgetPause();
        }

        if (!openInventoryTab(ctx)) {
            traceEnchant("select-spell-and-item:inventory-tab-failed", ctx, method);
            return false;
        }
        humanItemPause();

        boolean clickedMaterial = clickEnchantMaterial(ctx, method);
        traceEnchant("select-spell-and-item:material-click-result=" + clickedMaterial, ctx, method);
        return clickedMaterial;
    }

    private boolean selectJewelleryEnchantSpell(APIContext ctx, EnchantMethod method) {
        traceEnchant("select-spell:start", ctx, method);
        if (!openMagicTab(ctx)) {
            traceEnchant("select-spell:magic-tab-failed", ctx, method);
            return false;
        }

        if (!openJewelleryEnchantments(ctx, method)) {
            traceEnchant("select-spell:jewellery-menu-failed", ctx, method);
            return false;
        }

        boolean clickedSpell = clickEnchantSpellWidget(ctx, method);
        traceEnchant("select-spell:spell-widget-click-result=" + clickedSpell, ctx, method);
        return clickedSpell;
    }

    private boolean openJewelleryEnchantments(APIContext ctx, EnchantMethod method) {
        WidgetChild alreadyVisible = ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild);
        if (isVisibleWidget(alreadyVisible)) {
            logDiagnostic("Jewellery menu already open; expected enchant visible child=218."
                    + method.spellWidgetChild
                    + " widget=" + widgetSummary(alreadyVisible)
                    + " vars=" + varsSnapshot(ctx));
            return true;
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            WidgetChild jewelleryEnchantments = ctx.widgets().get(SPELLBOOK_GROUP, JEWELLERY_ENCHANTMENTS_CHILD);
            if (!isVisibleWidget(jewelleryEnchantments)) {
                stats.setStatus("Jewellery Enchantments widget missing: 218." + JEWELLERY_ENCHANTMENTS_CHILD);
                dumpSpellbookWidgets(ctx, "jewellery-menu-missing");
                return false;
            }

            stats.setStatus("Opening Jewellery Enchantments via 218." + JEWELLERY_ENCHANTMENTS_CHILD);
            logDiagnostic("Opening jewellery widget attempt=" + attempt
                    + " widget=" + widgetSummary(jewelleryEnchantments)
                    + " vars=" + varsSnapshot(ctx));
            humanWidgetPause();
            boolean opened = clickMagicWidgetByMouse(ctx, jewelleryEnchantments, "jewellery-menu-218."
                    + JEWELLERY_ENCHANTMENTS_CHILD);
            Time.sleep(
                    HUMAN_WIDGET_MIN_MS,
                    HUMAN_WIDGET_MAX_MS,
                    () -> isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild)),
                    100
            );
            logDiagnostic("Jewellery widget clicked=" + opened
                    + " expectedChild=" + method.spellWidgetChild
                    + " visible=" + isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild))
                    + " vars=" + varsSnapshot(ctx));

            if (isVisibleWidget(ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild))) {
                return true;
            }
        }

        stats.setStatus("Enchant widget missing after opening: 218." + method.spellWidgetChild);
        dumpSpellbookWidgets(ctx, "expected-enchant-child-missing");
        return false;
    }

    private boolean clickEnchantSpellWidget(APIContext ctx, EnchantMethod method) {
        WidgetChild spellWidget = ctx.widgets().get(SPELLBOOK_GROUP, method.spellWidgetChild);
        if (!isVisibleWidget(spellWidget)) {
            stats.setStatus("Enchant widget missing: 218." + method.spellWidgetChild);
            dumpSpellbookWidgets(ctx, "spell-widget-not-visible");
            return false;
        }

        if (!isExpectedEnchantWidget(method, spellWidget)) {
            stats.setStatus("Wrong enchant widget at 218." + method.spellWidgetChild
                    + "; expected " + method.expectedSpellText);
            logDiagnostic("Wrong enchant widget. expected=" + method.expectedSpellText
                    + " actual=" + widgetSummary(spellWidget)
                    + " vars=" + varsSnapshot(ctx));
            dumpSpellbookWidgets(ctx, "wrong-enchant-widget");
            return false;
        }

        stats.setStatus("Selecting " + method.spell.getSpellName() + " via 218." + method.spellWidgetChild);
        logDiagnostic("Clicking enchant widget expected=" + method.expectedSpellText
                + " widget=" + widgetSummary(spellWidget)
                + " varsBefore=" + varsSnapshot(ctx));
        humanWidgetPause();
        boolean clicked = clickMagicWidgetByMouse(ctx, spellWidget, "spell-218." + method.spellWidgetChild);
        Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS, () -> ctx.magic().isSpellSelected(), 100);

        if (!ctx.magic().isSpellSelected()) {
            humanWidgetPause();
            clicked = clickMagicWidgetByMouse(ctx, spellWidget, "spell-retry-218." + method.spellWidgetChild)
                    || clicked;
            Time.sleep(HUMAN_WIDGET_MIN_MS, HUMAN_WIDGET_MAX_MS, () -> ctx.magic().isSpellSelected(), 100);
        }

        if (!ctx.magic().isSpellSelected()) {
            stats.setStatus("Spell selection not detected; clicking material anyway");
        }
        logDiagnostic("Enchant widget click result=" + clicked
                + " spellSelected=" + ctx.magic().isSpellSelected()
                + " varsAfter=" + varsSnapshot(ctx));
        return clicked;
    }

    private boolean clickEnchantMaterial(APIContext ctx, EnchantMethod method) {
        ItemWidget item = ctx.inventory().getItem(method.inputItem);
        if (item == null) {
            stats.setStatus("Missing material in inventory: " + method.inputItem);
            logDiagnostic("Material missing before click: " + method.inputItem
                    + " inventory=" + itemSummary(ctx.inventory().getItems())
                    + " vars=" + varsSnapshot(ctx));
            return false;
        }

        stats.setStatus("Clicking material after spell: " + method.inputItem);
        logDiagnostic("Clicking material item=" + itemSummary(item)
                + " method=" + method.key
                + " spellSelected=" + ctx.magic().isSpellSelected()
                + " itemSelected=" + ctx.inventory().isItemSelected()
                + " varsBefore=" + varsSnapshot(ctx));
        humanItemPause();
        boolean clicked = ctx.menu().interact("Cast", method.inputItem, item, false)
                || ctx.menu().interact("Cast", item, false)
                || item.interact("Cast")
                || item.click(false);
        Time.sleep(HUMAN_ITEM_MIN_MS, HUMAN_ITEM_MAX_MS);
        logDiagnostic("Material click result=" + clicked
                + " inputCount=" + ctx.inventory().getCount(method.inputItem)
                + " outputCount=" + ctx.inventory().getCount(method.outputItem)
                + " animating=" + ctx.localPlayer().isAnimating()
                + " varsAfter=" + varsSnapshot(ctx));
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

    private void traceEnchant(String phase, APIContext ctx, EnchantMethod method) {
        logDiagnostic("phase=" + phase
                + " method=" + (method == null ? "-" : method.key)
                + " status='" + (stats == null ? "-" : stats.status) + "'"
                + " counts=" + enchantCounts(ctx, method)
                + " selectedSpell=" + safeBool(() -> ctx.magic().isSpellSelected())
                + " selectedItem=" + safeBool(() -> ctx.inventory().isItemSelected())
                + " tabsMagic=" + safeBool(() -> ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC))
                + " tabsInv=" + safeBool(() -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY))
                + " vars=" + varsSnapshot(ctx));
    }

    private void logDiagnostic(String message) {
        String full = "[Diag] " + message;
        if (stats != null) {
            stats.recordEvent(full);
        }
        appendLiveDiagnostic(full);
        getLogger().info(full);
    }

    private String enchantCounts(APIContext ctx, EnchantMethod method) {
        if (ctx == null || method == null) {
            return "-";
        }
        return method.inputItem + "=" + safeInt(() -> ctx.inventory().getCount(method.inputItem))
                + "," + method.outputItem + "=" + safeInt(() -> ctx.inventory().getCount(method.outputItem))
                + "," + COSMIC_RUNE + "=" + safeInt(() -> ctx.inventory().getCount(true, COSMIC_RUNE));
    }

    private boolean isExpectedEnchantWidget(EnchantMethod method, WidgetChild widget) {
        String signal = normalizedName(visibleText(widget));
        if (signal.isBlank()) {
            return true;
        }
        String expected = normalizedName(method.expectedSpellText);
        return !signal.contains("lvl")
                || !signal.contains("enchant")
                || signal.contains(expected);
    }

    private void dumpSpellbookWidgets(APIContext ctx, String reason) {
        StringBuilder summary = new StringBuilder();
        int[] children = {
                JEWELLERY_ENCHANTMENTS_CHILD,
                LEVEL_1_ENCHANT_CHILD,
                LEVEL_2_ENCHANT_CHILD,
                LEVEL_3_ENCHANT_CHILD,
                LEVEL_4_ENCHANT_CHILD
        };
        for (int child : children) {
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append("218.").append(child).append('=')
                    .append(widgetSummary(ctx.widgets().get(SPELLBOOK_GROUP, child)));
        }

        int extra = 0;
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }
            String text = normalizedName(visibleText(candidate));
            return text.contains("enchant") || text.contains("jewellery");
        })) {
            if (extra++ >= 12) {
                break;
            }
            summary.append(" | match").append(extra).append('=').append(widgetSummary(widget));
        }

        logDiagnostic("Spellbook widget dump reason=" + reason
                + " vars=" + varsSnapshot(ctx)
                + " widgets=" + shortText(summary.toString(), 2400));
    }

    private String varsSnapshot(APIContext ctx) {
        if (ctx == null) {
            return "ctx=null";
        }
        return "spellbook=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.SPELLBOOK))
                + ",sublist=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.SPELLBOOK_SUBLIST))
                + ",filterCombat=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.MAGIC_FILTER_BLOCKCOMBAT))
                + ",filterUtility=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.MAGIC_FILTER_BLOCKUTILITY))
                + ",filterLevel=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.MAGIC_FILTER_BLOCKLACKLEVEL))
                + ",filterRunes=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.MAGIC_FILTER_BLOCKLACKRUNES))
                + ",autocastSet=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.AUTOCAST_SET))
                + ",autocastSpell=" + safeInt(() -> ctx.vars().getVarbit(VarbitID.AUTOCAST_SPELL))
                + ",lastCastSpell=" + safeInt(() -> ctx.vars().getVarp(VarPlayerID.LASTCASTSPELL));
    }

    private String widgetSummary(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        String text = shortText(cleanWidgetText(visibleText(widget)), 80);
        return "{valid=" + safeBool(widget::isValid)
                + ",parent=" + safeInt(widget::getParentId)
                + ",child=" + safeInt(widget::getChildId)
                + ",idx=" + safeInt(widget::getIndex)
                + ",text='" + text + "'"
                + ",actions=" + shortText(String.valueOf(widget.getActions()), 90)
                + ",itemId=" + safeInt(widget::getItemId)
                + ",modelId=" + safeInt(widget::getModelId)
                + ",bounds=" + widget.getBounds()
                + "}";
    }

    private String itemSummary(ItemWidget item) {
        if (item == null) {
            return "null";
        }
        return "{name='" + item.getName() + "'"
                + ",id=" + safeInt(item::getId)
                + ",idx=" + safeInt(item::getIndex)
                + ",stack=" + safeInt(item::getStackSize)
                + ",noted=" + safeBool(item::isNoted)
                + ",actions=" + shortText(String.valueOf(item.getActions()), 90)
                + ",bounds=" + item.getBounds()
                + "}";
    }

    private String itemSummary(Iterable<ItemWidget> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemWidget item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            String key = item.getName() + (item.isNoted() ? " (noted)" : "");
            int amount = Math.max(1, item.getStackSize());
            counts.merge(key, amount, Integer::sum);
        }
        if (counts.isEmpty()) {
            return "empty";
        }
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return summary.toString();
    }

    private String locationText(APIContext ctx) {
        if (ctx == null) {
            return "unknown";
        }
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null) {
            return "unknown";
        }
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private int safeInt(IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (RuntimeException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private boolean safeBool(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
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
        return clickWidgetByMouse(ctx, widget, "generic-widget", false);
    }

    private boolean clickMagicWidgetByMouse(APIContext ctx, WidgetChild widget, String label) {
        return clickWidgetByMouse(ctx, widget, label, true);
    }

    private boolean clickWidgetByMouse(APIContext ctx, WidgetChild widget, String label, boolean diagnostic) {
        if (!isVisibleWidget(widget)) {
            if (diagnostic) {
                logDiagnostic("Mouse widget click skipped; not visible label=" + label);
            }
            return false;
        }

        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            if (diagnostic) {
                logDiagnostic("Mouse widget click skipped; bad bounds label=" + label
                        + " bounds=" + bounds);
            }
            return false;
        }

        Point point = randomPointInside(bounds);
        if (diagnostic) {
            logDiagnostic("Mouse widget click pending label=" + label
                    + " point=" + point
                    + " bounds=" + bounds);
        }

        boolean clicked = ctx.mouse().click(point, false);
        Time.sleep(180, 360);

        if (diagnostic) {
            logDiagnostic("Mouse widget click completed label=" + label
                    + " clicked=" + clicked
                    + " point=" + point);
        }
        return clicked;
    }

    private Point randomPointInside(Rectangle bounds) {
        int marginX = bounds.width >= 12 ? 4 : Math.max(0, bounds.width / 4);
        int marginY = bounds.height >= 12 ? 4 : Math.max(0, bounds.height / 4);
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
            stats.recordEvent("LOG " + message);
        }
        appendLiveDiagnostic("LOG " + oneLine(message));
        getLogger().info(message);
    }

    private void resetLiveDiagnosticLog() {
        liveDiagnosticPath = null;
        liveDiagnosticFileName = liveDiagnosticLogFileName();
        liveDiagnosticUnavailable = false;
    }

    private void appendLiveDiagnostic(String message) {
        if (liveDiagnosticUnavailable) {
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String line = timestamp + " " + oneLine(message) + System.lineSeparator();
        try {
            writeLiveDiagnosticLine(resolveLiveDiagnosticPath(), line);
        } catch (RuntimeException | IOException firstFailure) {
            try {
                liveDiagnosticPath = null;
                writeLiveDiagnosticLine(createLiveDiagnosticPath(fallbackLiveDiagnosticPath()), line);
            } catch (RuntimeException | IOException secondFailure) {
                liveDiagnosticUnavailable = true;
                getLogger().info("Live diagnostic log unavailable: " + secondFailure.getMessage()
                        + " (primary failure: " + firstFailure.getMessage() + ")");
            }
        }
    }

    private Path resolveLiveDiagnosticPath() throws IOException {
        if (liveDiagnosticPath != null) {
            return liveDiagnosticPath;
        }

        try {
            return createLiveDiagnosticPath(Path.of(
                    System.getProperty("user.dir", "."),
                    "live-logs",
                    liveDiagnosticFileName
            ));
        } catch (RuntimeException | IOException firstFailure) {
            return createLiveDiagnosticPath(fallbackLiveDiagnosticPath());
        }
    }

    private Path createLiveDiagnosticPath(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        liveDiagnosticPath = path;
        return path;
    }

    private void writeLiveDiagnosticLine(Path path, String line) throws IOException {
        Files.writeString(
                path,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private Path fallbackLiveDiagnosticPath() {
        return Path.of(
                System.getProperty("user.home", "."),
                "enchant-jewellery-live-logs",
                liveDiagnosticFileName
        );
    }

    private String liveDiagnosticPathText() {
        if (liveDiagnosticPath != null) {
            return liveDiagnosticPath.toString();
        }
        return liveDiagnosticUnavailable ? "unavailable" : "pending";
    }

    private String liveDiagnosticLogFileName() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "enchant-jewellery-live-" + timestamp + ".txt";
    }

    private String oneLine(String value) {
        return value == null
                ? "-"
                : value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
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

    private class Watchdog {
        private long startedAt;
        private WatchdogSnapshot lastProgressSnapshot;
        private String lastState = "";
        private Tile lastStableTile;
        private long lastProgressAt;
        private long sameStateSince;
        private long sameTileSince;
        private long nextInfoLogAt;
        private long idleStuckMs;
        private long hardStuckMs;
        private boolean triggered;

        private Watchdog() {
            reset();
        }

        private void reset() {
            startedAt = System.currentTimeMillis();
            lastProgressSnapshot = null;
            lastState = "";
            lastStableTile = null;
            long now = System.currentTimeMillis();
            lastProgressAt = now;
            sameStateSince = now;
            sameTileSince = now;
            nextInfoLogAt = now + WATCHDOG_INFO_LOG_MS;
            triggered = false;
            resetThresholds();
        }

        private boolean checkAndHandle(APIContext ctx) {
            if (ctx == null || triggered || safeBool(() -> ctx.script().isStopping())) {
                return triggered;
            }

            if (!safeBool(() -> ctx.client().isLoggedIn())) {
                trigger(ctx, "Watchdog stop: client is already logged out while script is active", false);
                return true;
            }

            long now = System.currentTimeMillis();
            WatchdogSnapshot current = captureWatchdogSnapshot(ctx);
            if (lastProgressSnapshot == null || current.hasMaterialProgressSince(lastProgressSnapshot)) {
                initialize(now, current);
                return false;
            }

            updateStableState(ctx, now, current);
            if (now - startedAt < WATCHDOG_WARMUP_MS) {
                return false;
            }

            long noProgressFor = now - lastProgressAt;
            long sameTileFor = now - sameTileSince;
            long sameStateFor = now - sameStateSince;
            boolean active = safeBool(() -> ctx.localPlayer().isMoving())
                    || safeBool(() -> ctx.localPlayer().isAnimating())
                    || safeBool(() -> ctx.localPlayer().isInCombat())
                    || safeBool(() -> ctx.localPlayer().isAttacking());
            boolean interfaceStuck = safeBool(() -> ctx.bank().isOpen())
                    || safeBool(() -> ctx.grandExchange().isOpen())
                    || safeBool(() -> ctx.menu().isOpen())
                    || safeBool(() -> ctx.dialogues().isDialogueOpen())
                    || safeBool(() -> ctx.inventory().isItemSelected())
                    || safeBool(() -> ctx.widgets().isInterfaceOpen());

            if (!active
                    && noProgressFor >= idleStuckMs
                    && sameTileFor >= idleStuckMs
                    && sameStateFor >= idleStuckMs / 2) {
                trigger(ctx, "Watchdog logout: idle without material progress for "
                        + minutes(noProgressFor)
                        + " min; state=" + current.stateSignature, true);
                return true;
            }

            if (noProgressFor >= hardStuckMs
                    && sameTileFor >= hardStuckMs / 2
                    && (!active || interfaceStuck || sameStateFor >= idleStuckMs)) {
                trigger(ctx, "Watchdog logout: no material progress for "
                        + minutes(noProgressFor)
                        + " min; active=" + active
                        + " interfaceStuck=" + interfaceStuck
                        + " state=" + current.stateSignature, true);
                return true;
            }

            if (noProgressFor >= 90_000L && now >= nextInfoLogAt) {
                logDiagnostic("Watchdog observing no progress for " + minutes(noProgressFor)
                        + " min; sameTile=" + minutes(sameTileFor)
                        + " min; sameState=" + minutes(sameStateFor)
                        + " min; state=" + current.stateSignature);
                nextInfoLogAt = now + WATCHDOG_INFO_LOG_MS;
            }

            return false;
        }

        private void initialize(long now, WatchdogSnapshot snapshot) {
            lastProgressSnapshot = snapshot;
            lastState = snapshot.stateSignature;
            lastStableTile = snapshot.tile;
            lastProgressAt = now;
            sameStateSince = now;
            sameTileSince = now;
            nextInfoLogAt = now + WATCHDOG_INFO_LOG_MS;
            resetThresholds();
        }

        private void updateStableState(APIContext ctx, long now, WatchdogSnapshot snapshot) {
            if (!snapshot.stateSignature.equals(lastState)) {
                lastState = snapshot.stateSignature;
                sameStateSince = now;
            }

            Tile currentTile = safeTile(ctx);
            if (lastStableTile == null
                    || currentTile == null
                    || tileDistance(lastStableTile, currentTile) >= WATCHDOG_TILE_PROGRESS_DISTANCE) {
                lastStableTile = currentTile;
                sameTileSince = now;
            }
        }

        private void trigger(APIContext ctx, String reason, boolean requestLogout) {
            triggered = true;
            stats.setStatus(reason);
            logDiagnostic(reason);
            Path report = saveWatchdogReport(ctx, reason, requestLogout ? "before-logout" : "stop-only");
            clearBlockingInterfacesForWatchdog(ctx);

            boolean logoutRequested = false;
            if (requestLogout && safeBool(() -> ctx.client().isLoggedIn())) {
                try {
                    logoutRequested = ctx.game().logout();
                    Time.sleep(1200, 2200, () -> !ctx.client().isLoggedIn(), 100);
                } catch (RuntimeException e) {
                    logDiagnostic("Watchdog logout failed: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }

            appendWatchdogReportFooter(report, logoutRequested);
            ctx.script().stop(reason);
        }

        private void resetThresholds() {
            idleStuckMs = randomLong(WATCHDOG_IDLE_MIN_MS, WATCHDOG_IDLE_MAX_MS);
            hardStuckMs = randomLong(WATCHDOG_HARD_MIN_MS, WATCHDOG_HARD_MAX_MS);
        }
    }

    private class WatchdogSnapshot {
        private final int magicXp;
        private final int inventoryFingerprint;
        private final int equipmentFingerprint;
        private final long progressScore;
        private final String stateSignature;
        private final Tile tile;

        private WatchdogSnapshot(
                int magicXp,
                int inventoryFingerprint,
                int equipmentFingerprint,
                long progressScore,
                String stateSignature,
                Tile tile
        ) {
            this.magicXp = magicXp;
            this.inventoryFingerprint = inventoryFingerprint;
            this.equipmentFingerprint = equipmentFingerprint;
            this.progressScore = progressScore;
            this.stateSignature = stateSignature;
            this.tile = tile;
        }

        private boolean hasMaterialProgressSince(WatchdogSnapshot previous) {
            return magicXp != previous.magicXp
                    || inventoryFingerprint != previous.inventoryFingerprint
                    || equipmentFingerprint != previous.equipmentFingerprint
                    || progressScore != previous.progressScore;
        }
    }

    private WatchdogSnapshot captureWatchdogSnapshot(APIContext ctx) {
        return new WatchdogSnapshot(
                safeInt(() -> ctx.skills().get(Skill.Skills.MAGIC).getExperience()),
                itemFingerprint(ctx.inventory().getItems()),
                itemFingerprint(ctx.equipment().getItems()),
                progressScore(ctx),
                stateSignature(ctx),
                safeTile(ctx)
        );
    }

    private long progressScore(APIContext ctx) {
        long score = stats == null ? 0L : stats.casts;
        score = 31L * score + activeBatchCasts;
        score = 31L * score + pendingGeActions.size();
        score = 31L * score + placedGeActions.size();
        score = 31L * score + safeInt(() -> ctx.inventory().getCount(true, COINS));
        if (activeMethod != null) {
            score = 31L * score + safeInt(() -> ctx.inventory().getCount(activeMethod.inputItem));
            score = 31L * score + safeInt(() -> ctx.inventory().getCount(activeMethod.outputItem));
        }
        return score;
    }

    private String stateSignature(APIContext ctx) {
        EnchantMethod method = activeMethod;
        return "status=" + (stats == null ? "-" : normalizedStatus(stats.status))
                + "|method=" + (method == null ? "-" : method.key)
                + "|bank=" + safeBool(() -> ctx.bank().isOpen())
                + "|ge=" + safeBool(() -> ctx.grandExchange().isOpen())
                + "|menu=" + safeBool(() -> ctx.menu().isOpen())
                + "|dialogue=" + safeBool(() -> ctx.dialogues().isDialogueOpen())
                + "|itemSelected=" + safeBool(() -> ctx.inventory().isItemSelected())
                + "|spellSelected=" + safeBool(() -> ctx.magic().isSpellSelected())
                + "|moving=" + safeBool(() -> ctx.localPlayer().isMoving())
                + "|animating=" + safeBool(() -> ctx.localPlayer().isAnimating())
                + "|input=" + (method == null ? 0 : safeInt(() -> ctx.inventory().getCount(method.inputItem)))
                + "|output=" + (method == null ? 0 : safeInt(() -> ctx.inventory().getCount(method.outputItem)))
                + "|pending=" + pendingGeActions.size()
                + "|placed=" + placedGeActions.size()
                + "|cycle=" + enchantInventoryCycleActive
                + "|loc=" + locationText(ctx);
    }

    private String normalizedStatus(String status) {
        if (status == null || status.isBlank()) {
            return "-";
        }
        return status.replaceAll("\\d+", "#");
    }

    private int itemFingerprint(Iterable<ItemWidget> items) {
        int result = 17;
        for (ItemWidget item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            result = 31 * result + item.getIndex();
            result = 31 * result + item.getId();
            result = 31 * result + item.getStackSize();
            result = 31 * result + (item.isNoted() ? 1 : 0);
        }
        return result;
    }

    private Tile safeTile(APIContext ctx) {
        try {
            return ctx.localPlayer().getLocation();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private int tileDistance(Tile left, Tile right) {
        if (left == null || right == null || left.getPlane() != right.getPlane()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(Math.abs(left.getX() - right.getX()), Math.abs(left.getY() - right.getY()));
    }

    private long randomLong(long minInclusive, long maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }

    private long minutes(long millis) {
        return Math.max(1L, Math.round(millis / 60_000.0D));
    }

    private void clearBlockingInterfacesForWatchdog(APIContext ctx) {
        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
            }
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
            }
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
            }
            if (ctx.widgets().isInterfaceOpen()) {
                ctx.widgets().closeInterface();
            }
        } catch (RuntimeException e) {
            logDiagnostic("Watchdog interface cleanup failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Path saveWatchdogReport(APIContext ctx, String reason, String phase) {
        String report = buildWatchdogReport(ctx, reason, phase);
        try {
            Path path = watchdogReportPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, report);
            logDiagnostic("Watchdog report saved: " + path);
            return path;
        } catch (RuntimeException | IOException firstFailure) {
            try {
                Path fallback = Path.of(
                        System.getProperty("user.home", "."),
                        "enchant-jewellery-watchdog-reports",
                        watchdogReportFileName()
                );
                Files.createDirectories(fallback.getParent());
                Files.writeString(fallback, report);
                logDiagnostic("Watchdog report saved: " + fallback);
                return fallback;
            } catch (RuntimeException | IOException secondFailure) {
                logDiagnostic("Could not save watchdog report: " + secondFailure.getMessage());
                return null;
            }
        }
    }

    private void appendWatchdogReportFooter(Path path, boolean logoutRequested) {
        if (path == null) {
            return;
        }
        try {
            Files.writeString(
                    path,
                    "\nlogoutRequested=" + logoutRequested
                            + "\nclientLoggedInAfterLogout="
                            + safeBool(() -> getAPIContext() != null && getAPIContext().client().isLoggedIn())
                            + "\n",
                    StandardOpenOption.APPEND
            );
        } catch (RuntimeException | IOException e) {
            logDiagnostic("Could not append watchdog report footer: " + e.getMessage());
        }
    }

    private Path watchdogReportPath() {
        return Path.of(System.getProperty("user.dir", "."), "watchdog-reports", watchdogReportFileName());
    }

    private String watchdogReportFileName() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "enchant-jewellery-watchdog-" + timestamp + ".txt";
    }

    private String buildWatchdogReport(APIContext ctx, String reason, String phase) {
        StringBuilder report = new StringBuilder();
        report.append("Enchant Jewellery watchdog report\n");
        report.append("version=").append(SCRIPT_VERSION).append('\n');
        report.append("phase=").append(phase).append('\n');
        report.append("reason=").append(reason).append('\n');
        report.append("runtime=").append(stats == null ? "-" : stats.runtimeText()).append('\n');
        report.append("status=").append(stats == null ? "-" : stats.status).append('\n');
        report.append("activeMethod=").append(activeMethod == null ? "-" : activeMethod.key).append('\n');
        report.append("activeQuote=").append(activeQuote == null ? "-" : activeQuote.profitPerCast + " gp/cast").append('\n');
        report.append("casts=").append(stats == null ? 0 : stats.casts).append('\n');
        report.append("batch=").append(activeBatchCasts).append('/').append(activeBatchTargetCasts).append('\n');
        report.append("pendingGe=").append(pendingGeActions.size()).append('\n');
        report.append("placedGe=").append(placedGeActions.size()).append('\n');
        report.append("enchantCycleActive=").append(enchantInventoryCycleActive).append('\n');
        report.append("location=").append(locationText(ctx)).append('\n');
        report.append("vars=").append(varsSnapshot(ctx)).append('\n');
        report.append("stateSignature=").append(stateSignature(ctx)).append('\n');
        report.append("inventory=").append(itemSummary(ctx.inventory().getItems())).append('\n');
        report.append("equipment=").append(itemSummary(ctx.equipment().getItems())).append('\n');
        report.append("spellWidgets=").append(spellbookWidgetsSummary(ctx)).append('\n');
        report.append("lastChat=").append(stats == null ? "-" : stats.lastChat).append('\n');
        report.append("lastGeAction=").append(stats == null ? "-" : stats.lastGeAction).append('\n');
        report.append("recentEvents:\n");
        if (stats == null || stats.recentEvents(80).isEmpty()) {
            report.append("- none\n");
        } else {
            for (String event : stats.recentEvents(80)) {
                report.append("- ").append(event).append('\n');
            }
        }
        return report.toString();
    }

    private String spellbookWidgetsSummary(APIContext ctx) {
        StringBuilder summary = new StringBuilder();
        int[] children = {
                JEWELLERY_ENCHANTMENTS_CHILD,
                LEVEL_1_ENCHANT_CHILD,
                LEVEL_2_ENCHANT_CHILD,
                LEVEL_3_ENCHANT_CHILD,
                LEVEL_4_ENCHANT_CHILD
        };
        for (int child : children) {
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append("218.").append(child).append('=')
                    .append(widgetSummary(ctx.widgets().get(SPELLBOOK_GROUP, child)));
        }
        return shortText(summary.toString(), 2600);
    }

    private static class EnchantMethod {
        private final String key;
        private final String label;
        private final int requiredMagic;
        private final Spell spell;
        private final int spellWidgetChild;
        private final String expectedSpellText;
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
                String expectedSpellText,
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
            this.expectedSpellText = expectedSpellText;
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
        private static final int MAX_RECENT_EVENTS = 220;
        private static final DateTimeFormatter EVENT_TIME_FORMAT =
                DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        private final long startedAt = System.currentTimeMillis();
        private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
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
            String next = status == null ? "-" : status;
            if (!next.equals(this.status)) {
                recordEvent("STATUS " + next);
            }
            this.status = next;
        }

        private void recordEvent(String event) {
            String timestamp = LocalDateTime.now().format(EVENT_TIME_FORMAT);
            recentEvents.addLast(timestamp + " " + (event == null ? "-" : event));
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeFirst();
            }
        }

        private List<String> recentEvents(int max) {
            List<String> events = new ArrayList<>(recentEvents);
            if (events.size() <= max) {
                return events;
            }
            return events.subList(events.size() - max, events.size());
        }
    }
}
