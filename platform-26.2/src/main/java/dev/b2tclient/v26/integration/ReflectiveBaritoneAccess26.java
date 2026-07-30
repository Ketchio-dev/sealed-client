package dev.b2tclient.v26.integration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/**
 * Reflection-only adapter for the small, stable Baritone goal API. Loading
 * this class does not resolve or initialize a Baritone class.
 */
final class ReflectiveBaritoneAccess26 implements BaritoneAccess26 {
    static final ApiNames PRODUCTION_NAMES = new ApiNames(
            "baritone.api.BaritoneAPI",
            "baritone.api.IBaritoneProvider",
            "baritone.api.IBaritone",
            "baritone.api.process.ICustomGoalProcess",
            "baritone.api.behavior.IPathingBehavior",
            "baritone.api.pathing.goals.Goal",
            "baritone.api.pathing.goals.GoalBlock",
            "baritone.api.utils.IPlayerContext",
            "baritone.api.utils.BetterBlockPos"
    );

    private final Method getProvider;
    private final Method getPrimaryBaritone;
    private final Method getCustomGoalProcess;
    private final Method getPathingBehavior;
    private final Method getPlayerContext;
    private final Method customIsActive;
    private final Method customGetGoal;
    private final Method setGoalAndPath;
    private final Method pathingGetGoal;
    private final Method pathingIsPathing;
    private final Method cancelEverything;
    private final Method getInProgress;
    private final Method playerFeet;
    private final Constructor<?> goalBlockConstructor;
    private final Method goalIsInGoal;
    private final Field positionX;
    private final Field positionY;
    private final Field positionZ;

    private ReflectiveBaritoneAccess26(
            Method getProvider,
            Method getPrimaryBaritone,
            Method getCustomGoalProcess,
            Method getPathingBehavior,
            Method getPlayerContext,
            Method customIsActive,
            Method customGetGoal,
            Method setGoalAndPath,
            Method pathingGetGoal,
            Method pathingIsPathing,
            Method cancelEverything,
            Method getInProgress,
            Method playerFeet,
            Constructor<?> goalBlockConstructor,
            Method goalIsInGoal,
            Field positionX,
            Field positionY,
            Field positionZ
    ) {
        this.getProvider = getProvider;
        this.getPrimaryBaritone = getPrimaryBaritone;
        this.getCustomGoalProcess = getCustomGoalProcess;
        this.getPathingBehavior = getPathingBehavior;
        this.getPlayerContext = getPlayerContext;
        this.customIsActive = customIsActive;
        this.customGetGoal = customGetGoal;
        this.setGoalAndPath = setGoalAndPath;
        this.pathingGetGoal = pathingGetGoal;
        this.pathingIsPathing = pathingIsPathing;
        this.cancelEverything = cancelEverything;
        this.getInProgress = getInProgress;
        this.playerFeet = playerFeet;
        this.goalBlockConstructor = goalBlockConstructor;
        this.goalIsInGoal = goalIsInGoal;
        this.positionX = positionX;
        this.positionY = positionY;
        this.positionZ = positionZ;
    }

    static ReflectiveBaritoneAccess26 probe(ClassLoader loader) {
        return probe(loader, PRODUCTION_NAMES);
    }

    static ReflectiveBaritoneAccess26 probe(
            ClassLoader loader,
            ApiNames names
    ) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(names, "names");
        try {
            Class<?> api = load(loader, names.api());
            Class<?> provider = load(loader, names.provider());
            Class<?> baritone = load(loader, names.baritone());
            Class<?> customProcess = load(loader, names.customProcess());
            Class<?> pathingBehavior = load(loader, names.pathingBehavior());
            Class<?> goal = load(loader, names.goal());
            Class<?> goalBlock = load(loader, names.goalBlock());
            Class<?> playerContext = load(loader, names.playerContext());
            Class<?> position = load(loader, names.position());

            return new ReflectiveBaritoneAccess26(
                    api.getMethod("getProvider"),
                    provider.getMethod("getPrimaryBaritone"),
                    baritone.getMethod("getCustomGoalProcess"),
                    baritone.getMethod("getPathingBehavior"),
                    baritone.getMethod("getPlayerContext"),
                    customProcess.getMethod("isActive"),
                    customProcess.getMethod("getGoal"),
                    customProcess.getMethod("setGoalAndPath", goal),
                    pathingBehavior.getMethod("getGoal"),
                    pathingBehavior.getMethod("isPathing"),
                    pathingBehavior.getMethod("cancelEverything"),
                    pathingBehavior.getMethod("getInProgress"),
                    playerContext.getMethod("playerFeet"),
                    goalBlock.getConstructor(int.class, int.class, int.class),
                    goalBlock.getMethod(
                            "isInGoal",
                            int.class,
                            int.class,
                            int.class
                    ),
                    position.getField("x"),
                    position.getField("y"),
                    position.getField("z")
            );
        } catch (ReflectiveOperationException
                 | LinkageError
                 | SecurityException exception) {
            throw new AccessFailure(
                    "Required Baritone API surface is unavailable",
                    exception
            );
        }
    }

    @Override
    public Object createGoal(int x, int y, int z) {
        try {
            return goalBlockConstructor.newInstance(x, y, z);
        } catch (ReflectiveOperationException
                 | LinkageError
                 | RuntimeException exception) {
            throw failure("Could not create Baritone goal", exception);
        }
    }

    @Override
    public Observation observe() {
        Object primary = primary();
        Object custom = invoke(getCustomGoalProcess, primary);
        Object pathing = invoke(getPathingBehavior, primary);
        boolean customActive = booleanValue(invoke(customIsActive, custom));
        Object customGoal = invoke(customGetGoal, custom);
        Object pathGoal = invoke(pathingGetGoal, pathing);
        boolean isPathing = booleanValue(invoke(pathingIsPathing, pathing));
        Object inProgress = invoke(getInProgress, pathing);
        if (!(inProgress instanceof Optional<?> calculation)) {
            throw new AccessFailure(
                    "Baritone getInProgress returned an incompatible value"
            );
        }
        Position feet = playerFeet(primary);
        return new Observation(
                customActive,
                customGoal,
                pathGoal,
                isPathing,
                customActive || calculation.isPresent(),
                feet
        );
    }

    @Override
    public void setGoalAndPath(Object goal) {
        Objects.requireNonNull(goal, "goal");
        Object custom = invoke(getCustomGoalProcess, primary());
        invoke(setGoalAndPath, custom, goal);
    }

    @Override
    public boolean cancelEverything() {
        Object pathing = invoke(getPathingBehavior, primary());
        return booleanValue(invoke(cancelEverything, pathing));
    }

    @Override
    public boolean isInGoal(Object goal, Position position) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(position, "position");
        return booleanValue(invoke(
                goalIsInGoal,
                goal,
                position.x(),
                position.y(),
                position.z()
        ));
    }

    private Object primary() {
        Object provider = invoke(getProvider, null);
        if (provider == null) {
            throw new AccessFailure("Baritone provider is unavailable");
        }
        Object primary = invoke(getPrimaryBaritone, provider);
        if (primary == null) {
            throw new AccessFailure(
                    "Baritone primary instance is unavailable"
            );
        }
        return primary;
    }

    private Position playerFeet(Object primary) {
        Object context = invoke(getPlayerContext, primary);
        if (context == null) {
            return null;
        }
        Object position;
        try {
            position = playerFeet.invoke(context);
        } catch (IllegalAccessException exception) {
            throw failure("Could not read Baritone player position", exception);
        } catch (InvocationTargetException exception) {
            Throwable target = exception.getTargetException();
            if (target instanceof NullPointerException) {
                return null;
            }
            throw failure("Could not read Baritone player position", target);
        }
        if (position == null) {
            return null;
        }
        try {
            return new Position(
                    positionX.getInt(position),
                    positionY.getInt(position),
                    positionZ.getInt(position)
            );
        } catch (IllegalAccessException
                 | IllegalArgumentException exception) {
            throw failure("Could not decode Baritone player position", exception);
        }
    }

    private static Class<?> load(ClassLoader loader, String name)
            throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Object invoke(
            Method method,
            Object receiver,
            Object... arguments
    ) {
        try {
            return method.invoke(receiver, arguments);
        } catch (IllegalAccessException | IllegalArgumentException exception) {
            throw failure("Baritone API invocation failed", exception);
        } catch (InvocationTargetException exception) {
            throw failure(
                    "Baritone API invocation failed",
                    exception.getTargetException()
            );
        } catch (LinkageError | RuntimeException exception) {
            throw failure("Baritone API invocation failed", exception);
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean result) {
            return result;
        }
        throw new AccessFailure("Baritone API returned a non-boolean value");
    }

    private static AccessFailure failure(String message, Throwable cause) {
        if (cause instanceof AccessFailure accessFailure) {
            return accessFailure;
        }
        return new AccessFailure(message, cause);
    }

    record ApiNames(
            String api,
            String provider,
            String baritone,
            String customProcess,
            String pathingBehavior,
            String goal,
            String goalBlock,
            String playerContext,
            String position
    ) {
        ApiNames {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(baritone, "baritone");
            Objects.requireNonNull(customProcess, "customProcess");
            Objects.requireNonNull(pathingBehavior, "pathingBehavior");
            Objects.requireNonNull(goal, "goal");
            Objects.requireNonNull(goalBlock, "goalBlock");
            Objects.requireNonNull(playerContext, "playerContext");
            Objects.requireNonNull(position, "position");
        }
    }

    static final class AccessFailure extends RuntimeException {
        AccessFailure(String message) {
            super(message);
        }

        AccessFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
