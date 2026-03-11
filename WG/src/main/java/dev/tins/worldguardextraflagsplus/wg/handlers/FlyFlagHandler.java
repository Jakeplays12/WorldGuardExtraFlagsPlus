package dev.tins.worldguardextraflagsplus.wg.handlers;

import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.session.handler.FlagValueChangeHandler;
import com.sk89q.worldguard.session.handler.Handler;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import dev.tins.worldguardextraflagsplus.wg.WorldGuardUtils;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import lombok.Getter;
import dev.tins.worldguardextraflagsplus.flags.Flags;
import java.lang.reflect.Method;

public class FlyFlagHandler extends FlagValueChangeHandler<State> {

	public static Factory FACTORY() { return new Factory(); }

	public static class Factory extends Handler.Factory<FlyFlagHandler> {
		@Override
		public FlyFlagHandler create(Session session) { return new FlyFlagHandler(session); }
	}

	@Getter private Boolean currentValue;
	@Setter private Boolean originalFly;

	private static Object essentialsPlugin;
	private static Method getUserMethod;
	private static Method setFlyMethod;
	private static boolean initialized = false;

	private static void setupEssentials() {
		if (initialized) return;
		initialized = true;
		try {
			Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
			if (plugin == null) plugin = Bukkit.getPluginManager().getPlugin("EssentialsX");

			if (plugin != null) {
				essentialsPlugin = plugin;
				getUserMethod = plugin.getClass().getMethod("getUser", Player.class);

				// We search the User class for the best available flight method
				// This avoids the "NoSuchMethod" error by checking what actually exists
				Class<?> userClass = Class.forName("com.earth2me.essentials.User");

				String[] candidates = {"setAllowFlight", "setFly", "setFlying"};
				for (String name : candidates) {
					try {
						// Try primitive boolean first
						setFlyMethod = userClass.getMethod(name, boolean.class);
						break;
					} catch (NoSuchMethodException e) {
						try {
							// Try Object Boolean second
							setFlyMethod = userClass.getMethod(name, Boolean.class);
							break;
						} catch (NoSuchMethodException ignored) {}
					}
				}

				if (setFlyMethod != null) {
					Bukkit.getLogger().info("[FlyFlagHandler] Hooked EssentialsX via: " + setFlyMethod.getName());
				}
			}
		} catch (Exception e) {
			Bukkit.getLogger().warning("[FlyFlagHandler] EssentialsX hook failed: " + e.getMessage());
		}
	}

	protected FlyFlagHandler(Session session) {
		super(session, Flags.FLY);
	}

	@Override
	protected void onInitialValue(LocalPlayer player, ApplicableRegionSet set, State value) {
		this.handleValue(player, player.getWorld(), value);
	}

	@Override
	protected boolean onSetValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet, State currentValue, State lastValue, MoveType moveType) {
		this.handleValue(player, (World) to.getExtent(), currentValue);
		return true;
	}

	@Override
	protected boolean onAbsentValue(LocalPlayer player, Location from, Location to, ApplicableRegionSet toSet, State lastValue, MoveType moveType) {
		this.handleValue(player, (World) to.getExtent(), null);
		return true;
	}

	private void handleValue(LocalPlayer player, World world, State state) {
		Player bukkitPlayer = ((BukkitPlayer) player).getPlayer();
		if (bukkitPlayer == null) return;

		WorldGuardUtils.getScheduler().runAtEntity(bukkitPlayer, task -> {
			if (state == State.ALLOW && !this.getSession().getManager().hasBypass(player, world)) {
				if (this.currentValue == null || !this.currentValue) {
					this.originalFly = bukkitPlayer.getAllowFlight();
					setEssentialsFly(bukkitPlayer, true);
					bukkitPlayer.setAllowFlight(true);
					this.currentValue = true;
				}
			}
			else if (this.currentValue != null || this.originalFly != null) {
				// Use a direct plugin lookup for the scheduler
				Plugin main = Bukkit.getPluginManager().getPlugin("WorldGuardExtraFlagsPlus");
				if (main != null) {
					Bukkit.getScheduler().runTaskLater(main, () -> {
						setEssentialsFly(bukkitPlayer, false);

						boolean restoreValue = (this.originalFly != null) ? this.originalFly : false;
						bukkitPlayer.setAllowFlight(restoreValue);
						if (!restoreValue) bukkitPlayer.setFlying(false);

						this.originalFly = null;
						this.currentValue = null;
					}, 2L);
				}
			}
		});
	}

	private void setEssentialsFly(Player player, boolean state) {
		setupEssentials();
		if (essentialsPlugin == null || getUserMethod == null || setFlyMethod == null) return;

		try {
			Object user = getUserMethod.invoke(essentialsPlugin, player);
			if (user != null) {
				setFlyMethod.invoke(user, state);
			}
		} catch (Exception ignored) {}
	}
}