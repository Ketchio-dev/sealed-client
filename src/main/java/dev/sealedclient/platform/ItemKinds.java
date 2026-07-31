package dev.sealedclient.platform;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * Item categories, asked by tag rather than by class.
 *
 * <p>{@code SwordItem} and {@code PickaxeItem} were folded into the component
 * system and no longer exist as types on later versions, so
 * {@code instanceof SwordItem} stops compiling. The tags they were replaced by
 * exist on every version this client targets, and they are also more correct:
 * a modded or datapack sword is in {@code #swords} without extending the
 * vanilla class.</p>
 */
public final class ItemKinds {
    private ItemKinds() {
    }

    /** Whether the stack counts as a sword. */
    public static boolean isSword(ItemStack stack) {
        return stack.is(ItemTags.SWORDS);
    }

    /** Whether the stack counts as a pickaxe. */
    public static boolean isPickaxe(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES);
    }
}
