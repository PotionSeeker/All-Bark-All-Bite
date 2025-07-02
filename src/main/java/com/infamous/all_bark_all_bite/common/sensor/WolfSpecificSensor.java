package com.infamous.all_bark_all_bite.common.sensor;

import com.google.common.collect.ImmutableSet;
import com.infamous.all_bark_all_bite.common.ABABTags;
import com.infamous.all_bark_all_bite.common.entity.SharedWolfAi;
import com.infamous.all_bark_all_bite.common.util.ai.AiUtil;
import com.infamous.all_bark_all_bite.common.entity.wolf.WolfAi;
import com.infamous.all_bark_all_bite.common.registry.ABABMemoryModuleTypes;
import com.infamous.all_bark_all_bite.common.util.ai.GenericAi;
import com.infamous.all_bark_all_bite.config.ABABConfig;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.Set;

public class WolfSpecificSensor extends Sensor<Wolf> {

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(
                MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                ABABMemoryModuleTypes.NEAREST_VISIBLE_DISLIKED.get(),
                ABABMemoryModuleTypes.NEAREST_VISIBLE_HUNTABLE.get(),
                ABABMemoryModuleTypes.NEAREST_TARGETABLE_PLAYER_NOT_SNEAKING.get(),
                MemoryModuleType.NEAREST_ATTACKABLE,
                ABABMemoryModuleTypes.IS_FLEEING.get(),
                MemoryModuleType.TEMPTING_PLAYER);
    }

    @Override
    protected void doTick(ServerLevel level, Wolf wolf) {
        Brain<?> brain = wolf.getBrain();
        float attackRange = wolf.level().isNight() ? SharedWolfAi.ATTACK_RANGE_NIGHT : SharedWolfAi.ATTACK_RANGE_DAY;

        Optional<Player> nearestPlayerNotSneaking = Optional.empty();
        Optional<LivingEntity> nearestDisliked = Optional.empty();
        Optional<LivingEntity> nearestVisibleHuntable = Optional.empty();
        Optional<LivingEntity> nearestAttackable = Optional.empty();
        Optional<Player> temptingPlayer = Optional.empty();

        NearestVisibleLivingEntities nvle = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES).orElse(NearestVisibleLivingEntities.empty());

        for (LivingEntity livingEntity : nvle.findAll((le) -> true)) {
            if (livingEntity instanceof Player player) {
                // Check if player is the owner of a tamed wolf in the pack
                boolean isPackOwner = GenericAi.getNearbyAdults(wolf).stream()
                        .filter(w -> w.isTame() && w.isOwnedBy(player))
                        .findAny()
                        .isPresent();

                // Handle MEET behavior for crouching players with tempting items
                if (!isPackOwner && !player.isSpectator() && player.isDiscrete() && (player.getMainHandItem().is(ABABTags.WOLF_LOVED) || player.getMainHandItem().is(ABABTags.WOLF_FOOD))) {
                    temptingPlayer = Optional.of(player);
                    brain.setMemory(MemoryModuleType.TEMPTING_PLAYER, player);
                    brain.eraseMemory(MemoryModuleType.AVOID_TARGET);
                    brain.eraseMemory(ABABMemoryModuleTypes.IS_FLEEING.get());
                    brain.setMemoryWithExpiry(ABABMemoryModuleTypes.HOWL_LOCATION.get(), GlobalPos.of(wolf.level().dimension(), player.blockPosition()), SharedWolfAi.HOWL_EXPIRE_TIME);
                    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                    brain.eraseMemory(MemoryModuleType.PATH);
                    continue;
                }
                // Handle AVOID behavior
                if (!isPackOwner && brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                        && brain.getMemory(MemoryModuleType.AVOID_TARGET).map(target -> target == player).orElse(false)) {
                    if (!player.closerThan(wolf, SharedWolfAi.DESIRED_DISTANCE_FROM_ENTITY_WHEN_AVOIDING)) {
                        brain.eraseMemory(MemoryModuleType.AVOID_TARGET);
                        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
                        brain.eraseMemory(ABABMemoryModuleTypes.IS_FLEEING.get());
                    } else if (player.closerThan(wolf, attackRange) && WolfAi.isTargetablePlayerNotSneaking(wolf, player)) {
                        brain.eraseMemory(MemoryModuleType.AVOID_TARGET);
                        brain.eraseMemory(ABABMemoryModuleTypes.IS_FLEEING.get());
                        brain.setMemory(MemoryModuleType.ATTACK_TARGET, player);
                        brain.setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, player.getUUID(), SharedWolfAi.ANGER_DURATION.sample(wolf.getRandom()));
                    }
                }
                // Clear ATTACK_TARGET and ANGRY_AT if player is out of attack range or not targetable
                if (!isPackOwner && brain.hasMemoryValue(MemoryModuleType.ANGRY_AT)
                        && brain.getMemory(MemoryModuleType.ANGRY_AT).map(uuid -> uuid.equals(player.getUUID())).orElse(false)
                        && !brain.getMemory(MemoryModuleType.HURT_BY_ENTITY).map(entity -> entity == player).orElse(false)) {
                    boolean isCrouchingWithTemptingItem = player.isDiscrete() && (player.getMainHandItem().is(ABABTags.WOLF_LOVED) || player.getMainHandItem().is(ABABTags.WOLF_FOOD));
                    if (!player.closerThan(wolf, attackRange) || !WolfAi.isTargetablePlayerNotSneaking(wolf, player)) {
                        brain.eraseMemory(MemoryModuleType.ANGRY_AT);
                        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
                        if (isCrouchingWithTemptingItem && player.closerThan(wolf, SharedWolfAi.MAX_FLEE_DISTANCE)) {
                            SharedWolfAi.setDirectedAvoidTarget(wolf, player, SharedWolfAi.AVOID_DURATION.sample(wolf.getRandom()));
                            brain.setMemoryWithExpiry(ABABMemoryModuleTypes.IS_FLEEING.get(), true, SharedWolfAi.AVOID_DURATION.sample(wolf.getRandom()));
                        }
                    }
                }
                // Only set NEAREST_TARGETABLE_PLAYER_NOT_SNEAKING if not fleeing, not pack owner, and player is targetable
                if (!isPackOwner && nearestPlayerNotSneaking.isEmpty() && WolfAi.isTargetablePlayerNotSneaking(wolf, player) && !brain.hasMemoryValue(ABABMemoryModuleTypes.IS_FLEEING.get())
                        && !player.isDiscrete() && !(player.getMainHandItem().is(ABABTags.WOLF_LOVED) || player.getMainHandItem().is(ABABTags.WOLF_FOOD))) {
                    nearestPlayerNotSneaking = Optional.of(player);
                }
            } else if (nearestDisliked.isEmpty() && WolfAi.isDisliked(livingEntity)) {
                nearestDisliked = Optional.of(livingEntity);
            } else if (nearestVisibleHuntable.isEmpty() && isHuntable(wolf, livingEntity)) {
                nearestVisibleHuntable = Optional.of(livingEntity);
            } else if (nearestAttackable.isEmpty() && isAttackable(wolf, livingEntity)) {
                nearestAttackable = Optional.of(livingEntity);
            }
        }

        brain.setMemory(ABABMemoryModuleTypes.NEAREST_TARGETABLE_PLAYER_NOT_SNEAKING.get(), nearestPlayerNotSneaking);
        brain.setMemory(ABABMemoryModuleTypes.NEAREST_VISIBLE_DISLIKED.get(), nearestDisliked);
        brain.setMemory(ABABMemoryModuleTypes.NEAREST_VISIBLE_HUNTABLE.get(), nearestVisibleHuntable);
        brain.setMemory(MemoryModuleType.NEAREST_ATTACKABLE, nearestAttackable);
        brain.setMemory(MemoryModuleType.TEMPTING_PLAYER, temptingPlayer);
    }

    private static boolean isAttackable(Wolf wolf, LivingEntity livingEntity) {
        return livingEntity.getType().is(ABABTags.WOLF_ALWAYS_HOSTILES) && AiUtil.isClose(wolf, livingEntity, ABABConfig.wolfTargetDetectionDistance.get())
                && AiUtil.isAttackable(wolf, livingEntity, true) && SharedWolfAi.wantsToAttack(wolf, livingEntity, wolf.getOwner());
    }

    private static boolean isHuntable(Wolf wolf, LivingEntity livingEntity) {
        return livingEntity.getType().is(ABABTags.WOLF_HUNT_TARGETS) && AiUtil.isClose(wolf, livingEntity, ABABConfig.wolfTargetDetectionDistance.get())
                && AiUtil.isAttackable(wolf, livingEntity, true) && SharedWolfAi.wantsToAttack(wolf, livingEntity, wolf.getOwner());
    }
}