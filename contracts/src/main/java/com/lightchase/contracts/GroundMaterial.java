package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class GroundMaterial {
    public final String materialId;
    public final double x;
    public final double y;
    public GroundMaterial(String materialId, double x, double y) {
        this.materialId = materialId;
        this.x = x;
        this.y = y;
    }
}
