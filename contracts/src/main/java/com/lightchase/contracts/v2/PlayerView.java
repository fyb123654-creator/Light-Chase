package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record PlayerView(String matchId,long tick,long serverTimeMs,long ackInputSeq,Role role,int remainingTicks,int marks,int deliveredCount,boolean exitsOpen,ActorPose self,VisibleOpponent opponent,List<OriginStatus> originStatus,ThiefPrivate thief,List<ItemInstance> inventory,List<EffectView> effects,InteractionView interaction,List<GroundItem> groundItems,List<GroundCard> groundCards,List<PortalState> portals,List<TransitView> transits) {
    public PlayerView {
        originStatus=Collections.unmodifiableList(new ArrayList<>(originStatus));
        inventory=Collections.unmodifiableList(new ArrayList<>(inventory));
        effects=Collections.unmodifiableList(new ArrayList<>(effects));
        groundItems=Collections.unmodifiableList(new ArrayList<>(groundItems));
        groundCards=Collections.unmodifiableList(new ArrayList<>(groundCards));
        portals=Collections.unmodifiableList(new ArrayList<>(portals));
        transits=Collections.unmodifiableList(new ArrayList<>(transits));
    }
}
