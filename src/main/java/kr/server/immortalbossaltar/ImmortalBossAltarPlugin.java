package kr.server.immortalbossaltar;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EnderDragonChangePhaseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public final class ImmortalBossAltarPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey bossKey;
    private NamespacedKey rewardKey;
    private UUID activeBoss;
    private UUID lastDamager;
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Set<String> usedPedestals = new HashSet<>();
    private boolean eggPrepared;
    private boolean ritualRunning;
    private boolean vanillaDragonDefeated;
    private BukkitTask ritualTask;
    private final Map<Location, BlockState> portalSnapshot = new LinkedHashMap<>();
    private boolean portalSuppressed;

    @Override public void onEnable() {
        saveDefaultConfig();
        bossKey = new NamespacedKey(this, "tracked_ancient_doom_dragon");
        rewardKey = new NamespacedKey(this, "reward_id");
        Objects.requireNonNull(getCommand("bossaltar")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().warning("0.1.0 초기 개발판입니다. 운영 서버 적용 전 반드시 테스트 서버에서 검증하세요.");
    }

    @Override public void onDisable() {
        if (ritualTask != null) ritualTask.cancel();
        // Never leave a portal suppressed just because the plugin was disabled.
        if (portalSuppressed) restorePortal();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("immortalbossaltar.admin")) {
            sender.sendMessage("§c플레이어 관리자만 사용할 수 있습니다."); return true;
        }
        if (args.length == 0) { player.sendMessage("§e/bossaltar setcenter|status|reset|reload"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setcenter" -> {
                if (player.getWorld().getEnvironment() != World.Environment.THE_END) { msg(player, "end-only"); return true; }
                Location l = player.getLocation().getBlock().getLocation();
                getConfig().set("altar.world", l.getWorld().getName());
                getConfig().set("altar.center.x", l.getBlockX()); getConfig().set("altar.center.y", l.getBlockY()); getConfig().set("altar.center.z", l.getBlockZ());
                saveConfig(); player.sendMessage("§a제단 중심을 " + l.getBlockX()+", "+l.getBlockY()+", "+l.getBlockZ()+" 로 저장했습니다.");
            }
            case "status" -> player.sendMessage(color("status", Map.of("altar", center() == null ? "미설정" : "설정됨", "boss", activeBoss == null ? "없음" : "생존", "defeated", vanillaDragonDefeated ? "예" : "아니오")));
            case "reset" -> { cancelRitual(); eggPrepared=false; usedPedestals.clear(); damage.clear(); lastDamager=null; player.sendMessage("§a제단 진행 상태를 초기화했습니다. 월드 블록은 변경하지 않았습니다."); }
            case "reload" -> { reloadConfig(); player.sendMessage("§a설정을 다시 불러왔습니다."); }
            default -> player.sendMessage("§e/bossaltar setcenter|status|reset|reload");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon && dragon.getWorld().getEnvironment() == World.Environment.THE_END && !isTrackedBoss(dragon)) {
            vanillaDragonDefeated = true;
            getLogger().info("바닐라 엔더 드래곤 처치가 감지되었습니다.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCrystalPlace(BlockPlaceEvent event) {
        if (activeBoss == null || event.getBlockPlaced().getType() != Material.END_CRYSTAL) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("§c커스텀 보스가 살아 있는 동안에는 바닐라 드래곤을 소환할 수 없습니다.");
        // Placed crystal is returned by cancelling the event; no item was consumed.
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Location center = center(); if (center == null || !sameWorld(event.getClickedBlock().getWorld(), center.getWorld())) return;
        Player p = event.getPlayer(); Block clicked = event.getClickedBlock();
        if (p.getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (clicked.getLocation().equals(center)) {
            if (clicked.getType() == Material.DRAGON_EGG) {
                event.setCancelled(true);
                if (!eggPrepared || usedPedestals.size() != 4) { msg(p,"altar-incomplete"); return; }
                beginRitual(p); return;
            }
            if (p.getInventory().getItemInMainHand().getType() == Material.DRAGON_EGG && !eggPrepared && activeBoss == null) {
                event.setCancelled(true);
                if (!vanillaDragonDefeated || hasLivingVanillaDragon(center.getWorld())) { msg(p,"dragon-not-defeated"); return; }
                if (!takeOne(p, Material.DRAGON_EGG)) return;
                clicked.setType(Material.DRAGON_EGG, false); eggPrepared=true; msg(p,"egg-placed");
            }
            return;
        }
        int offset = getConfig().getInt("altar.pedestal-offset",2);
        String direction = pedestalDirection(clicked.getLocation(), center, offset);
        if (direction == null || activeBoss != null || ritualRunning || usedPedestals.contains(direction)) return;
        Material expected = Material.matchMaterial(getConfig().getString("altar.pedestal-material", "END_STONE_BRICKS"));
        if (expected != null && clicked.getType() != expected) return;
        if (p.getInventory().getItemInMainHand().getType() != Material.ENDER_EYE) return;
        event.setCancelled(true);
        if (!eggPrepared) { msg(p,"altar-incomplete"); return; }
        if (takeOne(p, Material.ENDER_EYE)) {
            usedPedestals.add(direction); clicked.getWorld().spawnParticle(Particle.PORTAL, clicked.getLocation().add(.5,1,.5), 45, .35,.5,.35,.08);
            msg(p,"eye-placed", Map.of("count", String.valueOf(usedPedestals.size())));
        }
    }

    private void beginRitual(Player initiator) {
        if (ritualRunning || activeBoss != null) return;
        if (!vanillaDragonDefeated || hasLivingVanillaDragon(initiator.getWorld())) { msg(initiator,"vanilla-dragon-alive"); return; }
        ritualRunning=true; msg(initiator,"ritual-start");
        ritualTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            ritualRunning=false;
            Location c=center();
            if (c == null || !eggPrepared || usedPedestals.size()!=4 || hasLivingVanillaDragon(c.getWorld())) { getLogger().warning("소환 조건이 변경되어 의식을 취소했습니다."); return; }
            spawnMythicBoss(c);
        }, Math.max(1,getConfig().getLong("altar.ritual-seconds",10))*20L);
    }

    private void spawnMythicBoss(Location center) {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) { getLogger().severe("MythicMobs가 없어 보스를 소환할 수 없습니다."); return; }
        Set<UUID> before = new HashSet<>(); for (Entity e : center.getWorld().getNearbyEntities(center, 32,32,32)) if (e instanceof EnderDragon) before.add(e.getUniqueId());
        String mob = getConfig().getString("boss.mythicmobs-internal-name", "AncientDoomDragon");
        Location spawn = center.clone().add(.5, getConfig().getDouble("boss.spawn-y-offset",2), .5);
        String loc = center.getWorld().getName()+","+spawn.getX()+","+spawn.getY()+","+spawn.getZ();
        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm mobs spawn " + mob + " 1 " + loc);
        if (!accepted) { getLogger().severe("MythicMobs 소환 명령이 거부되었습니다: " + mob); return; }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            List<EnderDragon> found = center.getWorld().getNearbyEntities(center,32,32,32).stream()
                    .filter(e -> e instanceof EnderDragon).map(e -> (EnderDragon)e).filter(e -> !before.contains(e.getUniqueId())).toList();
            if (found.size()!=1) { getLogger().severe("새로 생성된 엔더 드래곤을 하나로 특정하지 못했습니다. 발견 수="+found.size()+"; 보상 추적을 시작하지 않았습니다."); return; }
            EnderDragon boss=found.get(0); activeBoss=boss.getUniqueId(); boss.getPersistentDataContainer().set(bossKey,PersistentDataType.BYTE,(byte)1);
            eggPrepared=false; usedPedestals.clear(); suppressPortal(center);
            for (Player p : center.getWorld().getPlayers()) msg(p,"boss-spawned");
        }, 2L);
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onBossDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !isTrackedBoss(dragon)) return;
        Player attacker = responsiblePlayer(event.getDamager()); if (attacker == null) return;
        double actual = Math.min(event.getFinalDamage(), Math.max(0,dragon.getHealth()));
        if (actual <= 0) return;
        damage.merge(attacker.getUniqueId(),actual,Double::sum); lastDamager=attacker.getUniqueId();
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !isTrackedBoss(dragon)) return;
        event.getDrops().removeIf(item -> item.getType()==Material.GOLDEN_SWORD);
        UUID killer=lastDamager; Player winner=killer==null?null:Bukkit.getPlayer(killer);
        if (winner!=null && winner.isOnline()) giveReward(winner);
        else getLogger().warning("보스가 처치되었지만 온라인 막타 플레이어를 찾지 못했습니다. UUID="+killer);
        double total=damage.values().stream().mapToDouble(Double::doubleValue).sum();
        for (Player p : dragon.getWorld().getPlayers()) {
            msg(p,"boss-defeated"); msg(p,"contribution-header");
            damage.entrySet().stream().sorted(Map.Entry.<UUID,Double>comparingByValue().reversed()).forEach(entry -> {
                OfflinePlayer op=Bukkit.getOfflinePlayer(entry.getKey()); double pct=total<=0?0:entry.getValue()*100.0/total;
                msg(p,"contribution-line",Map.of("player",op.getName()==null?entry.getKey().toString().substring(0,8):op.getName(),"damage",String.format(Locale.US,"%.1f",entry.getValue()),"percent",String.format(Locale.US,"%.1f",pct)));
            });
        }
        activeBoss=null; lastDamager=null; damage.clear(); restorePortal();
    }

    private void giveReward(Player player) {
        String itemName=getConfig().getString("reward.mythicmobs-item-internal-name","EndDoomScoreSword");
        // Use MythicMobs' item give command so the existing configured NBT, lore and unbreakable flags remain authoritative.
        boolean ok=Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm items give "+player.getName()+" "+itemName+" 1");
        if (ok) msg(player,"reward-received"); else getLogger().severe("종언의 심판 지급 명령이 실패했습니다. 플레이어="+player.getName());
    }

    private void suppressPortal(Location center) {
        portalSnapshot.clear(); int r=getConfig().getInt("portal.scan-radius",2);
        for(int x=-r;x<=r;x++) for(int y=-r;y<=r;y++) for(int z=-r;z<=r;z++) {
            Block b=center.getWorld().getBlockAt(center.getBlockX()+x,center.getBlockY()+y,center.getBlockZ()+z);
            if (b.getType()==Material.END_PORTAL) { portalSnapshot.put(b.getLocation(),b.getState()); b.setType(Material.AIR,false); }
        }
        portalSuppressed=!portalSnapshot.isEmpty();
    }
    private void restorePortal() {
        for (Map.Entry<Location,BlockState> e : portalSnapshot.entrySet()) {
            Block b=e.getKey().getBlock(); if (b.getType()==Material.AIR) b.setType(Material.END_PORTAL,false);
        }
        portalSnapshot.clear(); portalSuppressed=false;
    }

    private boolean isTrackedBoss(EnderDragon dragon) {
        return dragon.getPersistentDataContainer().has(bossKey,PersistentDataType.BYTE) || (activeBoss!=null && activeBoss.equals(dragon.getUniqueId()));
    }
    private boolean hasLivingVanillaDragon(World world) {
        return world.getEntitiesByClass(EnderDragon.class).stream().anyMatch(d -> !isTrackedBoss(d) && d.isValid() && !d.isDead());
    }
    private Player responsiblePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile) { ProjectileSource shooter=projectile.getShooter(); if (shooter instanceof Player p) return p; }
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player p) return p;
        return null;
    }
    private boolean takeOne(Player p, Material type) {
        ItemStack hand=p.getInventory().getItemInMainHand();
        if (hand.getType()!=type || hand.getAmount()<1) return false;
        if (hand.getAmount()==1) p.getInventory().setItemInMainHand(null); else hand.setAmount(hand.getAmount()-1);
        return true;
    }
    private String pedestalDirection(Location loc, Location c, int d) {
        int dx=loc.getBlockX()-c.getBlockX(), dz=loc.getBlockZ()-c.getBlockZ(), dy=loc.getBlockY()-c.getBlockY();
        if(dy!=0) return null; if(dx==0&&dz==-d)return "N"; if(dx==d&&dz==0)return "E"; if(dx==0&&dz==d)return "S"; if(dx==-d&&dz==0)return "W"; return null;
    }
    private Location center() {
        String world=getConfig().getString("altar.world",""); if(world==null||world.isBlank()) return null;
        World w=Bukkit.getWorld(world); if(w==null)return null;
        return new Location(w,getConfig().getInt("altar.center.x"),getConfig().getInt("altar.center.y"),getConfig().getInt("altar.center.z"));
    }
    private boolean sameWorld(World a, World b) { return a!=null&&b!=null&&a.getUID().equals(b.getUID()); }
    private void cancelRitual() { if(ritualTask!=null)ritualTask.cancel(); ritualTask=null; ritualRunning=false; }
    private void msg(Player p,String key) { msg(p,key,Map.of()); }
    private void msg(Player p,String key,Map<String,String> values) { p.sendMessage(color(key,values)); }
    private String color(String key,Map<String,String> values) {
        String s=getConfig().getString("messages."+key,"&cMissing message: "+key);
        for(Map.Entry<String,String> e:values.entrySet())s=s.replace("{"+e.getKey()+"}",e.getValue());
        return ChatColor.translateAlternateColorCodes('&',getConfig().getString("messages.prefix"," ")+s);
    }
}
