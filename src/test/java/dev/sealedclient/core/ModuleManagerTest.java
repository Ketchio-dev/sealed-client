package dev.sealedclient.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleManagerTest {
    @Test
    void registrationIsOrderedAndSearchable() {
        ModuleManager manager = new ModuleManager();
        Module first = new BasicModule("first", "First Module");
        Module second = new BasicModule("second", "Second Module");
        var allView = manager.all();
        var utilityView = manager.inCategory(Category.UTILITY);

        manager.register(first);
        manager.register(second);

        assertSame(allView, manager.all());
        assertSame(utilityView, manager.inCategory(Category.UTILITY));
        assertEquals(2, manager.all().size());
        assertEquals(2, utilityView.size());
        assertEquals(first, manager.find("FIRST").orElseThrow());
        assertEquals(second, manager.find("second module").orElseThrow());
        assertTrue(manager.inCategory(Category.HUD).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> utilityView.add(first));
    }

    @Test
    void tickDispatchOnlyCallsTickableModules() {
        ModuleManager manager = new ModuleManager();
        CountingModule tickable = new CountingModule("tickable");
        PassiveCountingModule passive = new PassiveCountingModule();
        manager.register(tickable);
        manager.register(passive);
        tickable.setEnabled(true, null);
        passive.setEnabled(true, null);

        manager.tick(null);

        assertEquals(1, tickable.ticks);
    }

    @Test
    void duplicateModuleIdsAreRejected() {
        ModuleManager manager = new ModuleManager();
        manager.register(new BasicModule("same", "One"));

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.register(new BasicModule("same", "Two"))
        );
    }

    private static final class BasicModule extends Module {
        private BasicModule(String id, String name) {
            super(id, name, "test", Category.UTILITY, false);
        }
    }

    private static final class CountingModule extends Module implements TickableModule {
        private int ticks;

        private CountingModule(String id) {
            super(id, id, "test", Category.UTILITY, false);
        }

        @Override
        public void onTick(net.minecraft.client.Minecraft minecraft) {
            ticks++;
        }
    }

    private static final class PassiveCountingModule extends Module {
        private PassiveCountingModule() {
            super("passive", "passive", "test", Category.UTILITY, false);
        }
    }
}
