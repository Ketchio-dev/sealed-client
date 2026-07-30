package dev.sealedclient.module.movement;

import dev.sealedclient.core.Category;
import dev.sealedclient.core.Module;
import dev.sealedclient.core.ModuleRisk;

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
