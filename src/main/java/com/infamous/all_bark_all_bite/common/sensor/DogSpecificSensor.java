package com.infamous.all_bark_all_bite.common.sensor;

import com.google.common.collect.ImmutableSet;
import com.infamous.all_bark_all_bite.common.ABABTags;
import com.infamous.all_bark_all_bite.common.util.ai.GenericAi;
import com.infamous.all_bark_all_bite.config.ABABConfig;
import com.infamous.all_bark_all_bite.common.entity.SharedWolfAi;
import com.infamous.all_bark_all_bite.common.entity.dog.Dog;
import com.infamous.all_bark_all_bite.common.entity.dog.DogAi;
import com.infamous.all_bark_all_bite.common.registry.ABABMemoryModuleTypes;
import com.infamous.all_bark_all_bite.common.util.ai.AiUtil;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.Set;

public class DogSpecificSensor extends Sensor<Dog> {

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(
                MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                ABABMemoryModuleTypes.NEAREST_VISIBLE_DISLIKED.get(),
                ABABMemoryModuleTypes.NEAREST_VISIBLE_HUNTABLE.get(),
                MemoryModuleType.NEAREST_ATTACKABLE,
                MemoryModuleType.NEAREST_PLAYER_HOLDING_WANTED_ITEM,
                MemoryModuleType.HURT_BY_ENTITY,
                ABABMemoryModuleTypes.IS_FLEEING.get(),
                MemoryModuleType.AVOID_TARGET,
                ABABMemoryModuleTypes.NEAREST_TARGETABLE_PLAYER_NOT_SNEAKING.get());
    }

    @Override
    protected void doTick(ServerLevel level, Dog dog) {
        boolean tame = dog.isTame();
        Brain<?> brain = dog.getBrain();
        float attackRange = SharedWolfAi.DOG_ATTACK_RANGE;

        Optional<LivingEntity> nearestDisliked = Optional.empty();
        Optional<LivingEntity> nearestHuntable = Optional.empty();
        Optional<LivingEntity> nearestAttackable = Optional.empty();
        Optional<Player> nearestPlayerHoldingWantedItem = Optional.empty();
        Optional<Player> nearestPlayerNotSneaking = Optional.empty();

        NearestVisibleLivingEntities nvle = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES).orElse(NearestVisibleLivingEntities.empty());

        // Check for retaliation against non-owner attackers
        Optional<LivingEntity> hurtByEntity = brain.getMemory(MemoryModuleType.HURT_BY_ENTITY);
        if (hurtByEntity.isPresent() && hurtByEntity.get() instanceof Player player && !dog.isOwnedBy(player)) {
            if (player.closerThan(dog, attackRange) && AiUtil.isAttackable(dog, player, true)) {
                nearestAttackable = Optional.of(player);
                brain.setMemory(MemoryModuleType.ATTACK_TARGET, player);
                brain.setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, player.getUUID(), SharedWolfAi.ANGER_DURATION.sample(dog.getRandom()));
            } else {
                brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                brain.eraseMemory(MemoryModuleType.ANGRY_AT);
            }
        }

        for (LivingEntity livingEntity : nvle.findAll((le) -> true)) {
            if (livingEntity instanceof Player player) {
                // Check if player is the owner of a tamed dog in the pack
                boolean isPackOwner = GenericAi.getNearbyAdults(dog).stream()
                        .filter(w -> w.isTame() && w.isOwnedBy(player))
                        .findAny()
                        .isPresent();

                // Handle MEET behavior for crouching players with tempting items
                if (!tame && !isPackOwner && !player.isSpectator() && player.isDiscrete() && (player.getMainHandItem().is(ABABTags.WOLF_LOVED) || player.getMainHandItem().is(ABABTags.WOLF_FOOD))) {
                    nearestPlayerHoldingWantedItem = Optional.of(player);
                    brain.setMemory(MemoryModuleType.NEAREST_PLAYER_HOLDING_WANTED_ITEM, player);
                    brain.eraseMemory(MemoryModuleType.AVOID_TARGET);
                    brain.eraseMemory(ABABMemoryModuleTypes.IS_FLEEING.get());
                    brain.setMemoryWithExpiry(ABABMemoryModuleTypes.HOWL_LOCATION.get(), GlobalPos.of(dog.level().dimension(), player.blockPosition()), SharedWolfAi.HOWL_EXPIRE_TIME);
                    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                    brain.eraseMemory(MemoryModuleType.PATH);
                    continue;
                }
                // Handle fleeing for untamed dogs from players
                if (!tame && !isPackOwner && !brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                    boolean isCrouchingWithTemptingItem = player.isDiscrete() && (player.getMainHandItem().is(ABABTags.WOLF_LOVED) || player.getMainHandItem().is(ABABTags.WOLF_FOOD));
                    if (!isCrouchingWithTemptingItem && player.closerThan(dog, SharedWolfAi.DOG_MAX_FLEE_DISTANCE) && !player.closerThan(dog, attackRange)) {
                        SharedWolfAi.setDirectedAvoidTarget(dog, player, SharedWolfAi.AVOID_DURATION.sample(dog.getRandom()));
                        brain.setMemoryWithExpiry(ABABMemoryModuleTypes.IS_FLEEING.get(), true, SharedWolfAi.AVOID_DURATION.sample(dog.getRandom()));
                    } else if (brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                            && brain.getMemory(MemoryModuleType.AVOID_TARGET).map(target -> target == player).orElse(false)
                            && !player.closerThan(dog, SharedWolfAi.DOG_MAX_FLEE_DISTANCE)) {
                        brain.eraseMemory(MemoryModuleType.AVOID_TARGET);
                        brain.eraseMemory(ABABMemoryModuleTypes.IS_FLEEING.get());
                        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
                        brain.eraseMemory(MemoryModuleType.PATH);
                    }
                }
                // Handle NEAREST_TARGETABLE_PLAYER_NOT_SNEAKING for untamed dogs
                if (!tame && !isPackOwner && nearestPlayerNotSneaking.isEmpty() && !player.isSpectator() && !player.isDiscrete()
                        && !brain.hasMemoryValue(ABABMemoryModuleTypes.IS_FLEEING.get())
                        && !(player.getMainHandItem().is(ABABTags.WOLF_LOVED) || player.getMainHandItem().is(ABABTags.WOLF_FOOD))
                        && player.closerThan(dog, attackRange)) {
                    nearestPlayerNotSneaking = Optional.of(player);
                }
                // Handle NEAREST_PLAYER_HOLDING_WANTED_ITEM for tamed dogs
                if (tame && nearestPlayerHoldingWantedItem.isEmpty() && !player.isSpectator() && player.isHolding(is -> DogAi.isInteresting(dog, is))) {
                    nearestPlayerHoldingWantedItem = Optional.of(player);
                }
            } else {
                if (brain.hasMemoryValue(MemoryModuleType.ANGRY_AT)
                        && brain.getMemory(MemoryModuleType.ANGRY_AT).map(uuid -> uuid.equals(livingEntity.getUUID())).orElse(false)
                        && !brain.getMemory(MemoryModuleType.HURT_BY_ENTITY).map(entity -> entity == livingEntity).orElse(false)) {
                    if (!livingEntity.closerThan(dog, attackRange) || !isAttackable(dog, livingEntity)) {
                        brain.eraseMemory(MemoryModuleType.ANGRY_AT);
                        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
                    }
                }
                if (nearestDisliked.isEmpty() && !tame && DogAi.isDisliked(livingEntity)) {
                    nearestDisliked = Optional.of(livingEntity);
                } else if (nearestHuntable.isEmpty() && !tame && isHuntable(dog, livingEntity)) {
                    nearestHuntable = Optional.of(livingEntity);
                } else if (nearestAttackable.isEmpty() && isAttackable(dog, livingEntity)) {
                    nearestAttackable = Optional.of(livingEntity);
                }
            }
        }

        brain.setMemory(ABABMemoryModuleTypes.NEAREST_VISIBLE_DISLIKED.get(), nearestDisliked);
        brain.setMemory(ABABMemoryModuleTypes.NEAREST_VISIBLE_HUNTABLE.get(), nearestHuntable);
        brain.setMemory(MemoryModuleType.NEAREST_ATTACKABLE, nearestAttackable);
        brain.setMemory(MemoryModuleType.NEAREST_PLAYER_HOLDING_WANTED_ITEM, nearestPlayerHoldingWantedItem);
        brain.setMemory(ABABMemoryModuleTypes.NEAREST_TARGETABLE_PLAYER_NOT_SNEAKING.get(), nearestPlayerNotSneaking);
    }

    private static boolean isAttackable(Dog dog, LivingEntity livingEntity) {
        return livingEntity.getType().is(ABABTags.DOG_ALWAYS_HOSTILES) && AiUtil.isClose(dog, livingEntity, ABABConfig.dogTargetDetectionDistance.get())
                && AiUtil.isAttackable(dog, livingEntity, true) && SharedWolfAi.wantsToAttack(dog, livingEntity, dog.getOwner());
    }

    private static boolean isHuntable(Dog dog, LivingEntity livingEntity) {
        return livingEntity.getType().is(ABABTags.DOG_HUNT_TARGETS) && AiUtil.isClose(dog, livingEntity, ABABConfig.dogTargetDetectionDistance.get())
                && AiUtil.isAttackable(dog, livingEntity, true) && SharedWolfAi.wantsToAttack(dog, livingEntity, dog.getOwner());
    }
}