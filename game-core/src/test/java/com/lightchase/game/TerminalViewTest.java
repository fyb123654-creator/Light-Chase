package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.ThiefPrivate;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The terminal snapshot must not advertise an interaction that can no longer start. */
public class TerminalViewTest {
    private static MatchEngine engine(RuleConfig rules) {
        return new V1EngineFactory().create(
                TestScripts.simpleTaskMap(), rules, TestMaps.init());
    }

    private static void assertInteractionCleared(MatchEngine engine) {
        ThiefPrivate thief = engine.viewFor("t").thief;
        assertNotNull(thief);
        assertNull(thief.interaction.candidate);
        assertNull(thief.interaction.active);
        assertEquals(0, thief.interaction.progressTicks);
        assertFalse(thief.interaction.requiresRelease);
        assertEquals("NO_TARGET", thief.interaction.blockedReason);
    }

    @Test
    public void captureClearsInteractionCandidate() {
        MatchEngine engine = new V1EngineFactory().create(
                TestScripts.threeTaskMapAt(2.5, 5.5),
                TestMaps.rules(100, 1, 0, 0),
                TestMaps.init());
        assertNotNull(engine.viewFor("t").thief.interaction.candidate);

        engine.step(TestScripts.empty(1));
        engine.step(TestScripts.empty(2));
        engine.step(TestScripts.empty(3));

        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.CAPTURED, result.reason);
        assertEquals(3, result.marks);
        assertInteractionCleared(engine);
    }

    @Test
    public void timeoutClearsInteractionCandidate() {
        MatchEngine engine = engine(TestMaps.rules(2));
        assertNotNull(engine.viewFor("t").thief.interaction.candidate);

        engine.step(TestScripts.empty(1));
        engine.step(TestScripts.empty(2));

        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.TIMEOUT, result.reason);
        assertInteractionCleared(engine);
    }

    @Test
    public void singleDepartureClearsInteractionCandidate() {
        MatchEngine engine = engine(TestMaps.rules(100));
        assertNotNull(engine.viewFor("t").thief.interaction.candidate);

        engine.step(new StepInput(1, Collections.emptyList(),
                Arrays.asList(new Departure("p", DepartureReason.QUIT))));

        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.QUIT, result.reason);
        assertInteractionCleared(engine);
    }

    @Test
    public void abortClearsInteractionCandidate() {
        MatchEngine engine = engine(TestMaps.rules(100));
        assertNotNull(engine.viewFor("t").thief.interaction.candidate);

        MatchResult aborted = engine.abort();
        assertEquals(ResultReason.SERVER_ABORTED, aborted.reason);
        assertTrue(engine.step(TestScripts.empty(1)).ended);
        assertInteractionCleared(engine);
    }
}
