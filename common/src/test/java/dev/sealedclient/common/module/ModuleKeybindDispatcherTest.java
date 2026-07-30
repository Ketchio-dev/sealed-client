package dev.sealedclient.common.module;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleKeybindDispatcherTest {
    private final Set<Integer> down = new HashSet<>();
    private final ModuleKeybindDispatcher dispatcher = new ModuleKeybindDispatcher();

    private static RegisteredModule module(String id, int keyCode) {
        RegisteredModule module = new RegisteredModule(
                new ModuleDescriptor(
                        id,
                        id,
                        "test module",
                        ModuleCategory.UTILITY,
                        ModuleRisk.PASSIVE,
                        false
                ),
                List.of()
        );
        module.setKeyCode(keyCode);
        return module;
    }

    private List<RegisteredModule> dispatch(List<RegisteredModule> modules, boolean blocked) {
        return dispatcher.pressedThisTick(modules, down::contains, blocked);
    }

    @Test
    void firesOnlyOnTheRisingEdgeOfAHeldKey() {
        RegisteredModule flight = module("flight", 70);
        List<RegisteredModule> modules = List.of(flight);

        assertTrue(dispatch(modules, false).isEmpty());

        down.add(70);
        assertEquals(List.of(flight), dispatch(modules, false));
        assertTrue(dispatch(modules, false).isEmpty(), "holding must not retrigger");

        down.remove(70);
        assertTrue(dispatch(modules, false).isEmpty());
        down.add(70);
        assertEquals(List.of(flight), dispatch(modules, false), "re-press fires again");
    }

    @Test
    void unboundModulesNeverFire() {
        RegisteredModule unbound = module("unbound", RegisteredModule.UNBOUND_KEY_CODE);
        down.add(RegisteredModule.UNBOUND_KEY_CODE);
        assertTrue(dispatch(List.of(unbound), false).isEmpty());
    }

    @Test
    void aKeyHeldWhileAScreenIsOpenDoesNotFireWhenTheScreenCloses() {
        RegisteredModule flight = module("flight", 70);
        List<RegisteredModule> modules = List.of(flight);

        down.add(70);
        assertTrue(dispatch(modules, true).isEmpty(), "blocked input must not toggle");
        assertTrue(dispatch(modules, false).isEmpty(), "still held, so no rising edge");

        down.remove(70);
        assertTrue(dispatch(modules, false).isEmpty());
        down.add(70);
        assertEquals(List.of(flight), dispatch(modules, false),
                "a genuine release-then-press still toggles");
    }

    @Test
    void oneKeyBoundToSeveralModulesTogglesAllOfThem() {
        RegisteredModule first = module("first", 70);
        RegisteredModule second = module("second", 70);
        down.add(70);
        assertEquals(List.of(first, second), dispatch(List.of(first, second), false));
    }

    @Test
    void resetClearsHeldStateSoTheNextSessionStartsClean() {
        RegisteredModule flight = module("flight", 70);
        List<RegisteredModule> modules = List.of(flight);
        down.add(70);
        assertEquals(List.of(flight), dispatch(modules, false));
        assertEquals(1, dispatcher.heldKeyCount());

        dispatcher.reset();
        assertEquals(0, dispatcher.heldKeyCount());
        assertEquals(List.of(flight), dispatch(modules, false),
                "after reset the still-held key counts as a fresh press");
    }

    @Test
    void outOfRangeKeyCodesCollapseToUnbound() {
        assertEquals(RegisteredModule.UNBOUND_KEY_CODE,
                RegisteredModule.normalizeKeyCode(-5));
        assertEquals(RegisteredModule.UNBOUND_KEY_CODE,
                RegisteredModule.normalizeKeyCode(Integer.MAX_VALUE));
        assertEquals(RegisteredModule.UNBOUND_KEY_CODE,
                RegisteredModule.normalizeKeyCode(RegisteredModule.MAX_KEY_CODE + 1));
        assertEquals(RegisteredModule.MAX_KEY_CODE,
                RegisteredModule.normalizeKeyCode(RegisteredModule.MAX_KEY_CODE));
    }

    @Test
    void persistedGarbageKeyCodeCannotResolveToARealKey() {
        RegisteredModule flight = module("flight", 70);
        flight.apply(new ModuleSnapshot(false, false, 99_999, java.util.Map.of()));
        assertEquals(RegisteredModule.UNBOUND_KEY_CODE, flight.keyCode());
    }
}
