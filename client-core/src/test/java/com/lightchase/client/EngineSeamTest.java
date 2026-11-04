package com.lightchase.client;

import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.PlayerCommand;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.StepResult;
import com.lightchase.game.V1EngineFactory;
import com.lightchase.data.DataFiles;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The seam that lets offline mode run the real rule engine instead of a copy of it.
 *
 * <p>This is the whole point of the change: the client builds A's {@code MatchEngine} through the
 * public factory in {@code game-core}, feeds it the same {@code PlayerCommand}s the server would,
 * and reads the same {@code PlayerView}. No rule lives in the client any more.
 */
public class EngineSeamTest {

    private static final String POLICE = "seam-police";
    private static final String THIEF = "seam-thief";

    private MatchEngine engine() {
        DataFiles data = DataFiles.bundled();
        return new V1EngineFactory().create(data.map, data.rules,
                new MatchInit("seam-match", POLICE, THIEF));
    }

    @Test
    public void theClientCanDriveTheRealEngine() {
        MatchEngine engine = engine();
        PlayerView start = engine.viewFor(THIEF);
        assertNotNull("the engine must project a view for a player", start);
        assertNotNull(start.thief);
        assertEquals("the thief starts where the map says",
                DataFiles.bundled().map.spawns.get(Role.THIEF).x, start.self.x, 0.001);
    }

    @Test
    public void aCommandMovesTheThiefThroughTheRealRules() {
        MatchEngine engine = engine();
        double before = engine.viewFor(THIEF).self.x;

        // Push right for half a second of ticks and expect the authoritative x to follow.
        long tick = 0;
        for (int i = 0; i < 15; i++) {
            tick++;
            List<PlayerCommand> commands = Collections.singletonList(
                    new PlayerCommand(THIEF, tick, 1.0, 0.0, false, null));
            StepResult result = engine.step(new StepInput(tick, commands, Collections.emptyList()));
            assertNotNull(result);
            if (result.ended) break;
        }
        double after = engine.viewFor(THIEF).self.x;
        assertTrue("the thief should have moved right, was " + before + " now " + after,
                after > before);
    }

    @Test
    public void theEngineReportsAMatchResultWhenItEnds() {
        MatchEngine engine = engine();
        MatchResult aborted = engine.abort();
        assertNotNull("aborting must still produce a result", aborted);
    }
}
