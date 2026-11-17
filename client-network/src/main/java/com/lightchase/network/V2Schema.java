package com.lightchase.network;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;

/** Strict R3 shape checking before Gson can coerce values or default missing fields. */
final class V2Schema {
    private static final Set<String> NULLABLE=Set.of(
        "InputBody.aimRad","InputBody.dropId","ActorPose.aimRad","ActorPose.beam",
        "VisibleOpponent.aimRad","VisibleOpponent.beam","PlayerView.opponent","PlayerView.thief",
        "ThiefPrivate.carriedMaterialId","ThiefPrivate.card","InteractionView.candidate",
        "InteractionView.active","InteractionTarget.expectedCardId");
    static void validate(JsonElement e,Type type,String path) {
        if(e==null)bad();
        if(e.isJsonNull()) { if(!NULLABLE.contains(path)&&!path.equals("PlayerView.inventory[]"))bad(); return; }
        if(type instanceof ParameterizedType p) {
            if(p.getRawType()!=List.class||!e.isJsonArray())bad();
            for(JsonElement item:e.getAsJsonArray())validate(item,p.getActualTypeArguments()[0],path+"[]");
            return;
        }
        Class<?> c=(Class<?>)type;
        if(!c.isRecord()) {
            if(c==Double.class)c=double.class;
            if(c==Long.class)c=long.class;
            BodyValidator.validate(e,c); return;
        }
        if(!e.isJsonObject())bad();
        JsonObject o=e.getAsJsonObject();Set<String> fields=new HashSet<>();
        for(RecordComponent f:c.getRecordComponents()) {
            fields.add(f.getName()); validate(o.get(f.getName()),f.getGenericType(),c.getSimpleName()+"."+f.getName());
        }
        if(!fields.equals(o.keySet()))bad();
    }
    static void bad(){throw new IllegalArgumentException("BAD_MESSAGE");}
}
