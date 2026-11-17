package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record ThiefPrivate(String carriedMaterialId,Protection protection,int protectionRemainingTicks,long invisibleUntilTick,long markImmuneUntilTick,List<GroundMaterial> groundMaterials,List<String> deliveredMaterialIds,CardInstance card) {
    public ThiefPrivate {
        groundMaterials=Collections.unmodifiableList(new ArrayList<>(groundMaterials));
        deliveredMaterialIds=Collections.unmodifiableList(new ArrayList<>(deliveredMaterialIds));
    }
}
