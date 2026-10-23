package com.lightchase.game;

import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.PlayerView;
import com.lightchase.data.DataFiles;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FormalMapEngineTest {
    @Test
    public void formalMapStartsEngineWithAllMaterials() {
        DataFiles data = DataFiles.bundledFormal();
        MatchEngine engine = new V1EngineFactory().create(
                data.map, data.rules, new MatchInit("formal", "p", "t"));

        PlayerView thief = engine.viewFor("t");
        assertNotNull(thief.thief);
        assertEquals(0L, thief.tick);
        assertEquals(0, thief.marks);
        assertEquals(0, thief.deliveredCount);
        assertEquals(3, thief.thief.groundMaterials.size());
        assertNull(thief.thief.carriedMaterialId);
        assertTrue(thief.thief.deliveredMaterialIds.isEmpty());
        assertNull(engine.result());

        PlayerView police = engine.viewFor("p");
        assertNull(police.thief);

        engine.step(TestScripts.empty(1));
        assertEquals(1L, engine.viewFor("t").tick);
        assertNull(engine.result());
    }
}
