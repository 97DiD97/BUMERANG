package dev.boomerang;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BoomerangPlugin extends JavaPlugin implements Listener {

    /** Параметры одного вида бумеранга. */
    record Stats(int maxBounces, int noHitTicks, double throwSpeed, double returnSpeed, double damage) {}

    private static final UUID PACK_ID = UUID.fromString("5b1f7f1e-3c2a-4e6a-9d0b-2f6a8d1c4e77");
    private static final String PACK_PATH = "/BoomerangPack.zip";

    private NamespacedKey itemKey;
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<Tier, Stats> stats = new EnumMap<>(Tier.class);

    private boolean useResourcePack;
    private int maxAgeTicks;

    // встроенный ресурспак
    private byte[] packBytes;
    private byte[] packHash;
    private HttpServer httpServer;
    private ExecutorService httpExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "boomerang_tier");
        loadSettings();
        setupPack();

        getServer().getPluginManager().registerEvents(this, this);
        registerRecipes();

        new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Flight> it = flights.values().iterator();
                while (it.hasNext()) {
                    if (it.next().tick()) it.remove();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Flight f : flights.values()) f.abort();
        flights.clear();
        stopPackServer();
        for (Tier t : Tier.values()) getServer().removeRecipe(recipeKey(t));
    }

    private void loadSettings() {
        useResourcePack = getConfig().getBoolean("resource-pack", true);
        maxAgeTicks = Math.max(40, getConfig().getInt("max-flight-ticks", 400));
        for (Tier t : Tier.values()) {
            ConfigurationSection s = getConfig().getConfigurationSection("tiers." + t.id);
            if (s == null) s = getConfig().createSection("tiers." + t.id);
            stats.put(t, new Stats(
                    Math.max(1, s.getInt("max-bounces", 3)),
                    Math.max(1, s.getInt("no-hit-return-ticks", 30)),
                    Math.max(0.1, s.getDouble("throw-speed", 1.0)),
                    Math.max(0.1, s.getDouble("return-speed", 1.3)),
                    Math.max(0.0, s.getDouble("damage", 6.0))));
        }
    }

    int maxAgeTicks() { return maxAgeTicks; }

    // ---------- встроенный ресурспак ----------

    private String externalPackUrl() {
        String url = getConfig().getString("pack-url", "");
        return url == null ? "" : url.trim();
    }

    private void setupPack() {
        packBytes = null;
        packHash = null;
        if (!useResourcePack || !externalPackUrl().isEmpty()) return;

        try (InputStream in = getResource("BoomerangPack.zip")) {
            if (in == null) {
                getLogger().warning("В плагине нет встроенного ресурспака (BoomerangPack.zip).");
                return;
            }
            packBytes = in.readAllBytes();
            packHash = MessageDigest.getInstance("SHA-1").digest(packBytes);
        } catch (Exception ex) {
            getLogger().warning("Не удалось прочитать встроенный ресурспак: " + ex.getMessage());
            return;
        }

        int port = getConfig().getInt("pack-port", 8165);
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext(PACK_PATH, exchange -> {
                byte[] data = packBytes;
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            });
            httpExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "Boomerang-Pack-HTTP");
                t.setDaemon(true);
                return t;
            });
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            getLogger().info("Ресурспак раздаётся на порту " + port + " (откройте этот TCP-порт на сервере).");
        } catch (IOException ex) {
            httpServer = null;
            getLogger().warning("Не удалось открыть порт " + port + " для ресурспака: " + ex.getMessage()
                    + ". Измените pack-port или укажите pack-url в config.yml.");
        }
    }

    private void stopPackServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!useResourcePack) return;
        Player p = e.getPlayer();
        String ext = externalPackUrl();

        String url;
        byte[] hash;
        if (!ext.isEmpty()) {
            url = ext;
            hash = null;
        } else if (httpServer != null && packBytes != null) {
            String host = getConfig().getString("pack-host", "");
            host = host == null ? "" : host.trim();
            if (host.isEmpty()) {
                InetSocketAddress vh = p.getVirtualHost();
                host = vh != null ? vh.getHostString() : "localhost";
            }
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            url = "http://" + host + ":" + getConfig().getInt("pack-port", 8165) + PACK_PATH;
            hash = packHash;
        } else {
            return;
        }

        final String finalUrl = url;
        final byte[] finalHash = hash;
        boolean force = getConfig().getBoolean("force-pack", false);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                p.setResourcePack(PACK_ID, finalUrl, finalHash,
                        Component.text("Текстуры бумерангов"), force);
            }
        }, 20L);
    }

    // ---------- предмет ----------

    ItemStack createBoomerang(Tier tier) {
        Stats st = stats.get(tier);
        // Без ресурспака предмет выглядит как слиток/алмаз, с ресурспаком — как наша текстура.
        ItemStack item = new ItemStack(useResourcePack ? Material.STICK : tier.ingredient);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(tier.title, tier.color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(gray("ПКМ — бросить"));
        lore.add(gray("Урон: " + trim(st.damage() / 2.0) + " ♥"));
        lore.add(gray("Отскоков: " + st.maxBounces()));
        meta.lore(lore);
        meta.setMaxStackSize(1);
        if (useResourcePack) {
            meta.setItemModel(new NamespacedKey("boomerang", tier.id + "_boomerang"));
        }
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, tier.id);
        item.setItemMeta(meta);
        return item;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** @return вид бумеранга или null, если предмет — не бумеранг. */
    private Tier tierOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : Tier.byId(id);
    }

    private NamespacedKey recipeKey(Tier t) {
        return new NamespacedKey(this, "boomerang_" + t.id);
    }

    private void registerRecipes() {
        for (Tier t : Tier.values()) {
            getServer().removeRecipe(recipeKey(t));
            ShapedRecipe recipe = new ShapedRecipe(recipeKey(t), createBoomerang(t));
            recipe.shape("II", "S ");
            recipe.setIngredient('I', t.ingredient);
            recipe.setIngredient('S', Material.STICK);
            getServer().addRecipe(recipe);
        }
    }

    // ---------- бросок ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack inHand = p.getInventory().getItem(hand);
        Tier tier = tierOf(inHand);
        if (tier == null) return;

        e.setCancelled(true);
        if (flights.containsKey(p.getUniqueId())) return; // один бумеранг за раз

        ItemStack thrown = inHand.asOne();
        inHand.subtract(1);
        p.getInventory().setItem(hand, inHand.getAmount() <= 0 ? new ItemStack(Material.AIR) : inHand);

        flights.put(p.getUniqueId(), new Flight(this, p, thrown, stats.get(tier)));
    }

    // ---------- команда ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("boomerang.admin")) {
                sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
                return true;
            }
            stopPackServer();
            reloadConfig();
            loadSettings();
            setupPack();
            registerRecipes();
            sender.sendMessage(Component.text("Настройки перезагружены.", NamedTextColor.GREEN));
            return true;
        }

        if (!sender.hasPermission("boomerang.give") || args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("boomerang.give")) {
                sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            } else {
                sender.sendMessage(Component.text(
                        "Использование: /boomerang give <copper|iron|diamond> [игрок]", NamedTextColor.RED));
            }
            return true;
        }

        Tier tier = Tier.byId(args[1].toLowerCase());
        if (tier == null) {
            sender.sendMessage(Component.text("Вид: copper, iron или diamond.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Укажи игрока.", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> left = target.getInventory().addItem(createBoomerang(tier));
        for (ItemStack rest : left.values()) target.getWorld().dropItemNaturally(target.getLocation(), rest);
        sender.sendMessage(Component.text(tier.title + " выдан: " + target.getName(), NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return List.of("copper", "iron", "diamond");
        return null; // имена игроков подставит сервер
    }
}
