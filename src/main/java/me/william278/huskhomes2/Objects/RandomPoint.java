package me.william278.huskhomes2.Objects;

import me.william278.huskhomes2.HuskHomes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Random;

public class RandomPoint extends TeleportationPoint {

    //List of all the unsafe blocks
    final static HashSet<Material> unsafeBlocks = new HashSet<>();

    static{
        unsafeBlocks.add(Material.LAVA);
        unsafeBlocks.add(Material.FIRE);
        unsafeBlocks.add(Material.CACTUS);
        unsafeBlocks.add(Material.WATER);
        unsafeBlocks.add(Material.MAGMA_BLOCK);
        unsafeBlocks.add(Material.JUNGLE_LEAVES);
        unsafeBlocks.add(Material.SPRUCE_LEAVES);
    }

    private Location randomLocation(World world) {
        //Generate a random location
        Random random = new Random();

        int x;
        int z;
        int y;
        int negativeX;
        int negativeZ;

        x = random.nextInt(HuskHomes.settings.getRtpRange());
        z = random.nextInt(HuskHomes.settings.getRtpRange());
        negativeX = random.nextInt(2);
        negativeZ = random.nextInt(2);

        if (negativeX == 1) {
            x = x * -1;
        }
        if (negativeZ == 1)
            z = z * -1;
        y = 150;

        Location randomLocation = new Location(world, x, y, z);
        y = randomLocation.getWorld().getHighestBlockYAt(randomLocation);
        randomLocation.setY(y);

        return randomLocation;
    }

    private boolean isLocationSafe(Location location){
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        //Get instances of the blocks around where the player would spawn
        Block block = location.getWorld().getBlockAt(x, y, z);
        Block below = location.getWorld().getBlockAt(x, y - 1, z);
        Block above = location.getWorld().getBlockAt(x, y + 1, z);

        //Check to see if the surroundings are safe or not
        return !(unsafeBlocks.contains(below.getType())) || (block.getType().isSolid()) || (above.getType().isSolid());
    }

    private Location getRandomLocation(World world) {
        Location randomLocation = randomLocation(world);

        while (!isLocationSafe(randomLocation)){

            //Keep looking for a safe location
            randomLocation = randomLocation(world);
        }
        return randomLocation;
    }

    public RandomPoint(Player player) {
        super(player.getLocation(), HuskHomes.settings.getServerID());
        setLocation(getRandomLocation(player.getWorld()), HuskHomes.settings.getServerID());
    }

}