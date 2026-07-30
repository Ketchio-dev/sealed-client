package dev.b2tclient.common.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Turns held key state into single module toggles.
 *
 * <p>The dispatcher only reacts to a rising edge, so holding a bound key never
 * flickers a module on and off across consecutive ticks. While input is blocked
 * (a screen is open, so the key belongs to a text field or the keybind editor)
 * the dispatcher still refreshes its held-key memory. That way a key pressed
 * inside a screen does not fire the moment the screen closes.</p>
 *
 * <p>The class is deliberately free of Minecraft types: the caller supplies a
 * predicate answering "is this key code currently down", which makes the whole
 * edge-detection contract testable without a running client.</p>
 */
public final class ModuleKeybindDispatcher {
    private final Set<Integer> held = new HashSet<>();

    /**
     * Returns the modules whose keybind transitioned from up to down.
     *
     * @param modules      the modules to consider, in registration order
     * @param keyDown      answers whether a GLFW key code is currently pressed
     * @param inputBlocked true when a screen owns the keyboard this tick
     */
    public List<RegisteredModule> pressedThisTick(
            List<RegisteredModule> modules,
            IntPredicate keyDown,
            boolean inputBlocked
    ) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(keyDown, "keyDown");

        Set<Integer> downNow = new HashSet<>();
        for (RegisteredModule module : modules) {
            int keyCode = module.keyCode();
            if (keyCode == RegisteredModule.UNBOUND_KEY_CODE) {
                continue;
            }
            if (keyDown.test(keyCode)) {
                downNow.add(keyCode);
            }
        }

        if (inputBlocked) {
            // Adopt the current state without firing, so a key held while a
            // screen is open cannot toggle anything once the screen closes.
            held.clear();
            held.addAll(downNow);
            return List.of();
        }

        List<RegisteredModule> triggered = new ArrayList<>();
        for (RegisteredModule module : modules) {
            int keyCode = module.keyCode();
            if (keyCode == RegisteredModule.UNBOUND_KEY_CODE) {
                continue;
            }
            if (downNow.contains(keyCode) && !held.contains(keyCode)) {
                triggered.add(module);
            }
        }
        held.clear();
        held.addAll(downNow);
        return Collections.unmodifiableList(triggered);
    }

    /**
     * Forgets every held key. Called on disconnect and client shutdown so a
     * stale press cannot survive into the next session.
     */
    public void reset() {
        held.clear();
    }

    /** Exposed for lifecycle assertions. */
    public int heldKeyCount() {
        return held.size();
    }
}
