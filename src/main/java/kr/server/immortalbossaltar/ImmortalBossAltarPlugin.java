package kr.server.immortalbossaltar;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ImmortalBossAltarPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private org.bukkit.NamespacedKey bossKey;
    private org.bukkit.NamespacedKey rewardKey;
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<String> usedPedestals = new HashSet<>();
    private final Map<String, Location> suppressedPortalBlocks = new LinkedHashMap<>();
    private UUID activeBoss;
    private UUID lastDamager;
    private boolean vanillaDragonDefeated;
    private boolean eggPrepared;
    private boolean ritualRunning;
    private boolean portalSuppressed;
    private boolean finalBossDefeated;
    private String finalBossKiller = "-";
    private String finalBossTime = "-";
    private BukkitTask ritualTask;
    private org.bukkit.configuration.file.YamlConfiguration data;

    @Override public void onEnable() {
        saveDefaultConfig();
        bossKey = new org.bukkit.NamespacedKey(this, "tracked_ancient_doom_dragon");
        rewardKey = new org.bukkit.NamespacedKey(this, "reward_id");
        loadPersistentData();
        if (getCommand("bossaltar") != null) getCommand("bossaltar").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        recoverAfterEnable();
        getLogger().info("ImmortalBossAltar 0.2.0 enabled. Persistent final-boss record: " + finalBossDefeated);
    }

    @Override public void onDisable() {
        if (ritualTask != null) ritualTask.cancel();
        // Keep a suppressed portal suppressed while the tracked boss is alive.
        // If there is no active boss, restore it to avoid leaving the exit broken.
        if (activeBoss == null && portalSuppressed) restorePortal();
        savePersistentData();
    }

    private void loadPersistentData() {
        data = new org.bukkit.configuration.file.YamlConfiguration();
        try {
            java.io.File file = new java.io.File(getDataFolder(), "data.yml");
            if (file.exists()) data.load(file);
            vanillaDragonDefeated = data.getBoolean("vanilla-dragon-defeated", false);
            finalBossDefeated = data.getBoolean("final-boss-defeated", false);
            finalBossKiller = data.getString("final-boss-killer", "-");
            finalBossTime = data.getString("final-boss-time", "-");
            String boss = data.getString("active-boss-uuid", "");
            if (!boss.isBlank()) activeBoss = UUID.fromString(boss);
            portalSuppressed = data.getBoolean("portal-suppressed", false);
            for (String key : data.getStringList("suppressed-portal-blocks")) {
                String[] p = key.split(",");
                if (p.length == 4) {
                    World w = Bukkit.getWorld(p[0]);
                    if (w != null) suppressedPortalBlocks.put(key, new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                }
            }
        } catch (Exception ex) {
            getLogger().severe("data.yml 로드 실패: " + ex.getMessage());
        }
    }

    private void savePersistentData() {
        if (data == null) data = new org.bukkit.configuration.file.YamlConfiguration();
        data.set("vanilla-dragon-defeated", vanillaDragonDefeated);
        data.set("final-boss-defeated", finalBossDefeated);
        data.set("final-boss-killer", finalBossKiller);
        data.set("final-boss-time", finalBossTime);
        data.set("active-boss-uuid", activeBoss == null ? "" : activeBoss.toString());
        data.set("portal-suppressed", portalSuppressed);
        List<String> blocks = new ArrayList<>(suppressedPortalBlocks.keySet());
        data.set("suppressed-portal-blocks", blocks);
        try { data.save(new java.io.File(getDataFolder(), "data.yml")); }
        catch (Exception ex) { getLogger().severe("data.yml 저장 실패: " + ex.getMessage()); }
    }

    private void recoverAfterEnable() {
        if (activeBoss != null) {
            EnderDragon found = findDragon(activeBoss);
            if (found == null || found.isDead() || !found.isValid()) {
                activeBoss = null;
                restorePortal();
                getLogger().warning("저장된 보스 UUID가 월드에서 발견되지 않아 전투 상태를 정리했습니다.");
            } else {
                found.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
                getLogger().warning("진행 중인 커스텀 보스를 다시 추적합니다. UUID=" + activeBoss);
            }
        } else if (portalSuppressed) {
            restorePortal();
        }
        savePersistentData();
    }

    private EnderDragon findDragon(UUID id) {
        for (World world : Bukkit.getWorlds()) for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class))
            if (dragon.getUniqueId().equals(id)) return dragon;
        return null;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("§e/bossaltar <setcenter|status|reset|reload>"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> showStatus(sender);
            case "setcenter" -> {
                if (!(sender instanceof Player player) || !player.hasPermission("immortalbossaltar.admin")) { sender.sendMessage("§c관리자 플레이어만 사용할 수 있습니다."); return true; }
                if (player.getWorld().getEnvironment() != World.Environment.THE_END) { msg(player, "end-only"); return true; }
                Block support = player.getTargetBlockExact(6);
                if (support == null || support.getType() != Material.BEDROCK) { msg(player, "center-target"); return true; }
                Block eggSpace = support.getRelative(BlockFace.UP);
                if (!eggSpace.isEmpty()) { msg(player, "center-target"); return true; }
                // Store the central support BEDROCK coordinate. The dragon egg
                // occupies the air block directly above it.
                Location c = support.getLocation();
                getConfig().set("altar.world", c.getWorld().getName());
                getConfig().set("altar.center.x", c.getBlockX());
                getConfig().set("altar.center.y", c.getBlockY());
                getConfig().set("altar.center.z", c.getBlockZ());
                saveConfig();
                if (!validateAltarStructure(c)) msg(player, "structure-invalid");
                else msg(player, "center-set", Map.of("x", String.valueOf(c.getBlockX()), "y", String.valueOf(c.getBlockY()), "z", String.valueOf(c.getBlockZ())));
            }
            case "reset" -> {
                if (!sender.hasPermission("immortalbossaltar.admin")) { sender.sendMessage("§c권한이 없습니다."); return true; }
                if (finalBossDefeated) { sender.sendMessage("§c최종 보스 영구 처치 기록이 있어 제단을 초기화할 수 없습니다."); return true; }
                if (activeBoss != null) { sender.sendMessage("§c보스가 살아 있는 동안 초기화할 수 없습니다."); return true; }
                cancelRitual(); eggPrepared = false; usedPedestals.clear(); damage.clear(); lastDamager = null;
                Location c = center(); if (c != null && c.getBlock().getType() == Material.DRAGON_EGG) c.getBlock().setType(Material.AIR, false);
                savePersistentData(); msg(sender, "reset-done");
            }
            case "reload" -> {
                if (!sender.hasPermission("immortalbossaltar.admin")) { sender.sendMessage("§c권한이 없습니다."); return true; }
                reloadConfig(); sender.sendMessage("§a설정을 다시 불러왔습니다.");
            }
            default -> sender.sendMessage("§e/bossaltar <setcenter|status|reset|reload>");
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        Location c = center();
        String world = c == null ? "미설정" : c.getWorld().getName();
        String altar = c == null ? "미설정" : c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ();
        String boss = activeBoss != null ? "전투 중" : finalBossDefeated ? "처치 완료" : "대기 중";
        sender.sendMessage(color("status-header", Map.of()));
        sender.sendMessage(color("status-world", Map.of("world", world)));
        sender.sendMessage(color("status-altar", Map.of("altar", altar)));
        sender.sendMessage(color("status-vanilla", Map.of("vanilla", vanillaDragonDefeated ? "완료" : "미처치")));
        sender.sendMessage(color("status-boss", Map.of("boss", boss)));
        sender.sendMessage(color("status-final", Map.of("final", finalBossDefeated ? "§a완료" : "§c미처치")));
        sender.sendMessage(color("status-killer", Map.of("killer", finalBossKiller)));
        sender.sendMessage(color("status-time", Map.of("time", finalBossTime)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVanillaDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon && dragon.getWorld().getEnvironment() == World.Environment.THE_END && !isTrackedBoss(dragon)) {
            vanillaDragonDefeated = true;
            savePersistentData();
            getLogger().info("바닐라 엔더 드래곤 처치 기록 저장 완료.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrystalPlace(BlockPlaceEvent event) {
        if (activeBoss != null && event.getBlockPlaced().getType() == Material.END_CRYSTAL) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c커스텀 보스 전투 중에는 바닐라 드래곤을 소환할 수 없습니다.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Location c = center(); if (c == null || event.getClickedBlock().getWorld().getUID() != c.getWorld().getUID()) return;
        Player player = event.getPlayer(); Block clicked = event.getClickedBlock();
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (finalBossDefeated) { if (isAltarRelated(clicked.getLocation(), c)) { event.setCancelled(true); msg(player, "already-final"); } return; }
        if (activeBoss != null || ritualRunning) return;
        if (clicked.getX() == c.getBlockX() && clicked.getY() == c.getBlockY() && clicked.getZ() == c.getBlockZ()) {
            Block eggBlock = c.getBlock().getRelative(BlockFace.UP);
            if (eggBlock.getType() == Material.DRAGON_EGG) {
                event.setCancelled(true);
                if (!eggPrepared || usedPedestals.size() != 4) { msg(player, "altar-incomplete"); return; }
                beginRitual(player); return;
            }
            if (player.getInventory().getItemInMainHand().getType() == Material.DRAGON_EGG && !eggPrepared) {
                event.setCancelled(true);
                if (!vanillaDragonDefeated || hasLivingVanillaDragon(c.getWorld())) { msg(player, "dragon-not-defeated"); return; }
                if (!validateAltarStructure(c)) { msg(player, "structure-invalid"); return; }
                if (!takeOne(player, Material.DRAGON_EGG)) return;
                eggBlock.setType(Material.DRAGON_EGG, false); eggPrepared = true; msg(player, "egg-placed");
                return;
            }
        }
        String direction = pedestalDirection(clicked.getLocation(), c, getConfig().getInt("altar.pedestal-offset", 2));
        if (direction == null || usedPedestals.contains(direction)) return;
        Material pedestal = Material.matchMaterial(getConfig().getString("altar.pedestal-material", "BEDROCK"));
        if (pedestal == null || clicked.getType() != pedestal || player.getInventory().getItemInMainHand().getType() != Material.ENDER_EYE) return;
        event.setCancelled(true);
        if (!eggPrepared) { msg(player, "altar-incomplete"); return; }
        if (takeOne(player, Material.ENDER_EYE)) {
            usedPedestals.add(direction);
            clicked.getWorld().spawnParticle(Particle.PORTAL, clicked.getLocation().add(.5, 1, .5), 45, .35, .5, .35, .08);
            msg(player, "eye-placed", Map.of("count", String.valueOf(usedPedestals.size())));
        }
    }

    private boolean isAltarRelated(Location l, Location c) {
        return Math.abs(l.getBlockX() - c.getBlockX()) <= 3 && Math.abs(l.getBlockZ() - c.getBlockZ()) <= 3 && Math.abs(l.getBlockY() - c.getBlockY()) <= 1;
    }

    private boolean validateAltarStructure(Location c) {
        if (getConfig().getBoolean("altar.require-bedrock-support", true) && c.getBlock().getType() != Material.BEDROCK) return false;
        Material pedestal = Material.matchMaterial(getConfig().getString("altar.pedestal-material", "BEDROCK"));
        if (pedestal == null) return false;
        int d = getConfig().getInt("altar.pedestal-offset", 2);
        return c.getWorld().getBlockAt(c.getBlockX(), c.getBlockY() + 1, c.getBlockZ()).isEmpty()
                && c.getWorld().getBlockAt(c.getBlockX(), c.getBlockY(), c.getBlockZ() - d).getType() == pedestal
                && c.getWorld().getBlockAt(c.getBlockX() + d, c.getBlockY(), c.getBlockZ()).getType() == pedestal
                && c.getWorld().getBlockAt(c.getBlockX(), c.getBlockY(), c.getBlockZ() + d).getType() == pedestal
                && c.getWorld().getBlockAt(c.getBlockX() - d, c.getBlockY(), c.getBlockZ()).getType() == pedestal;
    }

    private void beginRitual(Player initiator) {
        if (ritualRunning || activeBoss != null || finalBossDefeated) return;
        if (!vanillaDragonDefeated || hasLivingVanillaDragon(initiator.getWorld())) { msg(initiator, "vanilla-dragon-alive"); return; }
        ritualRunning = true; msg(initiator, "ritual-start");
        ritualTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            ritualRunning = false;
            Location c = center();
            if (c == null || finalBossDefeated || !eggPrepared || usedPedestals.size() != 4 || hasLivingVanillaDragon(c.getWorld()) || !validateAltarStructureWithEgg(c)) {
                getLogger().warning("소환 조건이 변경되어 의식을 취소했습니다. 재료는 자동 환불되지 않으므로 관리자 확인이 필요합니다.");
                return;
            }
            spawnMythicBoss(c);
        }, Math.max(1, getConfig().getLong("altar.ritual-seconds", 10)) * 20L);
    }

    private boolean validateAltarStructureWithEgg(Location c) {
        if (c.getBlock().getRelative(BlockFace.UP).getType() != Material.DRAGON_EGG) return false;
        Material pedestal = Material.matchMaterial(getConfig().getString("altar.pedestal-material", "BEDROCK"));
        int d = getConfig().getInt("altar.pedestal-offset", 2);
        return pedestal != null
                && c.getWorld().getBlockAt(c.getBlockX(), c.getBlockY(), c.getBlockZ() - d).getType() == pedestal
                && c.getWorld().getBlockAt(c.getBlockX() + d, c.getBlockY(), c.getBlockZ()).getType() == pedestal
                && c.getWorld().getBlockAt(c.getBlockX(), c.getBlockY(), c.getBlockZ() + d).getType() == pedestal
                && c.getWorld().getBlockAt(c.getBlockX() - d, c.getBlockY(), c.getBlockZ()).getType() == pedestal;
    }

    private void spawnMythicBoss(Location c) {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) { getLogger().severe("MythicMobs가 없어 보스를 소환할 수 없습니다."); return; }
        Set<UUID> before = new HashSet<>();
        for (Entity e : c.getWorld().getNearbyEntities(c, 40, 40, 40)) if (e instanceof EnderDragon) before.add(e.getUniqueId());
        String mob = getConfig().getString("boss.mythicmobs-internal-name", "AncientDoomDragon");
        Location spawn = c.clone().add(.5, getConfig().getDouble("boss.spawn-y-offset", 2.0), .5);
        String loc = c.getWorld().getName() + "," + spawn.getX() + "," + spawn.getY() + "," + spawn.getZ();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm mobs spawn " + mob + " 1 " + loc);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            List<EnderDragon> found = c.getWorld().getNearbyEntities(c, 40, 40, 40).stream()
                    .filter(e -> e instanceof EnderDragon).map(e -> (EnderDragon) e)
                    .filter(e -> !before.contains(e.getUniqueId())).toList();
            if (found.size() != 1) { getLogger().severe("새로 생성된 엔더 드래곤을 하나로 특정하지 못했습니다. 발견 수=" + found.size()); return; }
            EnderDragon boss = found.get(0);
            activeBoss = boss.getUniqueId(); boss.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            eggPrepared = false; usedPedestals.clear(); c.getBlock().getRelative(BlockFace.UP).setType(Material.AIR, false);
            suppressPortal(c); savePersistentData();
            for (Player p : c.getWorld().getPlayers()) msg(p, "boss-spawned");
        }, 3L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !isTrackedBoss(dragon)) return;
        Player attacker = responsiblePlayer(event.getDamager()); if (attacker == null) return;
        double actual = Math.min(event.getFinalDamage(), Math.max(0, dragon.getHealth()));
        if (actual <= 0) return;
        damage.merge(attacker.getUniqueId(), actual, Double::sum); lastDamager = attacker.getUniqueId();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !isTrackedBoss(dragon)) return;
        event.getDrops().removeIf(item -> item.getType() == Material.GOLDEN_SWORD);
        UUID killerId = lastDamager;
        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        if (killer != null && killer.isOnline()) {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm items give " + killer.getName() + " " + getConfig().getString("reward.mythicmobs-item-internal-name", "EndDoomScoreSword") + " 1");
            msg(killer, ok ? "reward-received" : "reward-failed");
            finalBossKiller = killer.getName();
        } else {
            finalBossKiller = killerId == null ? "판정 불가" : killerId.toString();
            getLogger().severe("막타 보상자를 온라인 플레이어로 찾지 못했습니다: " + finalBossKiller);
        }
        finalBossDefeated = true;
        finalBossTime = Instant.now().toString();
        savePersistentData();
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        for (Player p : dragon.getWorld().getPlayers()) {
            msg(p, "boss-defeated"); msg(p, "contribution-header");
            damage.entrySet().stream().sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()).forEach(entry -> {
                OfflinePlayer op = Bukkit.getOfflinePlayer(entry.getKey());
                double pct = total <= 0 ? 0 : entry.getValue() * 100.0 / total;
                msg(p, "contribution-line", Map.of("player", op.getName() == null ? entry.getKey().toString().substring(0, 8) : op.getName(),
                        "damage", String.format(Locale.US, "%.1f", entry.getValue()), "percent", String.format(Locale.US, "%.1f", pct)));
            });
            msg(p, "final-recorded");
        }
        activeBoss = null; lastDamager = null; damage.clear(); restorePortal(); savePersistentData();
    }

    private void suppressPortal(Location c) {
        suppressedPortalBlocks.clear(); int r = getConfig().getInt("portal.scan-radius", 3);
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            Block b = c.getWorld().getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
            if (b.getType() == Material.END_PORTAL) {
                String key = c.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
                suppressedPortalBlocks.put(key, b.getLocation());
            }
        }
        portalSuppressed = !suppressedPortalBlocks.isEmpty();
        savePersistentData(); // persist the exact portal coordinates before removing any block
        for (Location location : suppressedPortalBlocks.values()) location.getBlock().setType(Material.AIR, false);
        if (!portalSuppressed) getLogger().warning("제단 주변에서 출구 포털 블록을 찾지 못했습니다. 포털은 변경하지 않았습니다.");
    }

    private void restorePortal() {
        for (Location location : suppressedPortalBlocks.values()) {
            Block block = location.getBlock(); if (block.getType() == Material.AIR) block.setType(Material.END_PORTAL, false);
        }
        suppressedPortalBlocks.clear(); portalSuppressed = false; savePersistentData();
    }

    private boolean isTrackedBoss(EnderDragon dragon) {
        return dragon.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)
                || (activeBoss != null && activeBoss.equals(dragon.getUniqueId()));
    }

    private boolean hasLivingVanillaDragon(World world) {
        return world.getEntitiesByClass(EnderDragon.class).stream().anyMatch(d -> !isTrackedBoss(d) && d.isValid() && !d.isDead());
    }

    private Player responsiblePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile) { ProjectileSource shooter = projectile.getShooter(); if (shooter instanceof Player p) return p; }
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player p) return p;
        return null;
    }

    private boolean takeOne(Player player, Material type) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != type || hand.getAmount() < 1) return false;
        if (hand.getAmount() == 1) player.getInventory().setItemInMainHand(null); else hand.setAmount(hand.getAmount() - 1);
        return true;
    }

    private String pedestalDirection(Location l, Location c, int d) {
        int dx = l.getBlockX() - c.getBlockX(), dz = l.getBlockZ() - c.getBlockZ(), dy = l.getBlockY() - c.getBlockY();
        if (dy != 0) return null;
        if (dx == 0 && dz == -d) return "N"; if (dx == d && dz == 0) return "E";
        if (dx == 0 && dz == d) return "S"; if (dx == -d && dz == 0) return "W"; return null;
    }

    private Location center() {
        String worldName = getConfig().getString("altar.world", ""); if (worldName == null || worldName.isBlank()) return null;
        World world = Bukkit.getWorld(worldName); if (world == null) return null;
        return new Location(world, getConfig().getInt("altar.center.x"), getConfig().getInt("altar.center.y"), getConfig().getInt("altar.center.z"));
    }

    private void cancelRitual() { if (ritualTask != null) ritualTask.cancel(); ritualTask = null; ritualRunning = false; }
    private void msg(Player player, String key) { player.sendMessage(color(key, Map.of())); }
    private void msg(Player player, String key, Map<String, String> values) { player.sendMessage(color(key, values)); }
    private void msg(CommandSender sender, String key) { sender.sendMessage(color(key, Map.of())); }
    private String color(String key, Map<String, String> values) {
        String text = getConfig().getString("messages." + key, "&cMissing message: " + key);
        for (Map.Entry<String, String> entry : values.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "") + text);
    }
}
