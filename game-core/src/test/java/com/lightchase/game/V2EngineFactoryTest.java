package com.lightchase.game;

import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.MapDefinition;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.RuleConfig;
import com.lightchase.contracts.v2.StepResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class V2EngineFactoryTest {

    @Test
    public void createsEngineAndProjectsInitialViews() {
        MatchEngine engine = V2TestData.engine();

        PlayerView police = engine.viewFor(V2TestData.POLICE);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);

        assertEquals(0L, police.tick());
        assertEquals(0L, thief.tick());
        assertEquals(Role.POLICE, police.role());
        assertEquals(Role.THIEF, thief.role());
        assertEquals(V2TestRules.fast().durationTicks(), police.remainingTicks());
        assertNotNull(police.self().beam());
        assertNotNull(police.self().aimRad());
        assertNull(police.thief());
        assertNotNull(thief.thief());
        assertNull(thief.self().aimRad());
        assertNull(thief.self().beam());
        assertNull(engine.result());
    }

    @Test
    public void firstStepIsVisibilityChangedAndTickAdvances() {
        MatchEngine engine = V2TestData.engine();
        StepResult result = V2TestData.tick(engine, 1,
                V2TestData.police(1, 0, 0, null, false),
                V2TestData.thief(1, 0, 0, false));

        assertEquals(1L, result.tick());
        assertTrue(result.visibilityChanged());
        assertFalse(result.ended());
        assertNull(result.result());
        assertEquals(1L, engine.viewFor(V2TestData.POLICE).tick());
    }

    @Test
    public void rejectsNonConsecutiveTick() {
        MatchEngine engine = V2TestData.engine();
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.tick(engine, 2,
                        V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)));
    }

    @Test
    public void rejectsUnknownPlayerView() {
        MatchEngine engine = V2TestData.engine();
        assertThrows(IllegalArgumentException.class, () -> engine.viewFor("nobody"));
    }

    @Test
    public void rejectsInvalidRuleConfig() {
        RuleConfig bad = new RuleConfig(3, 8, 16.0 / 9.0, 4, 60, 2, 2, 2, 0.8, 2, 3,
                600, 20, 0, 0.25, 1.5, 1.5, 10, 10, 10, 5, 1, 1, 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(V2TestMaps.open(), bad, V2TestData.init()));
    }

    @Test
    public void rejectsMapWithoutTwoPortals() {
        MapDefinition map = V2TestMaps.open();
        MapDefinition bad = new MapDefinition(map.mapId(), map.mapVersion(), map.width(),
                map.height(), map.walls(), map.spawns(), map.materials(), map.deliveries(),
                map.exits(), map.itemSpawns(), map.cardSpawns(),
                List.of(map.portals().get(0)));
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad, V2TestRules.fast(), V2TestData.init()));
    }

    @Test
    public void abortIsIdempotentAndResultIsFixed() {
        MatchEngine engine = V2TestData.engine();
        MatchResult first = engine.abort();
        MatchResult second = engine.abort();

        assertNotNull(first);
        assertEquals(ResultReason.SERVER_ABORTED, first.reason);
        assertNull(first.winner);
        assertEquals(first, second);
        StepResult after = V2TestData.tick(engine, 1,
                V2TestData.police(1, 0, 0, null, false),
                V2TestData.thief(1, 0, 0, false));
        assertTrue(after.ended());
        assertEquals(0L, after.tick());
        assertTrue(after.actionResults().isEmpty());
        assertTrue(after.events().isEmpty());
    }

    @Test
    public void rejectsUnknownCommandOrActionPlayersWithoutAdvancing() {
        MatchEngine commandEngine = V2TestData.engine();
        assertThrows(IllegalArgumentException.class, () -> commandEngine.step(
                V2TestData.step(1, V2TestData.command("nobody", 1, 0, 0, null, false, null))));
        assertEquals(0L, commandEngine.viewFor(V2TestData.POLICE).tick());

        MatchEngine actionEngine = V2TestData.engine();
        assertThrows(IllegalArgumentException.class, () -> actionEngine.step(
                V2TestData.step(1,
                        List.of(V2TestData.police(1, 0, 0, null, false),
                                V2TestData.thief(1, 0, 0, false)),
                        List.of(V2TestData.use("nobody", 1, 0, "S1")))));
        assertEquals(0L, actionEngine.viewFor(V2TestData.POLICE).tick());
    }
}
