package com.tropicalwikis.tuxcraft.plugins.miningcash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Random;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.plugin.Plugin;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.ApplicableRegionSet;

/**
 * @author tux
 *
 */
public class MiningCash extends JavaPlugin implements Listener {
    private Economy econ;
    private ArrayList<String> recentlyPlacedOres = new ArrayList<String>();
    private HashMap<String, Double> rewardsHidden = new HashMap<String, Double>();
    private Random rnd = new Random();
    private Logger log = Logger.getLogger("Minecraft");
    private WorldGuardPlugin wgplugin = null;
    private double quotient = 0.0;
    private double bonusQuotient = 1.0;

	@Override
    public void onEnable() {
    	if(!setupEconomy()) {
    		log.severe("Vault not found! Disabling plugin.");
    		getServer().getPluginManager().disablePlugin(this);
    		return;
    	}
    	Plugin wgTmpPlugin = getServer().getPluginManager().getPlugin("WorldGuard");
    	if(wgTmpPlugin == null) {
    		log.info("WorldGuard not found. Region-specific quotients are disabled.");
    	} else {
    		wgplugin = (WorldGuardPlugin)wgTmpPlugin;
    	}
    	this.getDataFolder().mkdirs();
    	getConfig().options().copyDefaults(true);
    	if(!getConfig().contains("reward-quotient")) {
    		quotient = rnd.nextDouble() * 2;
    		getConfig().set("reward-quotient", quotient);
    	} else {
    		quotient = getConfig().getDouble("reward-quotient", 1.0);
    	}
    	if(!getConfig().contains("block-rewards")) {
    		getConfig().set("block-rewards.16", 0.20);
    		getConfig().set("block-rewards.15", 0.45);
    		getConfig().set("block-rewards.14", 0.91);
    		getConfig().set("block-rewards.73", 0.60);
    		getConfig().set("block-rewards.74", 0.60);
    		getConfig().set("block-rewards.21", 0.80);
    		getConfig().set("block-rewards.56", 1.05);
    		getConfig().set("block-rewards.129", 1.20);
    	}
    	this.saveConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }
    
    private double calculateOreReward(int type) {
    	if(getConfig().getBoolean("flat-pay", false)) {
    		return getConfig().getDouble("block-rewards."+type);
    	}
		return getConfig().getDouble("block-rewards."+type) * quotient + ((rnd.nextDouble() / 8 - (rnd.nextDouble() / 16)) % quotient) * bonusQuotient;
    }
    
    private String locationToString(Location l) {
    	return l.getWorld().getName() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
    
    @EventHandler(priority=EventPriority.MONITOR)
    public boolean onBlockPlace(BlockPlaceEvent evt) {
    	if(evt.isCancelled()) {
    		return true;
    	}
    	int type = evt.getBlockPlaced().getTypeId();
    	if(!getConfig().isDouble("block-rewards."+type)) {
    		return true;
    	}
    	if(!getConfig().getBoolean("ignore-riskiness", false)) {
    		recentlyPlacedOres.add(locationToString(evt.getBlock().getLocation()));
    	}
    	return true;
    }
    
    // MiningCash only monitors what happens in the end.
    @EventHandler(priority=EventPriority.MONITOR)
    public boolean onBlockBreak(BlockBreakEvent evt) {
    	if(evt.isCancelled()) {
    		return true;
    	}
    	if(!evt.getPlayer().hasPermission("miningcash.reward")) {
    		return true;
    	}
    	int type = evt.getBlock().getTypeId();
    	if(!getConfig().contains("block-rewards."+type)) {
    		return true;
    	}
    	// Riskness checks
    	if(!getConfig().getBoolean("ignore-riskiness", false)) {
    		if(recentlyPlacedOres.contains(locationToString(evt.getBlock().getLocation()))) {
    			// just return here
    			recentlyPlacedOres.remove(locationToString(evt.getBlock().getLocation()));
    			return true;
    		}
    		if(evt.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE) {
    			return true;
    		}
    	}
    	// Now calculate the reward.
    	double reward = calculateOreReward(type);
		if(wgplugin != null) {
			RegionManager regionManager = wgplugin.getRegionManager(evt.getPlayer().getWorld());
			if(regionManager != null) {
    			ApplicableRegionSet set = regionManager.getApplicableRegions(evt.getBlock().getLocation());
    			for (ProtectedRegion region : set) {
    				if(getConfig().contains("per-region-quotient."+region.getId())) {
    					reward = reward * getConfig().getDouble("per-region-quotient."+region.getId());
    				}
    			}
			}
		}
		econ.depositPlayer(evt.getPlayer().getName(), reward);
		
		if(rewardsHidden.containsKey(evt.getPlayer().getName())) {
			// Silently add to their count.
			double r = rewardsHidden.get(evt.getPlayer().getName());
			r += reward;
			rewardsHidden.remove(evt.getPlayer().getName());
			rewardsHidden.put(evt.getPlayer().getName(), r);
		} else {
			evt.getPlayer().sendMessage(ChatColor.GOLD + getConfig().getString("message-reward").replace("%amount%", econ.format(reward)));
		}
		return true;
    }
    
	private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }
	
	public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
		if(sender.hasPermission("miningcash.reward") && sender instanceof Player) {
			// Reward message hide/show
			try {
				if(args[0].equals("hiderewards")) {
					rewardsHidden.put(sender.getName(), 0.0);
					sender.sendMessage(ChatColor.GOLD + "[MiningCash] Disabled reward messages. Use /mining showrewards to re-enable and display your total.");
					return true;
				}
				if(args[0].equals("showrewards")) {
					sender.sendMessage(ChatColor.GOLD + "[MiningCash] Enabled reward messages. You have mined "+econ.format(rewardsHidden.get(sender.getName()))+" of ore.");
					rewardsHidden.remove(sender.getName());
					return true;
				}
			} catch (ArrayIndexOutOfBoundsException e) {
				// Sliently fallover to the miningcash admin code.
			}
		}
		if(!sender.hasPermission("miningcash.admin")) {
			sender.sendMessage("You do not have permission.");
			return true;
		}
		try {
			if(args[0].equals("estimatereward")) {
				try {
					if(getConfig().isDouble("block-rewards."+args[1])) {
						sender.sendMessage("Base value for block ID "+args[1] + ": "+econ.format(calculateOreReward(Integer.valueOf(args[1]))));
					} else {
						sender.sendMessage("Block ID "+args[1]+" not defined!");
					}
				} catch (ArrayIndexOutOfBoundsException e) {
					sender.sendMessage("Usage: /mining estimatereward BlockID");
				} catch (NumberFormatException e) {
					sender.sendMessage("Invalid block ID!");
				}
			}
			if(args[0].equals("settemporarybonus")) {
				try {
					bonusQuotient = Double.parseDouble(args[1]);
					sender.sendMessage("Temporary bonus set.");
				} catch (ArrayIndexOutOfBoundsException e) {
					sender.sendMessage("Usage: /mining settemporarybonus Bonus.Value");
				} catch (NumberFormatException e) {
					sender.sendMessage("Invalid number!");
				}
			}
			if(args[0].equals("setbasequotient")) {
				try {
					quotient = Double.parseDouble(args[1]);
					getConfig().set("reward-quotient", quotient);
					sender.sendMessage("Base quotient set.");
					this.saveConfig();
				} catch (ArrayIndexOutOfBoundsException e) {
					sender.sendMessage("Usage: /mining setbasequotient Quotient.Value");
				} catch (NumberFormatException e) {
					sender.sendMessage("Invalid number!");
				}
			}
			if(args[0].equals("setmineralquotient")) {
				try {
					int bid = Integer.parseInt(args[1]);
					double quotient = Double.parseDouble(args[2]);
					getConfig().set("block-rewards."+bid, quotient);
					sender.sendMessage("Quotient of block ID "+bid+" set.");
					this.saveConfig();
				} catch (ArrayIndexOutOfBoundsException e) {
					sender.sendMessage("Usage: /mining setmineralquotient BlockID Quotient.Value");
				} catch (NumberFormatException e) {
					sender.sendMessage("Invalid number!");
				}
			}
			if(args[0].equals("reload")) {
				reloadConfig();
				sender.sendMessage("Reloaded MiningCash configuration.");
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			sender.sendMessage("/mining estimatereward/settemporarybonus/setbasequotient/setmineralquotient/reload");
		}
		return true;
	}

}
