package com.lightchase.game;

import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.StepResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2OutcomeTest {

    @Test
    public void oneDepartureAwardsTheWinToTheOtherRole() {
        MatchEngine engine = V2TestData.engine();
        StepResult result = engine.step(V2TestData.step(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)),
                List.of(),
                List.of(V2TestData.departure(V2TestData.POLICE, DepartureReason.QUIT))));

        assertTrue(result.ended());
        assertEquals(ResultReason.QUIT, result.result().reason);
        assertEquals(Role.THIEF, result.result().winner);
        assertEquals(V2TestData.POLICE, result.result().departedPlayerId);
    }

    @Test
    public void twoDeparturesAreBothLeftEvenWithDifferentReasons() {
        MatchEngine engine = V2TestData.engine();
        StepResult result = engine.step(V2TestData.step(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)),
                List.of(),
                List.of(V2TestData.departure(V2TestData.POLICE, DepartureReason.QUIT),
                        V2TestData.departure(V2TestData.THIEF, DepartureReason.DISCONNECT))));

        assertTrue(result.ended());
        assertEquals(ResultReason.BOTH_LEFT, result.result().reason);
        assertNull(result.result().winner);
    }

    @Test
    public void normalTimeoutWinsOverADepartureInTheSameStep() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.open(),
                V2TestRules.duration(1), V2TestData.init());
        StepResult result = engine.step(V2TestData.step(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)),
                List.of(),
                List.of(V2TestData.departure(V2TestData.POLICE, DepartureReason.QUIT))));

        assertEquals(ResultReason.TIMEOUT, result.result().reason);
        assertEquals(Role.POLICE, result.result().winner);
    }

    @Test
    public void backgroundAndDisconnectMapToTheirReasons() {
        MatchEngine background = V2TestData.engine();
        StepResult a = background.step(V2TestData.step(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)),
                List.of(),
                List.of(V2TestData.departure(V2TestData.THIEF, DepartureReason.BACKGROUND))));
        assertEquals(ResultReason.BACKGROUND, a.result().reason);

        MatchEngine disconnect = V2TestData.engine();
        StepResult b = disconnect.step(V2TestData.step(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)),
                List.of(),
                List.of(V2TestData.departure(V2TestData.THIEF, DepartureReason.DISCONNECT))));
        assertEquals(ResultReason.DISCONNECT, b.result().reason);
    }

    @Test
    public void endedEngineReturnsTheSameResultWithoutAdvancing() {
        MatchEngine engine = V2TestData.engine();
        StepResult first = engine.step(V2TestData.step(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)),
                List.of(),
                List.of(V2TestData.departure(V2TestData.POLICE, DepartureReason.QUIT))));
        StepResult second = V2TestData.tick(engine, 2);

        assertTrue(second.ended());
        assertEquals(first.tick(), second.tick());
        assertEquals(first.result().reason, second.result().reason);
        assertTrue(second.actionResults().isEmpty());
        assertTrue(second.events().isEmpty());
    }
}
