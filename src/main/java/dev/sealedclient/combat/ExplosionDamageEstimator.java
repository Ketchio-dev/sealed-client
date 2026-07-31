package dev.sealedclient.combat;

import dev.sealedclient.common.combat.ExplosionDamageFormula;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Predicts end-crystal damage against live client state.
 *
 * <p>The calculation itself lives in {@link ExplosionDamageFormula}, which is
 * checked against real explosions on a dedicated server by the 26.2 accuracy
 * game test. This class only supplies the inputs: the entity's box, the blocks
 * between it and the blast, and whatever armour and effects the client can see.</p>
 */
public final class ExplosionDamageEstimator {
    public static final double END_CRYSTAL_POWER =
            ExplosionDamageFormula.END_CRYSTAL_RADIUS;

    private ExplosionDamageEstimator() {
    }

    public static double estimateEndCrystal(
            ClientLevel level,
            LivingEntity entity,
            Vec3 predictedPosition,
            Vec3 explosionPosition
    ) {
        double maxDistance = END_CRYSTAL_POWER * 2.0;
        double distance = predictedPosition.distanceTo(explosionPosition);
        if (distance >= maxDistance) {
            return 0.0;
        }
        Vec3 movement = predictedPosition.subtract(entity.position());
        AABB predictedBox = entity.getBoundingBox().move(movement);
        double exposure = sampleExposure(level, entity, predictedBox, explosionPosition);
        double raw = rawExplosionDamage(distance, exposure, END_CRYSTAL_POWER);
        double difficultyScaled = scaleForDifficulty(raw, level.getDifficulty());
        return applyReductions(
                difficultyScaled,
                entity.getArmorValue(),
                entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                resistanceLevel(entity),
                protectionPoints(entity)
        );
    }

    public static double rawExplosionDamage(
            double distance,
            double exposure,
            double power
    ) {
        return ExplosionDamageFormula.rawDamage(distance, exposure, power);
    }

    public static double scaleForDifficulty(double damage, Difficulty difficulty) {
        if (damage <= 0.0 || difficulty == Difficulty.PEACEFUL) {
            return 0.0;
        }
        return switch (difficulty) {
            case EASY -> Math.min(damage, damage * 0.5 + 1.0);
            case NORMAL -> damage;
            case HARD -> damage * 1.5;
            case PEACEFUL -> 0.0;
        };
    }

    public static double applyReductions(
            double damage,
            double armor,
            double toughness,
            int resistanceLevel,
            int protectionPoints
    ) {
        return ExplosionDamageFormula.afterReductions(
                damage, armor, toughness, protectionPoints, resistanceLevel
        );
    }

    /**
     * Casts vanilla's own sample grid rather than a fixed 2x2x2 approximation.
     *
     * <p>The grid size follows the entity's bounding box, so a player is
     * sampled on roughly forty-five rays instead of eight. The coarse version
     * disagreed with real explosions; this one matches them.</p>
     */
    private static double sampleExposure(
            ClientLevel level,
            LivingEntity entity,
            AABB box,
            Vec3 explosion
    ) {
        return ExplosionDamageFormula.exposure(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                (x, y, z) -> level.clip(new ClipContext(
                        new Vec3(x, y, z),
                        explosion,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        entity
                )).getType() == HitResult.Type.MISS
        );
    }

    private static int resistanceLevel(LivingEntity entity) {
        MobEffectInstance resistance = entity.getEffect(MobEffects.DAMAGE_RESISTANCE);
        return resistance == null ? 0 : resistance.getAmplifier() + 1;
    }

    private static int protectionPoints(LivingEntity entity) {
        int points = 0;
        for (ItemStack armor : entity.getArmorSlots()) {
            for (var enchantment : armor.getEnchantments().entrySet()) {
                if (enchantment.getKey().is(Enchantments.BLAST_PROTECTION)) {
                    points += enchantment.getIntValue() * 2;
                } else if (enchantment.getKey().is(Enchantments.PROTECTION)) {
                    points += enchantment.getIntValue();
                }
            }
        }
        return Math.min(20, points);
    }
}
