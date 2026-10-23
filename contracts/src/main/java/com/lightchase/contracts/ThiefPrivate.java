package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class ThiefPrivate {
    public final String carriedMaterialId;
    public final Protection protection;
    public final int protectionRemainingTicks;
    public final java.util.List<GroundMaterial> groundMaterials;
    public final java.util.List<String> deliveredMaterialIds;
    public final InteractionView interaction;
    public ThiefPrivate(String carriedMaterialId, Protection protection, int protectionRemainingTicks, java.util.List<GroundMaterial> groundMaterials, java.util.List<String> deliveredMaterialIds, InteractionView interaction) {
        this.carriedMaterialId = carriedMaterialId;
        this.protection = protection;
        this.protectionRemainingTicks = protectionRemainingTicks;
        this.groundMaterials = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(groundMaterials));
        this.deliveredMaterialIds = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(deliveredMaterialIds));
        this.interaction = interaction;
    }
}
