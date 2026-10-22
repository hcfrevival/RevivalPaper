package gg.hcfrevival.revival.auth.view;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Whether a vanilla client would have predicted a block change (design §15.2). The client predicts in
 * {@code MultiPlayerGameMode.useItemOn} (a {@link BlockItem} placing itself) and {@code startDestroyBlock}/
 * {@code continueDestroyBlock} (a break), always on the block its crosshair is on ({@code Minecraft.hitResult}, the same
 * pick the attack check replays). A change the server made for a sequence the client could not have predicted reaches
 * the truth only through its own block update, never early as a prediction.
 */
public final class PredictionRules {

    private PredictionRules() {
    }

    /** {@code BlockItem.useOn}: the client places only with a block item in that hand. */
    public static boolean canPlace(final ItemStack held) {
        return !held.isEmpty() && held.getItem() instanceof BlockItem;
    }

    /** The client's pick must be on the block the packet names. */
    public static boolean onCrosshair(final boolean blockHit, final int pickX, final int pickY, final int pickZ, final int x, final int y, final int z) {
        return blockHit && pickX == x && pickY == y && pickZ == z;
    }
}
