package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.*;
import java.util.*;

/** R3 codec shared by B's future v2 endpoints and test doubles. No gameplay execution. */
public final class V2WireCodec {
    private final WireCodec framing=new WireCodec();
    private final Gson gson=new GsonBuilder().serializeNulls().create();
    public WireCodec.Message decodeClient(String text){return decode(text,true);}
    public WireCodec.Message decodeServer(String text){return decode(text,false);}
    private WireCodec.Message decode(String text,boolean client){
        WireCodec.Message m=framing.decode(text);
        if(m.v!=Protocol.VERSION)throw new IllegalArgumentException("VERSION_MISMATCH");
        switch(m.type){
            case "INPUT" -> {if(!client)bad();correlate(m,false);InputBody b=body(m,InputBody.class);
                positive(b.inputSeq());input(b.moveX(),b.moveY(),b.aimRad());if(b.dropId()!=null)positive(b.dropId());}
            case "USE_ITEM" -> {if(!client)bad();correlate(m,true);UseItem b=body(m,UseItem.class);action(b.actionId(),b.slot(),b.expectedItemId());
                if(!m.requestId.equals("a:"+b.actionId()))bad();}
            case "ACTION_RESULT" -> {if(client)bad();correlate(m,true);ActionResult b=body(m,ActionResult.class);action(b.actionId(),b.slot(),b.expectedItemId());
                if(!m.matchId.equals(b.matchId())||!m.requestId.equals("a:"+b.actionId()))bad();
                if(!Set.of("OK","EMPTY_SLOT","ITEM_INSTANCE_MISMATCH","ROLE_FORBIDDEN","EFFECT_ACTIVE","IN_TRANSIT","MATCH_ENDED","STALE_ACTION").contains(b.code())||b.success()!=b.code().equals("OK"))bad();}
            case "PUBLIC_EVENT" -> {if(client)bad();correlate(m,false);PublicEvent b=body(m,PublicEvent.class);
                if(!m.matchId.equals(b.matchId())||!b.eventId().equals(b.transitId()))bad();id(b.eventId());id(b.portalId());}
            case "SNAPSHOT" -> {if(client)bad();correlate(m,false);PlayerView b=body(m,PlayerView.class);if(!m.matchId.equals(b.matchId()))bad();snapshot(b);}
            default -> {
                // Unchanged lobby/result framing and bodies retain the established strict schema.
                JsonObject base=WireJson.parse(text);base.addProperty("v",com.lightchase.contracts.Protocol.VERSION);
                if(client)framing.decodeClient(gson.toJson(base));else framing.decodeServer(gson.toJson(base));
                if(m.type.equals("WELCOME")&&m.body.get("protocolVersion").getAsInt()!=2)bad();
                if(m.requestId!=null&&m.requestId.startsWith("a:")&&!m.type.equals("ERROR"))bad();
            }
        }
        return m;
    }
    public <T>T body(WireCodec.Message message,Class<T> type){
        V2Schema.validate(message.body,type,"");return gson.fromJson(message.body,type);
    }
    public String encodeClient(String type,long seq,String requestId,String matchId,Object body){return encode(type,seq,requestId,matchId,body,true);}
    public String encodeServer(String type,long seq,String requestId,String matchId,Object body){return encode(type,seq,requestId,matchId,body,false);}
    private String encode(String type,long seq,String requestId,String matchId,Object body,boolean client){
        JsonObject o=new JsonObject();o.addProperty("v",2);o.addProperty("type",type);o.addProperty("seq",seq);
        o.add("requestId",gson.toJsonTree(requestId));o.add("matchId",gson.toJsonTree(matchId));o.add("body",gson.toJsonTree(body));
        String text=gson.toJson(o);decode(text,client);return text;
    }
    private static void snapshot(PlayerView b){
        id(b.matchId());if(b.role()==null||b.remainingTicks()<0||b.marks()<0||b.deliveredCount()<0)bad();
        if(b.inventory().size()!=2||b.portals().size()!=2||(b.role()==Role.POLICE)!=(b.thief()==null))bad();
        actor(b.self().playerId(),b.self().headingRad(),b.self().aimRad(),b.self().beam(),b.role());
        if(b.opponent()!=null){var v=b.opponent();if(v.role()==b.role()||v.playerId().equals(b.self().playerId()))bad();actor(v.playerId(),v.headingRad(),v.aimRad(),v.beam(),v.role());}
        Set<String> items=new HashSet<>();for(var i:b.inventory())if(i!=null){unique(items,i.itemId());owner(i.type(),b.role());}
        for(var i:b.groundItems()){unique(items,i.itemId());owner(i.type(),i.ownerRole());}
        Set<String> portals=new HashSet<>();for(var p:b.portals()){unique(portals,p.portalId());if(p.revision()!=(p.unlocked()?1:0))bad();}
        Set<EffectType> effects=new HashSet<>();for(var e:b.effects()){if(!effects.add(e.type())||e.untilTick()<=b.tick())bad();owner(ItemType.valueOf(e.type().name()),b.role());}
        Set<String> cards=new HashSet<>();for(var c:b.groundCards()){unique(cards,c.cardId());if(!portals.contains(c.portalId()))bad();}
        if(b.thief()!=null){var t=b.thief();if(t.card()!=null){unique(cards,t.card().cardId());if(!portals.contains(t.card().portalId()))bad();}
            String state=b.tick()<t.invisibleUntilTick()?"INVISIBLE":b.tick()<t.markImmuneUntilTick()?"IMMUNE":"NORMAL";
            long remaining=state.equals("INVISIBLE")?t.invisibleUntilTick()-b.tick():state.equals("IMMUNE")?t.markImmuneUntilTick()-b.tick():0;
            if(!t.protection().name().equals(state)||t.protectionRemainingTicks()!=remaining)bad();}
        var interaction=b.interaction();if(interaction.progressTicks()<0||interaction.requiredTicks()<0||interaction.progressTicks()>interaction.requiredTicks())bad();
        for(var t:Arrays.asList(interaction.candidate(),interaction.active()))if(t!=null){id(t.targetId());if((t.type()==InteractionType.UNLOCK_PORTAL)!=(t.expectedCardId()!=null))bad();if(t.expectedCardId()!=null)id(t.expectedCardId());}
        if(b.transits().size()>1)bad();for(var t:b.transits()){id(t.transitId());id(t.playerId());if(!portals.contains(t.portalId())||t.endTick()<=t.startTick()||b.tick()<t.startTick()||b.tick()>=t.endTick())bad();
            if(b.role()==Role.POLICE&&b.opponent()!=null)bad();
            if(b.role()==Role.THIEF){if(b.self().motion()!=Motion.TRANSIT||!t.playerId().equals(b.self().playerId()))bad();double z=(double)(b.tick()-t.startTick())/(t.endTick()-t.startTick());
                if(Math.abs(b.self().x()-(t.from().x()+z*(t.to().x()-t.from().x())))>1e-6||Math.abs(b.self().y()-(t.from().y()+z*(t.to().y()-t.from().y())))>1e-6)bad();}}
        if(b.self().motion()==Motion.TRANSIT&&(b.role()!=Role.THIEF||b.transits().isEmpty()))bad();
    }
    private static void actor(String player,double heading,Double aim,BeamView beam,Role role){id(player);angle(heading);if(role==Role.POLICE){if(aim==null||beam==null)bad();angle(aim);if(beam.range()<=0||beam.halfAngleRad()<=0||beam.halfAngleRad()>Math.PI)bad();}else if(aim!=null||beam!=null)bad();}
    private static void owner(ItemType type,Role role){if(role==null||type==ItemType.LIGHT_BOOST&&role!=Role.POLICE||type==ItemType.SHIELD&&role!=Role.THIEF)bad();}
    private static void correlate(WireCodec.Message m,boolean request){id(m.matchId);if(request!=(m.requestId!=null))bad();}
    private static void action(long id,int slot,String item){positive(id);if(slot<0||slot>1)bad();id(item);}
    private static void input(double x,double y,Double aim){if(Math.abs(x)>1||Math.abs(y)>1)bad();if(aim!=null)angle(aim);}
    private static void positive(long x){if(x<1)bad();}
    private static void angle(double a){if(!Double.isFinite(a)||a<0||a>=2*Math.PI)bad();}
    private static void unique(Set<String> set,String value){id(value);if(!set.add(value))bad();}
    private static void id(String s){if(s==null||s.isEmpty()||s.length()>64)bad();}
    private static void bad(){throw new IllegalArgumentException("BAD_MESSAGE");}
}
