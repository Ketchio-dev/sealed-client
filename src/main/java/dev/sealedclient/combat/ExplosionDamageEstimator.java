package dev.sealedclient.combat;

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

public final class ExplosionDamageEstimator {
    public static final double END_CRYSTAL_POWER = 6.0;
    private static final int EXPOSURE_STEPS = 2;

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
        if (power <= 0.0 || exposure <= 0.0) {
            return 0.0;
        }
        double impact = (1.0 - distance / (power * 2.0))
                * Math.max(0.0, Math.min(1.0, exposure));
        if (impact <= 0.0) {
            return 0.0;
        }
        return ((impact * impact + impact) * 0.5 * 7.0 * power * 2.0) + 1.0;
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
        if (damage <= 0.0) {
            return 0.0;
        }
        double armorTerm = Math.max(
                armor / 5.0,
                armor - damage / (2.0 + toughness / 4.0)
        );
        double afterArmor = damage
                * (1.0 - Math.min(20.0, Math.max(0.0, armorTerm)) / 25.0);
        double resistanceMultiplier = 1.0
                - Math.min(0.8, Math.max(0, resistanceLevel) * 0.2);
        double protectionMultiplier = 1.0
                - Math.min(20, Math.max(0, protectionPoints)) / 25.0;
        return Math.max(0.0, afterArmor * resistanceMultiplier * protectionMultiplier);
    }

    private static double sampleExposure(
            ClientLevel level,
            LivingEntity entity,
            AABB box,
            Vec3 explosion
    ) {
        int visible = 0;
        int samples = 0;
        for (int xi = 0; xi < EXPOSURE_STEPS; xi++) {
            double x = interpolate(box.minX, box.maxX, xi);
            for (int yi = 0; yi < EXPOSURE_STEPS; yi++) {
                double y = interpolate(box.minY, box.maxY, yi);
                for (int zi = 0; zi < EXPOSURE_STEPS; zi++) {
                    double z = interpolate(box.minZ, box.maxZ, zi);
                    Vec3 sample = new Vec3(x, y, z);
                    HitResult hit = level.clip(new ClipContext(
                            sample,
                            explosion,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE,
                            entity
                    ));
                    if (hit.getType() == HitResult.Type.MISS) {
                        visible++;
                    }
                    samples++;
                }
            }
        }
        return samples == 0 ? 0.0 : (double) visible / samples;
    }

    private static double interpolate(double minimum, double maximum, int step) {
        if (EXPOSURE_STEPS <= 1) {
            return (minimum + maximum) * 0.5;
        }
        double padding = 0.05;
        double start = minimum + Math.min(padding, (maximum - minimum) * 0.25);
        double end = maximum - Math.min(padding, (maximum - minimum) * 0.25);
        return start + (end - start) * step / (EXPOSURE_STEPS - 1.0);
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
