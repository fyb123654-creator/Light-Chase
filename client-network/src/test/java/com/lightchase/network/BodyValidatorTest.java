package com.lightchase.network;
import com.lightchase.contracts.*;
import com.google.gson.*;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class BodyValidatorTest {
    private JsonObject sample() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("../docs/development/fixtures/room-lobby.json"))).getAsJsonObject();
    }
    @Test public void sharedFixtureAcceptsExplicitNullableFields() throws Exception {
        BodyValidator.validate(sample(),RoomState.class);
    }
    @Test public void missingOrUnknownFieldsFail() throws Exception {
        JsonObject o=sample();o.remove("matchId");assertThrows(IllegalArgumentException.class,()->BodyValidator.validate(o,RoomState.class));
        JsonObject extra=sample();extra.addProperty("playerID","p1");assertThrows(IllegalArgumentException.class,()->BodyValidator.validate(extra,RoomState.class));
    }
    @Test public void invalidEnumNullAndCoercedNumbersFail() throws Exception {
        JsonObject o=sample();o.addProperty("phase","PLAYING");assertThrows(IllegalArgumentException.class,()->BodyValidator.validate(o,RoomState.class));
        o.add("phase",JsonNull.INSTANCE);assertThrows(IllegalArgumentException.class,()->BodyValidator.validate(o,RoomState.class));
        o.addProperty("phase","LOBBY");o.addProperty("revision","1");assertThrows(IllegalArgumentException.class,()->BodyValidator.validate(o,RoomState.class));
    }
    @Test public void nestedTyposFail() throws Exception {
        JsonObject o=sample();o.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("readey",true);
        assertThrows(IllegalArgumentException.class,()->BodyValidator.validate(o,RoomState.class));
    }
}
