package com.infamous.all_bark_all_bite.common.entity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.infamous.all_bark_all_bite.common.behavior.hunter.RememberIfHuntTargetWasKilled;
import com.infamous.all_bark_all_bite.common.behavior.hunter.StalkAndPounce;
import com.infamous.all_bark_all_bite.common.behavior.hunter.StartHuntingPrey;
import com.infamous.all_bark_all_bite.common.behavior.long_jump.LeapAtTarget;
import com.infamous.all_bark_all_bite.common.behavior.misc.*;
import com.infamous.all_bark_all_bite.common.behavior.pet.DefendLikedPlayer;
import com.infamous.all_bark_all_bite.common.behavior.pet.OwnerHurtByTarget;
import com.infamous.all_bark_all_bite.common.behavior.pet.OwnerHurtTarget;
import com.infamous.all_bark_all_bite.common.behavior.pet.SitWhenOrderedTo;
import com.infamous.all_bark_all_bite.common.behavior.sleep.SleepOnGround;
import com.infamous.all_bark_all_bite.common.entity.dog.Dog;
import com.infamous.all_bark_all_bite.common.registry.ABABMemoryModuleTypes;
import com.infamous.all_bark_all_bite.common.util.ai.AngerAi;
import com.infamous.all_bark_all_bite.common.util.ai.BrainUtil;
import com.infamous.all_bark_all_bite.common.util.ai.GenericAi;
import com.infamous.all_bark_all_bite.common.util.ai.HunterAi;
import com.mojang.datafixers.util.Pair;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.Unit;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.schedule.Activity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public class SharedWolfBrain {

    private static final UniformInt PLAY_START_INTERVAL = TimeUtil.rangeOfSeconds(10, 40);

    public static AnimalMakeLove createBreedBehavior(EntityType<? extends Wolf> type) {
        return new AnimalMakeLove(type, SharedWolfAi.SPEED_MODIFIER_BREEDING);
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getFightPackage(BiPredicate<E, LivingEntity> huntTargetPredicate) {
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new Sprint<>(SharedWolfBrain::canSprintWhileAttacking),
                        new LeapAtTarget(SharedWolfAi.LEAP_YD, SharedWolfAi.TOO_CLOSE_TO_LEAP, SharedWolfAi.TOO_FAR_TO_LEAP, SharedWolfAi.LEAP_COOLDOWN),
                        SetWalkTargetFromAttackTargetIfTargetOutOfReach.create(SharedWolfAi.SPEED_MODIFIER_CHASING),
                        MeleeAttack.create(SharedWolfAi.ATTACK_COOLDOWN_TICKS),
                        new RememberIfHuntTargetWasKilled<>(huntTargetPredicate, SharedWolfAi.TIME_BETWEEN_HUNTS),
                        EraseMemoryIf.create(BehaviorUtils::isBreeding, MemoryModuleType.ATTACK_TARGET)));
    }

    private static boolean canSprintWhileAttacking(Wolf wolf){
        return SharedWolfAi.canMove(wolf) && GenericAi.getAttackTarget(wolf).map(at -> !wolf.isWithinMeleeAttackRange(at)).orElse(false);
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getTargetPackage(BiConsumer<E, LivingEntity> onHurtByEntity, Predicate<E> canStartHunting){
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new OwnerHurtByTarget<>(SharedWolfAi::canDefendOwner, SharedWolfAi::wantsToAttack),
                        new RunBehaviorIf<>(Predicate.not(TamableAnimal::isTame), new DefendLikedPlayer<>()),
                        new OwnerHurtTarget<>(SharedWolfAi::canDefendOwner, SharedWolfAi::wantsToAttack),
                        new HurtByEntityTrigger<>(onHurtByEntity),
                        StartAttacking.create(SharedWolfAi::canStartAttacking, SharedWolfAi::findNearestValidAttackTarget),
                        new StartHuntingPrey<>(canStartHunting, SharedWolfAi.TIME_BETWEEN_HUNTS),
                        StopAttackingIfTargetInvalid.create(),
                        StopBeingAngryIfTargetDead.create()
                ));
    }

    public static void onHurtBy(Wolf tamableAnimal){
        SharedWolfAi.stopHoldingItemInMouth(tamableAnimal);
        SharedWolfAi.clearStates(tamableAnimal, true);
        tamableAnimal.setOrderedToSit(false);
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getUpdatePackage(List<Activity> activities, BiConsumer<E, Pair<Activity, Activity>> onActivityChanged){
        return BrainUtil.createPriorityPairs(99,
                ImmutableList.of(
                        new UpdateActivity<>(activities, onActivityChanged),
                        new UpdateTarget(),
                        new UpdateNeutralMob<>()
                ));
    }

    public static ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super Wolf>>> getPanicPackage(){
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new Sprint<>(SharedWolfAi::canMove)
                ));
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getSitPackage(RunOne<E> idleLookBehaviors, BehaviorControl<E> beg){
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new SitWhenOrderedTo(),
                        beg,
                        idleLookBehaviors
                ));
    }

    public static ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super Wolf>>> getHuntPackage() {
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new RunBehaviorIf<>(Predicate.not(LivingEntity::isBaby), new StalkAndPounce<>(SharedWolfAi.SPEED_MODIFIER_WALKING,
                                SharedWolfAi.POUNCE_DISTANCE, SharedWolfAi.POUNCE_HEIGHT,
                                SharedWolfBrain::adultStopHuntingWhen, SharedWolfBrain::onHuntTargetErased)),
                        new RunBehaviorIf<>(LivingEntity::isBaby, new StalkAndPounce<>(SharedWolfAi.SPEED_MODIFIER_WALKING,
                                SharedWolfAi.BABY_POUNCE_DISTANCE, SharedWolfAi.BABY_POUNCE_HEIGHT,
                                SharedWolfBrain::babyStopHuntingWhen))
                ));
    }

    private static boolean babyStopHuntingWhen(Wolf wolf, LivingEntity target){
        return !target.isBaby();
    }

    private static boolean adultStopHuntingWhen(Wolf wolf, LivingEntity target){
        return !GenericAi.getNearbyAdults(wolf).isEmpty();
    }

    private static void onHuntTargetErased(Wolf wolf, LivingEntity target){
        if(Sensor.isEntityAttackableIgnoringLineOfSight(wolf, target)){
            AngerAi.setAngerTargetIfCloserThanCurrent(wolf, target, SharedWolfAi.ANGER_DURATION.sample(wolf.getRandom()));
            GenericAi.setAttackTargetIfCloserThanCurrent(wolf, target);
        }
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getAvoidPackage(RunOne<E> idleMovementBehaviors, RunOne<E> idleLookBehaviors) {
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new Sprint<>(SharedWolfAi::canMove),
                        BehaviorBuilder.triggerIf(SharedWolfAi::canMove, SetWalkTargetAwayFrom.entity(MemoryModuleType.AVOID_TARGET, SharedWolfAi.SPEED_MODIFIER_RETREATING, SharedWolfAi.DESIRED_DISTANCE_FROM_ENTITY_WHEN_AVOIDING, true)),
                        new RunBehaviorIf<>(wolf -> !wolf.getBrain().hasMemoryValue(MemoryModuleType.PATH), RandomStroll.stroll(SharedWolfAi.SPEED_MODIFIER_RETREATING, 2, 1)),
                        idleMovementBehaviors,
                        idleLookBehaviors,
                        EraseMemoryIf.create(SharedWolfBrain::wantsToStopFleeing, MemoryModuleType.AVOID_TARGET),
                        EraseMemoryIf.create(SharedWolfBrain::wantsToStopFleeing, ABABMemoryModuleTypes.IS_FLEEING.get())));
    }

    private static boolean wantsToStopFleeing(Wolf wolf){
        if (wolf.isTame() || !wolf.getBrain().hasMemoryValue(MemoryModuleType.AVOID_TARGET)) {
            return true;
        }
        Optional<LivingEntity> avoidTarget = wolf.getBrain().getMemory(MemoryModuleType.AVOID_TARGET);
        float maxFleeDistance = wolf instanceof Dog ? SharedWolfAi.DOG_MAX_FLEE_DISTANCE : SharedWolfAi.MAX_FLEE_DISTANCE;
        return avoidTarget.map(target -> !target.closerThan(wolf, maxFleeDistance)).orElse(true);
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getMeetPackage() {
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new Sprint<>(SharedWolfAi::canMove),
                        StayCloseToTarget.create(SharedWolfAi::getHowlPosition, le -> true, SharedWolfAi.ADULT_FOLLOW_RANGE.getMinValue() - 1, SharedWolfAi.ADULT_FOLLOW_RANGE.getMaxValue(), SharedWolfAi.SPEED_MODIFIER_WALKING),
                        EraseMemoryIf.create(SharedWolfBrain::wantsToStopFollowingHowl, ABABMemoryModuleTypes.HOWL_LOCATION.get()))
        );
    }

    private static boolean wantsToStopFollowingHowl(Wolf wolf){
        Optional<PositionTracker> howlPosition = SharedWolfAi.getHowlPosition(wolf);
        if (howlPosition.isEmpty()) {
            return true;
        } else {
            PositionTracker tracker = howlPosition.get();
            return wolf.position().closerThan(tracker.currentPosition(), SharedWolfAi.ADULT_FOLLOW_RANGE.getMaxValue());
        }
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getRestPackage(RunOne<E> idleLookBehaviors, boolean nocturnal){
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new SleepOnGround<>(wolf -> SharedWolfAi.canSleep(wolf, nocturnal), SharedWolfAi::handleSleeping),
                        new RunBehaviorIf<>(Predicate.not(LivingEntity::isSleeping), idleLookBehaviors)
                ));
    }

    public static Set<Pair<MemoryModuleType<?>, MemoryStatus>> getPanicConditions() {
        return ImmutableSet.of(Pair.of(MemoryModuleType.IS_PANICKING, MemoryStatus.VALUE_PRESENT));
    }

    public static Set<Pair<MemoryModuleType<?>, MemoryStatus>> getSitConditions() {
        return ImmutableSet.of(Pair.of(ABABMemoryModuleTypes.IS_ORDERED_TO_SIT.get(), MemoryStatus.VALUE_PRESENT));
    }

    public static Set<Pair<MemoryModuleType<?>, MemoryStatus>> getRestConditions(MemoryModuleType<Unit> timeMemory) {
        return ImmutableSet.of(
                Pair.of(ABABMemoryModuleTypes.IS_SHELTERED.get(), MemoryStatus.VALUE_PRESENT),
                Pair.of(timeMemory, MemoryStatus.VALUE_PRESENT),
                Pair.of(ABABMemoryModuleTypes.IS_ALERT.get(), MemoryStatus.VALUE_ABSENT),
                Pair.of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT),
                Pair.of(MemoryModuleType.TEMPTING_PLAYER, MemoryStatus.VALUE_ABSENT));
    }

    public static void fetchItem(LivingEntity livingEntity) {
        Brain<?> brain = livingEntity.getBrain();
        brain.eraseMemory(ABABMemoryModuleTypes.TIME_TRYING_TO_REACH_FETCH_ITEM.get());
        brain.setMemory(ABABMemoryModuleTypes.FETCHING_ITEM.get(), true);
    }

    public static ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super Wolf>>> getCountDownPackage(){
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new CountDownCooldownTicks(MemoryModuleType.ITEM_PICKUP_COOLDOWN_TICKS),
                        new CountDownCooldownTicks(MemoryModuleType.LONG_JUMP_COOLDOWN_TICKS),
                        new CountDownCooldownTicks(MemoryModuleType.TEMPTATION_COOLDOWN_TICKS)
                ));
    }

    public static boolean canPerch(LivingEntity mob) {
        return GenericAi.getAttackTarget(mob).isEmpty()
                && !SharedWolfAi.isAlert(mob)
                && HunterAi.getHuntTarget(mob).isEmpty()
                && !mob.hasPose(Pose.CROUCHING);
    }

    public static boolean isActivelyFollowing(LivingEntity wolf){
        return wolf.getBrain().hasMemoryValue(ABABMemoryModuleTypes.IS_FOLLOWING.get());
    }

    public static RunBehaviorIf<Wolf> createMoveToTargetSink() {
        return new RunBehaviorIf<>(SharedWolfAi::canMove, new MoveToTargetSink());
    }

    public static RunBehaviorIf<Wolf> createAnimalPanic() {
        return new RunBehaviorIf<>(SharedWolfAi::shouldPanic, new AnimalPanic(SharedWolfAi.SPEED_MODIFIER_PANICKING));
    }

    public static BehaviorControl<Wolf> copyToAvoidTarget(MemoryModuleType<? extends LivingEntity> copyMemory) {
        return CopyMemoryWithExpiry.create(
                wolf -> SharedWolfAi.isNearDisliked(wolf, copyMemory),
                copyMemory,
                MemoryModuleType.AVOID_TARGET,
                SharedWolfAi.AVOID_DURATION);
    }

    public static RunBehaviorIf<Mob> createLookAtTargetSink() {
        return new RunBehaviorIf<>(Predicate.not(LivingEntity::isSleeping), new LookAtTargetSink(45, 90));
    }

    public static <E extends Wolf> ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super E>>> getFollowPackage(RunOne<E> idleMovementBehaviors, BehaviorControl<E> beg, RunOne<E> idleLookBehaviors) {
        return BrainUtil.createPriorityPairs(0,
                ImmutableList.of(
                        new Sprint<>(SharedWolfAi::canMove, SharedWolfAi.TOO_FAR_FROM_WALK_TARGET),
                        SharedWolfAi.createFollowOwner(SharedWolfAi.SPEED_MODIFIER_WALKING),
                        idleMovementBehaviors,
                        beg,
                        idleLookBehaviors
                ));
    }

    public static Set<Pair<MemoryModuleType<?>, MemoryStatus>> getFollowConditions() {
        return ImmutableSet.of(
                Pair.of(ABABMemoryModuleTypes.IS_ORDERED_TO_FOLLOW.get(), MemoryStatus.VALUE_PRESENT));
    }

    public static BehaviorControl<Wolf> babySometimesHuntBaby() {
        SetEntityLookTargetSometimes.Ticker ticker = new SetEntityLookTargetSometimes.Ticker(PLAY_START_INTERVAL);
        return CopyMemoryNoExpiry.create((wolf) -> wolf.isBaby() && ticker.tickDownAndCheck(wolf.level().random), ABABMemoryModuleTypes.NEAREST_VISIBLE_BABY.get(), ABABMemoryModuleTypes.HUNT_TARGET.get());
    }
}