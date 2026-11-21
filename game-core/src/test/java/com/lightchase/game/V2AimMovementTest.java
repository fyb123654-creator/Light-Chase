package com.lightchase.game;

import com.lightchase.contracts.WallCell;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.RuleConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class V2AimMovementTest {

    @Test
    public void aimIsIndependentFromMovementAndPersistsWhenNull() {
        MatchEngine engine = V2TestData.engine();

        V2TestData.tick(engine, 1,
                V2TestData.police(1, 1.0, 0.0, Math.PI, false),
                V2TestData.thief(1, 0, 0, false));
        PlayerView view = engine.viewFor(V2TestData.POLICE);
        assertEquals(Math.PI, view.self().aimRad(), 1e-9);
        assertEquals(0.0, view.self().headingRad(), 1e-9);
        assertTrue(view.self().x() > 3.0);

        V2TestData.tick(engine, 2,
                V2TestData.police(2, 1.0, 0.0, null, false),
                V2TestData.thief(2, 0, 0, false));
        view = engine.viewFor(V2TestData.POLICE);
        assertEquals(Math.PI, view.self().aimRad(), 1e-9);
        assertTrue(view.self().x() > 4.0);

        V2TestData.tick(engine, 3,
                V2TestData.police(3, 1.0, 0.0, 0.0, false),
                V2TestData.thief(3, 0, 0, false));
        assertEquals(0.0, engine.viewFor(V2TestData.POLICE).self().aimRad(), 1e-9);
    }

    @Test
    public void thiefMustNotSendAim() {
        MatchEngine engine = V2TestData.engine();
        assertThrows(IllegalArgumentException.class, () -> V2TestData.tick(engine, 1,
                V2TestData.police(1, 0, 0, null, false),
                V2TestData.command(V2TestData.THIEF, 1, 0, 0, 0.5, false, null)));
        assertEquals(0L, engine.viewFor(V2TestData.THIEF).tick());
    }

    @Test
    public void headingFollowsMovementAndDiagonalIsNormalized() {
        MatchEngine engine = V2TestData.engine();
        V2TestData.tick(engine, 1,
                V2TestData.police(1, 0, 1, null, false),
                V2TestData.thief(1, 0, 0, false));
        PlayerView police = engine.viewFor(V2TestData.POLICE);
        assertEquals(Math.PI / 2.0, police.self().headingRad(), 1e-9);
        double y = police.self().y();

        MatchEngine second = V2TestData.engine();
        V2TestData.tick(second, 1,
                V2TestData.police(1, 1, 1, null, false),
                V2TestData.thief(1, 0, 0, false));
        PlayerView diagonal = second.viewFor(V2TestData.POLICE);
        assertEquals(1.0 / Math.sqrt(2.0), diagonal.self().x() - 2.5, 1e-6);
        assertEquals(1.0 / Math.sqrt(2.0), diagonal.self().y() - 6.5, 1e-6);
        assertTrue(Math.abs(diagonal.self().y() - y) < 1.0);
    }

    @Test
    public void wallBlocksMovementButStillTurnsTheActor() {
        RuleConfig rules = V2TestRules.fast();
        MatchEngine engine = V2TestData.factory().create(
                V2TestMaps.open(java.util.List.of(new WallCell(3, 6))),
                rules, V2TestData.init());

        V2TestData.tick(engine, 1,
                V2TestData.police(1, 1, 0, null, false),
                V2TestData.thief(1, 0, 0, false));
        PlayerView police = engine.viewFor(V2TestData.POLICE);
        assertEquals(2.7, police.self().x(), 1e-9);
        assertEquals(6.5, police.self().y(), 1e-9);
        assertEquals(0.0, police.self().headingRad(), 1e-9);
    }

    @Test
    public void ackInputSeqTracksTheLastConsumedCommand() {
        MatchEngine engine = V2TestData.engine();
        V2TestData.tick(engine, 1,
                V2TestData.police(7, 0, 0, null, false),
                V2TestData.thief(3, 0, 0, false));
        assertEquals(7L, engine.viewFor(V2TestData.POLICE).ackInputSeq());
        assertEquals(3L, engine.viewFor(V2TestData.THIEF).ackInputSeq());

        V2TestData.tick(engine, 2);
        assertEquals(7L, engine.viewFor(V2TestData.POLICE).ackInputSeq());
        assertEquals(3L, engine.viewFor(V2TestData.THIEF).ackInputSeq());
    }

    @Test
    public void rejectsMoveOutsideUnitRange() {
        MatchEngine engine = V2TestData.engine();
        assertThrows(IllegalArgumentException.class, () -> V2TestData.tick(engine, 1,
                V2TestData.police(1, 1.2, 0, null, false),
                V2TestData.thief(1, 0, 0, false)));
    }
}
