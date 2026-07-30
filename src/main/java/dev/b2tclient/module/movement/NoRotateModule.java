package dev.b2tclient.module.movement;

import dev.b2tclient.core.Category;
import dev.b2tclient.core.Module;
import dev.b2tclient.core.ModuleRisk;

public final class NoRotateModule extends Module {
    public static final String ID = "no_rotate";

    public NoRotateModule() {
        super(
                ID,
                "No Rotate",
                "Accepts server position corrections without forcing the local camera rotation.",
                Category.MOVEMENT,
                false,
                ModuleRisk.PACKET
        );
    }
}
