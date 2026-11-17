package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record ActionResult(String matchId,long actionId,int slot,String expectedItemId,boolean success,String code,long appliedTick) {}
