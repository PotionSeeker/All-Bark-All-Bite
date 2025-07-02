package com.infamous.all_bark_all_bite.common.entity.wolf;

import com.google.common.collect.ImmutableList;
import com.infamous.all_bark_all_bite.common.ABABTags;
import com.infamous.all_bark_all_bite.common.behavior.misc.*;
import com.infamous.all_bark_all_bite.common.behavior.pack.HowlForPack;
import com.infamous.all_bark_all_bite.common.behavior.pack.JoinOrCreatePackAndFollow;
import com.infamous.all_bark_all_bite.common.behavior.pack.ValidateFollowers;
import com.infamous.all_bark_all_bite.common.behavior.pack.ValidateLeader;
import com.infamous.all_bark_all_bite.common.behavior.pet.Beg;
import com.infamous.all_bark_all_bite.common.behavior.sleep.MoveToNonSkySeeingSpot;
import com.infamous.all_bark_all_bite.common.behavior.sleep.WakeUpTrigger;
import com.infamous.all_bark_all_bite.common.entity.SharedWolfAi;
import com.infamous.all_bark_all_bite.common.entity.SharedWolfBrain;
import com.infamous.all_bark_all_bite.common.logic.BrainMaker;
import com.infamous.all_bark_all_bite.common.registry.ABABActivities;
import com.infamous.all_bark_all_bite.common.registry.ABABMemoryModuleTypes;
import com.infamous.all_bark_all_bite.common.util.ai.*;
import com.infamous.all_bark_all_bite.config.ABABConfig;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;

import java.util.function.Predicate;

public class WolfBrain {

    public static Brain<Wolf> makeBrain(Brain<Wolf> brain) {
        BrainMaker<Wolf> brainMaker = new BrainMaker<>(brain);
        brainMaker.initActivityWithConditions(Activity.PANIC,
                SharedWolfBrain.getPanicPackage(), SharedWolfBrain.getPanicConditions());
        brainMaker.initActivityWithConditions(ABABActivities.SIT.get(),
                SharedWolfBrain.getSitPackage(createIdleLookBehaviors(), beg()), SharedWolfBrain.getSitConditions());
        brainMaker.initActivityWithMemoryGate(Activity.FIGHT,
                SharedWolfBrain.getFightPackage(WolfBrain::isHuntTarget), MemoryModuleType.ATTACK_TARGET);
        brainMaker.initActivityWithMemoryGate(Activity.AVOID,
                SharedWolfBrain.getAvoidPackage(createIdleMovementBehaviors(), createIdleLookBehaviors()), MemoryModuleType.AVOID_TARGET);
        brainMaker.initActivityWithConditions(ABABActivities.FOLLOW.get(),
                SharedWolfBrain.getFollowPackage(createIdleMovementBehaviors(), beg(), createIdleLookBehaviors()), SharedWolfBrain.getFollowConditions());
        brainMaker.initActivityWithMemoryGate(Activity.MEET,
                SharedWolfBrain.getMeetPackage(), ABABMemoryModuleTypes.HOWL_LOCATION.get());
        brainMaker.initActivityWithMemoryGate(ABABActivities.HUNT.get(),
                SharedWolfBrain.getHuntPackage(), ABABMemoryModuleTypes.HUNT_TARGET.get());
        brainMaker.initActivityWithConditions(Activity.REST,
                SharedWolfBrain.getRestPackage(createIdleLookBehaviors(), true), SharedWolfBrain.getRestConditions(ABABMemoryModuleTypes.IS_LEVEL_DAY.get()));
        brainMaker.initActivity(Activity.IDLE,
                getIdlePackage());

        brainMaker.initCoreActivity(Activity.CORE,
                getCorePackage());
        brainMaker.initCoreActivity(ABABActivities.COUNT_DOWN.get(),
                SharedWolfBrain.getCountDownPackage());
        brainMaker.initCoreActivity(ABABActivities.TARGET.get(),
                SharedWolfBrain.getTargetPackage(WolfBrain::wasHurtBy, SharedWolfAi::canStartAttacking));
        brainMaker.initCoreActivity(ABABActivities.UPDATE.get(),
                SharedWolfBrain.getUpdatePackage(brainMaker.getActivities(), WolfBrain::onActivityChanged));

        return brainMaker.makeBrain(Activity.IDLE);
    }

    private static RunBehaviorIf<Wolf> beg() {
        return new RunBehaviorIf<>(TamableAnimal::isTame, new Beg<>(Wolf::isFood, Wolf::setIsInterested, SharedWolfAi.MAX_LOOK_DIST));
    }

    private static ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super Wolf>>> getCorePackage(){
        return BrainUtil.createPriorityPairs(0, ImmutableList.of(
                new HurtByTrigger<>(SharedWolfBrain::onHurtBy),
                new WakeUpTrigger<>(SharedWolfAi::wantsToWakeUp),
                new PickUpItemTrigger<>(WolfHooks::onWolfPickUpItem),
                new Swim(SharedWolfAi.JUMP_CHANCE_IN_WATER),
                SharedWolfBrain.createAnimalPanic(),
                SharedWolfBrain.createLookAtTargetSink(),
                SharedWolfBrain.createMoveToTargetSink(),
                SharedWolfBrain.copyToAvoidTarget(ABABMemoryModuleTypes.NEAREST_TARGETABLE_PLAYER_NOT_SNEAKING.get()),
                SharedWolfBrain.copyToAvoidTarget(ABABMemoryModuleTypes.NEAREST_VISIBLE_DISLIKED.get()),
                new UpdateUnitMemory<>(TamableAnimal::isOrderedToSit, ABABMemoryModuleTypes.IS_ORDERED_TO_SIT.get()),
                new UpdateUnitMemory<>(SharedWolfAi::hasShelter, ABABMemoryModuleTypes.IS_SHELTERED.get()),
                new UpdateUnitMemory<>(SharedWolfAi::isInDayTime, ABABMemoryModuleTypes.IS_LEVEL_DAY.get()),
                new UpdateUnitMemory<>(WolfBrain::isAlert, ABABMemoryModuleTypes.IS_ALERT.get()),
                new UpdateUnitMemory<>(LivingEntity::isSleeping, ABABMemoryModuleTypes.IS_SLEEPING.get()),
                new ValidateLeader(),
                new ValidateFollowers()
        ));
    }

    private static boolean isAlert(Wolf wolf) {
        return SharedWolfAi.alertable(wolf, ABABTags.WOLF_HUNT_TARGETS, ABABTags.WOLF_ALWAYS_HOSTILES, ABABTags.WOLF_DISLIKED);
    }

    private static void wasHurtBy(Wolf wolf, LivingEntity attacker) {
        AiUtil.eraseMemories(wolf,
                MemoryModuleType.BREED_TARGET,
                ABABMemoryModuleTypes.HUNT_TARGET.get(),
                ABABMemoryModuleTypes.HOWL_LOCATION.get());

        if(!wolf.isTame() && TrustAi.isLikedBy(wolf, attacker)){
            TrustAi.decrementTrust(wolf, ABABConfig.wolfTrustDecrement.get());
        }

        SharedWolfAi.reactToAttack(wolf, attacker);
        // If attacked by a player, set as attack target for the wolf and its pack
        if (!wolf.isTame() && attacker instanceof Player) {
            AngerAi.setAngerTarget(wolf, attacker, SharedWolfAi.ANGER_DURATION.sample(wolf.getRandom()));
            GenericAi.setAttackTarget(wolf, attacker);
            // Broadcast to pack members
            GenericAi.getNearbyAdults(wolf).forEach(packMember -> {
                if (!packMember.isTame()) {
                    AngerAi.setAngerTarget(packMember, attacker, SharedWolfAi.ANGER_DURATION.sample(packMember.getRandom()));
                    GenericAi.setAttackTarget(packMember, attacker);
                }
            });
        }
    }

    private static void onActivityChanged(Wolf wolf, Pair<Activity, Activity> activityChange){
        // Clear conflicting memories when entering AVOID or TEMPT activity
        if (activityChange.getSecond() == Activity.AVOID || activityChange.getSecond() == Activity.MEET) {
            wolf.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            wolf.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
            wolf.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            wolf.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
            wolf.getBrain().eraseMemory(MemoryModuleType.PATH);
        }
        WolfAi.getSoundForCurrentActivity(wolf).ifPresent(se -> AiUtil.playSoundEvent(wolf, se));
    }

    private static boolean isHuntTarget(Wolf wolf, LivingEntity target) {
        return target.getType().is(ABABTags.WOLF_HUNT_TARGETS) || (target instanceof Player player && WolfAi.isTargetablePlayerNotSneaking(wolf, player));
    }

    private static ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super Wolf>>> getIdlePackage(){
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new Sprint<>(SharedWolfAi::canMove, SharedWolfAi.TOO_FAR_FROM_WALK_TARGET),
                        new Eat(SharedWolfAi::setAteRecently),
                        new RunBehaviorIf<>(WolfAi::isTrusting, new FollowTemptation(SharedWolfAi::getSpeedModifierTempted)),
                        SharedWolfBrain.createBreedBehavior(EntityType.WOLF),
                        BehaviorBuilder.triggerIf(livingEntity -> SharedWolfAi.wantsToFindShelter(livingEntity, true), MoveToNonSkySeeingSpot.create(SharedWolfAi.SPEED_MODIFIER_WALKING)),
                        new HowlForPack<>(Predicate.not(TamableAnimal::isTame), SharedWolfAi.TIME_BETWEEN_HOWLS, SharedWolfAi.ADULT_FOLLOW_RANGE.getMaxValue()),
                        new JoinOrCreatePackAndFollow<>(SharedWolfAi.ADULT_FOLLOW_RANGE, SharedWolfAi.SPEED_MODIFIER_FOLLOWING_ADULT),
                        BabyFollowAdult.create(SharedWolfAi.ADULT_FOLLOW_RANGE, SharedWolfAi.SPEED_MODIFIER_FOLLOWING_ADULT),
                        SharedWolfBrain.babySometimesHuntBaby(),
                        new PlayTagWithOtherBabies(SharedWolfAi.SPEED_MODIFIER_RETREATING, SharedWolfAi.SPEED_MODIFIER_CHASING),
                        SharedWolfAi.createGoToWantedItem(false),
                        new PerchAndSearch<>(SharedWolfBrain::canPerch, TamableAnimal::setInSittingPose),
                        createIdleMovementBehaviors(),
                        beg(),
                        createIdleLookBehaviors()
                ));
    }

    private static RunOne<Wolf> createIdleLookBehaviors() {
        return new RunOne<>(
                ImmutableList.of(
                        Pair.of(SetEntityLookTarget.create(EntityType.WOLF, SharedWolfAi.MAX_LOOK_DIST), 1),
                        Pair.of(SetEntityLookTarget.create(SharedWolfAi.MAX_LOOK_DIST), 1),
                        Pair.of(new DoNothing(30, 60), 1)));
    }

    private static RunOne<Wolf> createIdleMovementBehaviors() {
        return new RunOne<>(
                ImmutableList.of(
                        Pair.of(RandomStroll.stroll(SharedWolfAi.SPEED_MODIFIER_WALKING), 3),
                        Pair.of(InteractWith.of(EntityType.WOLF, SharedWolfAi.INTERACTION_RANGE, MemoryModuleType.INTERACTION_TARGET, SharedWolfAi.SPEED_MODIFIER_WALKING, SharedWolfAi.CLOSE_ENOUGH_TO_INTERACT), 2),
                        Pair.of(SetWalkTargetFromLookTarget.create(SharedWolfAi.SPEED_MODIFIER_WALKING, SharedWolfAi.CLOSE_ENOUGH_TO_LOOK_TARGET), 2),
                        Pair.of(new DoNothing(30, 60), 1)));
    }
}