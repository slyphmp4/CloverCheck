package com.slyph.clovercheck.model;

import java.util.Objects;

public record StoredLocation(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public StoredLocation {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
    }
}
