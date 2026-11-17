package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record TransitView(String transitId,String portalId,String playerId,Position from,Position to,long startTick,long endTick) {}
