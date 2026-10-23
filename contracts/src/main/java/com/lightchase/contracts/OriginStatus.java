package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class OriginStatus {
    public final String materialId;
    public final boolean everPicked;
    public OriginStatus(String materialId, boolean everPicked) {
        this.materialId = materialId;
        this.everPicked = everPicked;
    }
}
