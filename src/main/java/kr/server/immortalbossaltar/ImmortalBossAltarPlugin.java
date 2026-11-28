package kr.server.immortalbossaltar;

import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
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
    // Offered items are removed immediately and held in persistent escrow until spawn succeeds or is refunded.
    private UUID eggDepositor;
    private final Map<String, UUID> pedestalDepositors = new HashMap<>();
    private final Map<UUID, Integer> escrowEyes = new HashMap<>();
    private final Map<UUID, Integer> escrowEggs = new HashMap<>();
    private boolean summonPromptSent;
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
        getLogger().info("ImmortalBossAltar 0.4.2 enabled. Persistent final-boss record: " + finalBossDefeated);
    }

    @Override public void onDisable() {
        if (ritualTask != null) ritualTask.cancel();
        // Offered materials remain in player inventories until spawn confirmation.
        clearPreparation(true);
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
            if (data.isConfigurationSection("escrow-eyes")) {
                for (String key : data.getConfigurationSection("escrow-eyes").getKeys(false)) {
                    try { escrowEyes.put(UUID.fromString(key), data.getInt("escrow-eyes." + key)); } catch (IllegalArgumentException ignored) {}
                }
            }
            if (data.isConfigurationSection("escrow-eggs")) {
                for (String key : data.getConfigurationSection("escrow-eggs").getKeys(false)) {
                    try { escrowEggs.put(UUID.fromString(key), data.getInt("escrow-eggs." + key)); } catch (IllegalArgumentException ignored) {}
                }
            }
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
        data.set("escrow-eyes", null);
        for (Map.Entry<UUID, Integer> entry : escrowEyes.entrySet()) data.set("escrow-eyes." + entry.getKey(), entry.getValue());
        data.set("escrow-eggs", null);
        for (Map.Entry<UUID, Integer> entry : escrowEggs.entrySet()) data.set("escrow-eggs." + entry.getKey(), entry.getValue());
        List<String> blocks = new ArrayList<>(suppressedPortalBlocks.keySet());
        data.set("suppressed-portal-blocks", blocks);
        try { data.save(new java.io.File(getDataFolder(), "data.yml")); }
        catch (Exception ex) { getLogger().severe("data.yml 저장 실패: " + ex.getMessage()); }
    }

    private void recoverAfterEnable() {
        // In-progress offerings are not persisted across restart. They were not consumed,
        // so clear only the visual egg and return the altar to idle.
        if (activeBoss == null) clearPreparation(true);
        if (activeBoss != null) {
            EnderDragon found = findDragon(activeBoss);
            if (found == null || found.isDead() || !found.isValid()) {
                activeBoss = null;
                restorePortal();
                getLogger().warning("저장된 보스 UUID가 월드에서 발견되지 않아 전투 상태를 정리했습니다.");
            } else {
                found.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
                clearPreparation(true);
                if (!portalSuppressed) {
                    Location c = center();
                    if (c != null) suppressPortal(c);
                }
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
            case "summon" -> { if (!(sender instanceof Player player)) { sender.sendMessage("플레이어만 소환을 확정할 수 있습니다."); return true; } beginRitual(player); }
            case "setcenter" -> {
                if (!(sender instanceof Player player) || !player.hasPermission("immortalbossaltar.admin")) { sender.sendMessage("§c관리자 플레이어만 사용할 수 있습니다."); return true; }
                if (player.getWorld().getEnvironment() != World.Environment.THE_END) { msg(player, "end-only"); return true; }
                Block support = player.getTargetBlockExact(6);
                if (support == null || support.getType() != Material.BEDROCK) { msg(player, "center-target"); return true; }
                Block eggSpace = support.getRelative(BlockFace.UP);
                if (!eggSpace.isEmpty()) { msg(player, "center-target"); return true; }
                // Store the central support BEDROCK coordinate. Pedestals are
                // discovered from the real fountain geometry instead of assuming
                // they are exactly two blocks away at the same Y level.
                Location c = support.getLocation();
                getConfig().set("altar.world", c.getWorld().getName());
                getConfig().set("altar.center.x", c.getBlockX());
                getConfig().set("altar.center.y", c.getBlockY());
                getConfig().set("altar.center.z", c.getBlockZ());
                discoverPedestals(c);
                saveConfig();
                if (!validateAltarStructure(c)) {
                    msg(player, "center-saved-pedestals-missing", Map.of("missing", missingPedestalDirections(c)));
                } else {
                    msg(player, "center-set", Map.of("x", String.valueOf(c.getBlockX()), "y", String.valueOf(c.getBlockY()), "z", String.valueOf(c.getBlockZ())));
                }
            }
            case "reset" -> {
                if (!sender.hasPermission("immortalbossaltar.admin")) { sender.sendMessage("§c권한이 없습니다."); return true; }
                if (finalBossDefeated) { sender.sendMessage("§c최종 보스 영구 처치 기록이 있어 제단을 초기화할 수 없습니다."); return true; }
                if (activeBoss != null) { sender.sendMessage("§c보스가 살아 있는 동안 초기화할 수 없습니다."); return true; }
                cancelRitual(); clearPreparation(true); damage.clear(); lastDamager = null;
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
        if (c != null) {
            sender.sendMessage(color("status-pedestals", Map.of("pedestals", validateAltarStructure(c) ? "4곳 확인 완료" : "미확인 방향: " + missingPedestalDirections(c))));
            sender.sendMessage("§7제물 발광 액자: §f" + readyPedestalFrameCount(c) + "/4곳 (엔더의 눈 확인)");
            for (String direction : List.of("N", "E", "S", "W")) {
                Location pedestal = getPedestalLocation(direction);
                String value = pedestal == null ? "미발견" : pedestal.getBlockX() + "," + pedestal.getBlockY() + "," + pedestal.getBlockZ()
                        + " (" + pedestal.getBlock().getType() + ")";
                sender.sendMessage("§7받침대 " + direction + ": §f" + value);
            }
        }
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        Location c = center();
        if (c != null) {
            Block eggSlot = c.getBlock().getRelative(BlockFace.UP);
            if (sameBlock(event.getBlockPlaced(), eggSlot)) {
                event.setCancelled(true);
                return;
            }
        }
        if (activeBoss != null && event.getBlockPlaced().getType() == Material.END_CRYSTAL) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c커스텀 보스 전투 중에는 바닐라 드래곤을 소환할 수 없습니다.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEggBreak(BlockBreakEvent event) {
        Location c = center();
        if (c == null) return;
        Block egg = c.getBlock().getRelative(BlockFace.UP);
        if (sameBlock(event.getBlock(), egg)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c제단의 용의 알은 파괴할 수 없습니다. 제단 초기화로 회수하세요.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEggInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Location c = center();
        if (c == null) return;
        Block egg = c.getBlock().getRelative(BlockFace.UP);
        if (!sameBlock(event.getClickedBlock(), egg) || egg.getType() != Material.DRAGON_EGG) return;

        // The altar egg never uses vanilla teleport behavior.
        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.getPlayer().sendMessage("§e제단의 용의 알은 우클릭 상호작용이 비활성화되어 있습니다. 회수하려면 좌클릭하세요.");
            return;
        }

        // Left-click retrieves exactly one egg directly to the player's inventory.
        cancelRitual();
        egg.setType(Material.AIR, false);
        Player player = event.getPlayer();
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(Material.DRAGON_EGG, 1));
        for (ItemStack item : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);

        if (eggDepositor != null) escrowEggs.remove(eggDepositor);
        eggPrepared = false;
        eggDepositor = null;
        summonPromptSent = false;
        savePersistentData();
        player.sendMessage("§a용의 알을 회수하여 인벤토리로 돌려받았습니다. 받침대의 제물 액자는 그대로 유지됩니다.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getHand() != EquipmentSlot.HAND) return;
        Location c = center();
        if (c == null) return;
        Block clicked = event.getClickedBlock();
        if (!clicked.getWorld().getUID().equals(c.getWorld().getUID())) return;
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) return;

        Block eggBlock = c.getBlock().getRelative(BlockFace.UP);
        boolean centerClicked = sameBlock(clicked, c.getBlock());
        boolean eggClicked = sameBlock(clicked, eggBlock);

        if (finalBossDefeated) {
            if (centerClicked || eggClicked || pedestalDirection(clicked.getLocation(), c, 0) != null) {
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                msg(player, "already-final");
            }
            return;
        }
        if (activeBoss != null || ritualRunning) {
            if (centerClicked || eggClicked || pedestalDirection(clicked.getLocation(), c, 0) != null) {
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                player.sendMessage("§c보스 소환 또는 전투가 진행 중입니다.");
            }
            return;
        }

        // The actual egg block is handled by onEggInteract. Never allow a
        // right-click on the egg to pass through to vanilla.
        if (eggClicked) {
            event.setCancelled(true);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            return;
        }

        // Only the central BEDROCK is used to place the dragon egg.
        // Cardinal pedestals are intentionally left interactive so players can
        // place glow item frames on them; offerings are inserted into the frames.
        if (!centerClicked) return;

        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        if (eggPrepared || eggBlock.getType() == Material.DRAGON_EGG) {
            player.sendMessage("§e이미 제단 중앙에 용의 알이 준비되어 있습니다.");
            return;
        }
        if (player.getInventory().getItemInMainHand().getType() != Material.DRAGON_EGG) {
            player.sendMessage("§c중앙 기반암을 우클릭할 때는 용의 알을 들어야 합니다.");
            return;
        }
        if (!vanillaDragonDefeated || hasLivingVanillaDragon(c.getWorld())) {
            msg(player, "dragon-not-defeated");
            return;
        }
        if (!validateAltarStructure(c)) {
            msg(player, "structure-invalid");
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);

        eggBlock.setType(Material.DRAGON_EGG, false);
        eggPrepared = true;
        eggDepositor = player.getUniqueId();
        escrowEggs.merge(player.getUniqueId(), 1, Integer::sum);
        savePersistentData();
        msg(player, "egg-reserved");
        showSummonPromptIfReady(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPedestalFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof GlowItemFrame frame)) return;
        String direction = pedestalFrameDirection(frame);
        if (direction == null) return;

        ItemStack displayed = frame.getItem();
        if (displayed.getType() == Material.ENDER_EYE) {
            // Once an Eye is offered, it cannot be taken back or rotated out.
            event.setCancelled(true);
            event.getPlayer().sendMessage("§e이 받침대에는 엔더의 눈이 이미 봉헌되어 있습니다.");
            return;
        }

        if (displayed.getType() == Material.AIR) {
            if (event.getPlayer().getInventory().getItemInMainHand().getType() != Material.ENDER_EYE) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§c발광 아이템 액자에는 엔더의 눈만 넣을 수 있습니다.");
                return;
            }
            // Let vanilla put the Eye into the frame, then validate all four
            // frames on the next tick after the entity has updated.
            Player offeringPlayer = event.getPlayer();
            Bukkit.getScheduler().runTask(this, () -> {
                if (frame.isValid() && frame.getItem().getType() == Material.ENDER_EYE) {
                    msg(offeringPlayer, "eye-reserved",
                            Map.of("count", String.valueOf(readyPedestalFrameCount(center()))));
                    showSummonPromptIfReady(offeringPlayer);
                }
            });
            return;
        }

        // Reject and return any non-Eye item already placed in a pedestal frame.
        event.setCancelled(true);
        ItemStack rejected = displayed.clone();
        frame.setItem(new ItemStack(Material.AIR), false);
        Map<Integer, ItemStack> overflow = event.getPlayer().getInventory().addItem(rejected);
        for (ItemStack item : overflow.values()) {
            event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), item);
        }
        event.getPlayer().sendMessage("§e엔더의 눈이 아닌 제물은 허용되지 않아 회수했습니다.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPedestalFrameDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof GlowItemFrame frame && pedestalFrameDirection(frame) != null) {
            event.setCancelled(true);
        }
    }

    private String pedestalFrameDirection(GlowItemFrame frame) {
        Location frameLocation = frame.getLocation();
        Location c = center();
        if (c == null || !frameLocation.getWorld().getUID().equals(c.getWorld().getUID())) return null;

        // Hanging-entity coordinates are positioned on the supporting block's
        // face, so deriving the support block by flooring the entity location
        // is unreliable (especially for top-mounted frames). Match the frame
        // to the exact configured pedestal by its distance from that block's
        // center instead. Pedestals are separated far enough that the 0.75
        // squared-distance limit cannot overlap neighboring directions.
        for (String direction : List.of("N", "E", "S", "W")) {
            Location pedestal = getPedestalLocation(direction);
            if (pedestal == null || !pedestal.getWorld().getUID().equals(frameLocation.getWorld().getUID())) continue;
            Location blockCenter = pedestal.clone().add(.5, .5, .5);
            if (frameLocation.distanceSquared(blockCenter) <= 0.75) return direction;
        }
        return null;
    }

    private List<GlowItemFrame> framesOnPedestal(String direction) {
        Location pedestal = getPedestalLocation(direction);
        if (pedestal == null || pedestal.getWorld() == null) return List.of();
        List<GlowItemFrame> found = new ArrayList<>();
        for (Entity entity : pedestal.getWorld().getNearbyEntities(pedestal.clone().add(.5, .5, .5), 1.25, 1.25, 1.25)) {
            if (entity instanceof GlowItemFrame frame && direction.equals(pedestalFrameDirection(frame))) {
                found.add(frame);
            }
        }
        return found;
    }

    private boolean pedestalFrameReady(String direction) {
        List<GlowItemFrame> frames = framesOnPedestal(direction);
        return frames.size() == 1 && frames.get(0).getItem().getType() == Material.ENDER_EYE;
    }

    private int readyPedestalFrameCount(Location c) {
        int count = 0;
        for (String direction : List.of("N", "E", "S", "W")) {
            if (pedestalFrameReady(direction)) count++;
        }
        return count;
    }

    private String missingFrameDirections() {
        List<String> missing = new ArrayList<>();
        for (String direction : List.of("N", "E", "S", "W")) {
            List<GlowItemFrame> frames = framesOnPedestal(direction);
            if (frames.isEmpty()) missing.add(direction + ": 발광 액자 없음");
            else if (frames.size() != 1) missing.add(direction + ": 액자 중복");
            else if (frames.get(0).getItem().getType() != Material.ENDER_EYE) missing.add(direction + ": 엔더의 눈 없음");
        }
        return missing.isEmpty() ? "없음" : String.join(", ", missing);
    }

    private boolean consumePedestalFrames() {
        Location c = center();
        if (c == null) return false;
        List<GlowItemFrame> toRemove = new ArrayList<>();
        for (String direction : List.of("N", "E", "S", "W")) {
            List<GlowItemFrame> frames = framesOnPedestal(direction);
            if (frames.size() != 1 || frames.get(0).getItem().getType() != Material.ENDER_EYE) return false;
            toRemove.add(frames.get(0));
        }
        // Removing the GlowItemFrame entity also consumes the Eye displayed in it.
        for (GlowItemFrame frame : toRemove) frame.remove();
        return true;
    }

    private boolean sameBlock(Block a, Block b) {
        return a.getWorld().getUID().equals(b.getWorld().getUID())
                && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) { refundEscrowFor(event.getPlayer().getUniqueId()); }

    private void showSummonPromptIfReady(Player player) {
        if (summonPromptSent || !eggPrepared || activeBoss != null || finalBossDefeated || !hasAllReservedMaterials()) return;
        summonPromptSent = true;
        player.sendMessage(Component.text("[불멸의 제단] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text("제단이 준비되었습니다! ", NamedTextColor.GREEN))
                .append(Component.text("고대의 파멸룡을 소환하시겠습니까? ", NamedTextColor.WHITE))
                .append(Component.text("[소환하기]", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/bossaltar summon"))
                        .hoverEvent(HoverEvent.showText(Component.text("클릭하면 소환 의식이 시작됩니다.")))));
    }

    private void refundEscrowFor(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) return;

        int eggs = escrowEggs.getOrDefault(id, 0);
        int eyes = escrowEyes.getOrDefault(id, 0);
        if (eggs <= 0 && eyes <= 0) return;

        if (eggs > 0) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(Material.DRAGON_EGG, eggs));
            for (ItemStack item : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
            escrowEggs.remove(id);
        }
        if (eyes > 0) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(Material.ENDER_EYE, eyes));
            for (ItemStack item : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), item);
            escrowEyes.remove(id);
        }
        savePersistentData();
    }

    private void refundAllEscrowEyes() {
        Set<UUID> owners = new HashSet<>(escrowEyes.keySet());
        owners.addAll(escrowEggs.keySet());
        for (UUID id : owners) refundEscrowFor(id);
        savePersistentData();
    }

    private boolean isAltarRelated(Location l, Location c) {
        return Math.abs(l.getBlockX() - c.getBlockX()) <= 3 && Math.abs(l.getBlockZ() - c.getBlockZ()) <= 3 && Math.abs(l.getBlockY() - c.getBlockY()) <= 1;
    }

    private boolean validateAltarStructure(Location c) {
        if (getConfig().getBoolean("altar.require-bedrock-support", true) && c.getBlock().getType() != Material.BEDROCK) return false;
        if (!c.getBlock().getRelative(BlockFace.UP).isEmpty()
                && c.getBlock().getRelative(BlockFace.UP).getType() != Material.DRAGON_EGG) return false;
        for (String direction : List.of("N", "E", "S", "W")) {
            Location pedestal = getPedestalLocation(direction);
            if (pedestal == null || pedestal.getWorld() == null || !pedestal.getWorld().getUID().equals(c.getWorld().getUID())
                    || pedestal.getBlock().getType() != Material.BEDROCK) return false;
        }
        return true;
    }

    private void discoverPedestals(Location center) {
        // The End exit fountain has a raised central pillar and a lower bedrock
        // rim. Search each cardinal axis independently, allowing vertical
        // offsets, and store the actual block coordinates.
        int maxRadius = Math.max(3, getConfig().getInt("altar.pedestal-search-radius", 5));
        int minY = getConfig().getInt("altar.pedestal-search-min-y", -5);
        int maxY = getConfig().getInt("altar.pedestal-search-max-y", 2);
        for (String direction : List.of("N", "E", "S", "W")) {
            Location best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int radius = 1; radius <= maxRadius; radius++) {
                int dx = direction.equals("E") ? radius : direction.equals("W") ? -radius : 0;
                int dz = direction.equals("S") ? radius : direction.equals("N") ? -radius : 0;
                for (int dy = minY; dy <= maxY; dy++) {
                    int x = center.getBlockX() + dx;
                    int y = center.getBlockY() + dy;
                    int z = center.getBlockZ() + dz;
                    if (center.getWorld().getBlockAt(x, y, z).getType() != Material.BEDROCK) continue;
                    int score = radius * 10 + Math.abs(dy) * 3;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Location(center.getWorld(), x, y, z);
                    }
                }
            }
            String path = "altar.pedestals." + direction;
            if (best == null) {
                getConfig().set(path, null);
            } else {
                getConfig().set(path + ".x", best.getBlockX());
                getConfig().set(path + ".y", best.getBlockY());
                getConfig().set(path + ".z", best.getBlockZ());
            }
        }
    }

    private Location getPedestalLocation(String direction) {
        String path = "altar.pedestals." + direction;
        if (!getConfig().contains(path + ".x") || !getConfig().contains(path + ".y") || !getConfig().contains(path + ".z")) return null;
        String worldName = getConfig().getString("altar.world", "");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, getConfig().getInt(path + ".x"), getConfig().getInt(path + ".y"), getConfig().getInt(path + ".z"));
    }

    private String missingPedestalDirections(Location center) {
        List<String> missing = new ArrayList<>();
        for (String direction : List.of("N", "E", "S", "W")) {
            Location pedestal = getPedestalLocation(direction);
            if (pedestal == null || !pedestal.getWorld().getUID().equals(center.getWorld().getUID())
                    || pedestal.getBlock().getType() != Material.BEDROCK) missing.add(direction);
        }
        return missing.isEmpty() ? "-" : String.join(", ", missing);
    }

    private void beginRitual(Player initiator) {
        if (ritualRunning || activeBoss != null || finalBossDefeated) return;
        Location altar = center();
        if (altar == null || !initiator.getWorld().getUID().equals(altar.getWorld().getUID())) {
            initiator.sendMessage("§c제단이 있는 엔드 월드에서 소환 버튼을 눌러야 합니다.");
            return;
        }
        if (!vanillaDragonDefeated || hasLivingVanillaDragon(altar.getWorld())) { msg(initiator, "vanilla-dragon-alive"); return; }
        if (!hasAllReservedMaterials()) {
            initiator.sendMessage("§c소환 조건이 부족합니다. " + missingFrameDirections());
            if (!eggPrepared || altar.getBlock().getRelative(BlockFace.UP).getType() != Material.DRAGON_EGG)
                initiator.sendMessage("§c중앙 제단에 용의 알이 설치되어 있지 않습니다.");
            return;
        }
        ritualRunning = true; msg(initiator, "ritual-start");
        ritualTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            ritualRunning = false;
            Location c = center();
            if (c == null || finalBossDefeated || !eggPrepared
                    || hasLivingVanillaDragon(c.getWorld()) || !validateAltarStructureWithEgg(c)
                    || !hasAllReservedMaterials()) {
                getLogger().warning("소환 조건이 변경되어 의식을 취소했습니다. 예약 재료는 인벤토리에 남아 있습니다.");
                if (c != null) msg(initiator, "ritual-cancelled-safe");
                clearPreparation(true);
                return;
            }
            spawnMythicBoss(c);
        }, Math.max(1, getConfig().getLong("altar.ritual-seconds", 10)) * 20L);
    }

    private boolean validateAltarStructureWithEgg(Location c) {
        return c.getBlock().getRelative(BlockFace.UP).getType() == Material.DRAGON_EGG
                && validateAltarStructure(c);
    }

    private void spawnMythicBoss(Location c) {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            getLogger().severe("MythicMobs가 없어 보스를 소환할 수 없습니다.");
            clearPreparation(true);
            return;
        }
        if (!hasAllReservedMaterials()) {
            getLogger().warning("소환 직전 예약 재료가 부족하여 취소했습니다.");
            clearPreparation(true);
            return;
        }
        Set<UUID> before = new HashSet<>();
        for (Entity e : c.getWorld().getNearbyEntities(c, 40, 40, 40))
            if (e instanceof EnderDragon) before.add(e.getUniqueId());

        String mob = getConfig().getString("boss.mythicmobs-internal-name", "AncientDoomDragon");
        Location spawn = c.clone().add(.5, getConfig().getDouble("boss.spawn-y-offset", 2.0), .5);
        String loc = c.getWorld().getName() + "," + spawn.getX() + "," + spawn.getY() + "," + spawn.getZ() + ",0,0";
        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm mobs spawn " + mob + " 1 " + loc);
        if (!dispatched) {
            getLogger().severe("MythicMobs 소환 명령 실행에 실패했습니다.");
            clearPreparation(true);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            List<EnderDragon> found = c.getWorld().getNearbyEntities(c, 40, 40, 40).stream()
                    .filter(e -> e instanceof EnderDragon).map(e -> (EnderDragon) e)
                    .filter(e -> !before.contains(e.getUniqueId())).toList();
            if (found.size() != 1) {
                getLogger().severe("새로 생성된 엔더 드래곤을 하나로 특정하지 못했습니다. 발견 수=" + found.size()
                        + ". 예약 재료는 소비하지 않았습니다.");
                clearPreparation(true);
                return;
            }
            EnderDragon boss = found.get(0);
            // Persist boss ownership before consuming any offered materials. If the
            // server crashes at this boundary, the boss is recoverable and the
            // players' items are still safe (at worst the summon is unpaid).
            activeBoss = boss.getUniqueId();
            boss.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            savePersistentData();

            if (!hasAllReservedMaterials() || !consumePedestalFrames() || !consumeReservedMaterials()) {
                getLogger().severe("보스 생성 후 제단 제물을 확인/소비하지 못했습니다. 해당 보스를 제거합니다.");
                boss.remove();
                activeBoss = null;
                savePersistentData();
                clearPreparation(true);
                return;
            }

            clearPreparationCommitted();
            suppressPortal(c);
            savePersistentData();
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

    private boolean hasItems(Player player, Material type, int amount) {
        if (countItems(player, type) >= amount) return true;
        player.sendMessage("§c필요한 재료가 부족합니다: " + type.name() + " x" + amount);
        return false;
    }

    private int countItems(Player player, Material type) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == type) count += stack.getAmount();
        }
        return count;
    }

    private boolean hasAllReservedMaterials() {
        Location c = center();
        if (!eggPrepared || eggDepositor == null || c == null
                || c.getBlock().getRelative(BlockFace.UP).getType() != Material.DRAGON_EGG) return false;
        int totalEggs = escrowEggs.values().stream().mapToInt(Integer::intValue).sum();
        if (totalEggs != 1) return false;
        for (String direction : List.of("N", "E", "S", "W")) {
            if (!pedestalFrameReady(direction)) return false;
        }
        return true;
    }

    private boolean consumeReservedMaterials() {
        if (!eggPrepared || center() == null
                || center().getBlock().getRelative(BlockFace.UP).getType() != Material.DRAGON_EGG) return false;
        int totalEggs = escrowEggs.values().stream().mapToInt(Integer::intValue).sum();
        if (totalEggs != 1) return false;
        // The four glow frames and the Eyes inside them are removed separately
        // only after a newly spawned MythicMobs boss has been identified.
        escrowEggs.clear();
        savePersistentData();
        return true;
    }

    private boolean removeItems(Player player, Material type, int amount) {
        return player.getInventory().removeItem(new ItemStack(type, amount)).isEmpty();
    }

    private void clearPreparation(boolean removeVisualEgg) {
        cancelRitual();
        refundAllEscrowEyes();
        clearPreparationState(removeVisualEgg);
    }

    private void clearPreparationCommitted() {
        escrowEggs.clear();
        escrowEyes.clear();
        savePersistentData();
        clearPreparationState(true);
    }

    private void clearPreparationState(boolean removeVisualEgg) {
        Location c = center();
        if (removeVisualEgg && c != null) {
            Block egg = c.getBlock().getRelative(BlockFace.UP);
            if (egg.getType() == Material.DRAGON_EGG) egg.setType(Material.AIR, false);
        }
        eggPrepared = false; eggDepositor = null; usedPedestals.clear(); pedestalDepositors.clear(); summonPromptSent = false;
    }

    private String pedestalDirection(Location clicked, Location c, int ignoredOffset) {
        for (String direction : List.of("N", "E", "S", "W")) {
            Location pedestal = getPedestalLocation(direction);
            if (pedestal != null && pedestal.getWorld().getUID().equals(clicked.getWorld().getUID())
                    && pedestal.getBlockX() == clicked.getBlockX()
                    && pedestal.getBlockY() == clicked.getBlockY()
                    && pedestal.getBlockZ() == clicked.getBlockZ()) return direction;
        }
        return null;
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
