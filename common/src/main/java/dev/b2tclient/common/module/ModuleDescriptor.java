package dev.b2tclient.common.module;

import java.util.Locale;
import java.util.Objects;

public record ModuleDescriptor(
        String id,
        String name,
        String description,
        ModuleCategory category,
        ModuleRisk risk,
        boolean enabledByDefault,
        ModuleAvailability availability,
        String capabilityDetail
) {
    public ModuleDescriptor(
            String id,
            String name,
            String description,
            ModuleCategory category,
            ModuleRisk risk,
            boolean enabledByDefault
    ) {
        this(
                id,
                name,
                description,
                category,
                risk,
                enabledByDefault,
                ModuleAvailability.AVAILABLE,
                "Implemented by this platform"
        );
    }

    public ModuleDescriptor {
        id = Objects.requireNonNull(id, "id").trim().toLowerCase(Locale.ROOT);
        name = Objects.requireNonNull(name, "name").trim();
        description = Objects.requireNonNull(description, "description").trim();
        category = Objects.requireNonNull(category, "category");
        risk = Objects.requireNonNull(risk, "risk");
        availability = Objects.requireNonNull(availability, "availability");
        capabilityDetail = Objects.requireNonNull(capabilityDetail, "capabilityDetail").trim();
        if (!id.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid module id: " + id);
        }
        if (name.isEmpty() || description.isEmpty() || capabilityDetail.isEmpty()) {
            throw new IllegalArgumentException("Module text fields must not be blank");
        }
    }

    public boolean available() {
        return availability == ModuleAvailability.AVAILABLE;
    }
}
