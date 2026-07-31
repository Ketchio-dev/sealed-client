package dev.sealedclient.platform;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity access whose spelling moves between versions.
 *
 * <p>Same reason as the other adapters in this package: each of these is a
 * rename rather than a behaviour change, and keeping the old name at the call
 * sites turns a one-line upstream edit into a compile error in every module
 * that touched it.</p>
 */
public final class EntityAccess {
    private EntityAccess() {
    }

    /** The damage resistance effect, renamed from {@code DAMAGE_RESISTANCE}. */
    public static Holder<MobEffect> resistanceEffect() {
        return MobEffects.DAMAGE_RESISTANCE;
    }

    /**
     * Worn armour pieces.
     *
     * <p>{@code getArmorSlots} stopped returning an iterable of stacks, so this
     * walks the armour slots directly. Empty slots are skipped, which every
     * caller wanted anyway.</p>
     */
    public static List<ItemStack> armorPieces(LivingEntity entity) {
        List<ItemStack> worn = new ArrayList<>(4);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack piece = entity.getItemBySlot(slot);
            if (!piece.isEmpty()) {
                worn.add(piece);
            }
        }
        return worn;
    }

    /**
     * Teleports an entity without interpolating, renamed from
     * {@code absMoveTo}.
     */
    public static void snapTo(
            Entity entity,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        entity.absMoveTo(x, y, z, yaw, pitch);
    }
}
