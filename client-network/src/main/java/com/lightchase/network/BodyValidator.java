package com.lightchase.network;
import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
/** Strict structural validation before Gson can substitute defaults or ignore typos.
 * Cross-field/gameplay constraints remain the receiving state machine's responsibility. */
final class BodyValidator {
    private static final Set<String> NULLABLE=new HashSet<>(Arrays.asList(
        "RoomState.matchId","RoomPlayer.role","PlayerView.opponent","PlayerView.thief",
        "ThiefPrivate.carriedMaterialId","InteractionView.candidate","InteractionView.active",
        "MatchResult.winner","MatchResult.departedPlayerId"));
    static void validate(JsonElement e,Type type){
        if(e==null||e.isJsonNull())bad();
        if(type instanceof ParameterizedType){
            ParameterizedType p=(ParameterizedType)type;
            if(p.getRawType()!=List.class||!e.isJsonArray())bad();
            for(JsonElement item:e.getAsJsonArray())validate(item,p.getActualTypeArguments()[0]);
            return;
        }
        Class<?> c=(Class<?>)type;
        if(c==String.class){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())bad();return;}
        if(c==boolean.class){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())bad();return;}
        if(c.isEnum()){
            validate(e,String.class);boolean found=false;
            for(Object value:c.getEnumConstants())if(((Enum<?>)value).name().equals(e.getAsString()))found=true;
            if(!found)bad();return;
        }
        if(c==int.class||c==long.class||c==double.class){
            if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())bad();
            try{
                if(c==int.class)e.getAsBigDecimal().intValueExact();
                else if(c==long.class){long n=e.getAsBigDecimal().longValueExact();if(n<0||n>9007199254740991L)bad();}
                else if(!Double.isFinite(e.getAsDouble()))bad();
            }catch(ArithmeticException ex){bad();}return;
        }
        if(!e.isJsonObject())bad();
        JsonObject o=e.getAsJsonObject();Set<String> names=new HashSet<>();
        for(Field f:c.getFields()){
            if(Modifier.isStatic(f.getModifiers()))continue;
            names.add(f.getName());JsonElement value=o.get(f.getName());
            if(value==null)bad();
            if(value.isJsonNull()){if(!NULLABLE.contains(c.getSimpleName()+"."+f.getName()))bad();}
            else validate(value,f.getGenericType());
        }
        if(!o.keySet().equals(names))bad();
        if(c.getSimpleName().equals("RoomPlayer"))oneOf(o,"connection","CONNECTED","SUSPECT");
        if(c.getSimpleName().equals("InteractionView"))oneOf(o,"blockedReason","NONE","INVISIBLE","MOVING","NO_TARGET","RELEASE_REQUIRED");
        if(c.getSimpleName().equals("PlayerView")){
            boolean police=o.get("role").getAsString().equals("POLICE");
            if(police!=o.get("thief").isJsonNull())bad();
        }
    }
    private static void oneOf(JsonObject o,String key,String... values){if(!Arrays.asList(values).contains(o.get(key).getAsString()))bad();}
    private static void bad(){throw new IllegalArgumentException("BAD_MESSAGE");}
}
