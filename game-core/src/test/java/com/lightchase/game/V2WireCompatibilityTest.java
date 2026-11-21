package com.lightchase.game;

import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.v2.ActionResult;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.PublicEvent;
import com.lightchase.contracts.v2.StepResult;
import com.lightchase.contracts.v2.StepInput;
import com.lightchase.data.V2DataFiles;
import com.lightchase.network.V2WireCodec;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Test-only seam against B's strict codec. This catches an A view that compiles but cannot
 * legally travel on the wire (for example a missing police beam or a transit with no motion).
 */
public class V2WireCompatibilityTest {

    private static V2WireCodec codec() {
        return new V2WireCodec();
    }

    private static void roundTripSnapshot(V2WireCodec codec, String matchId, PlayerView view,
                                          long seq) {
        String text = codec.encodeServer("SNAPSHOT", seq, null, matchId, view);
        PlayerView decoded = codec.body(codec.decodeServer(text), PlayerView.class);
        assertEquals(view.matchId(), decoded.matchId());
        assertEquals(view.tick(), decoded.tick());
        assertEquals(view.role(), decoded.role());
        assertEquals(view.transits().size(), decoded.transits().size());
    }

    @Test
    public void productionViewsSurviveTheStrictCodec() {
        V2DataFiles data = V2DataFiles.bundled();
        MatchInit init = new MatchInit("codec-v2", V2TestData.POLICE, V2TestData.THIEF);
        MatchEngine engine = new V2EngineFactory().create(data.map, data.rules, init);
        V2WireCodec codec = codec();

        roundTripSnapshot(codec, "codec-v2", engine.viewFor(V2TestData.POLICE), 1);
        roundTripSnapshot(codec, "codec-v2", engine.viewFor(V2TestData.THIEF), 1);

        engine.step(new StepInput(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)),
                List.of(), List.of()));
        roundTripSnapshot(codec, "codec-v2", engine.viewFor(V2TestData.POLICE), 2);
        roundTripSnapshot(codec, "codec-v2", engine.viewFor(V2TestData.THIEF), 2);
    }

    @Test
    public void transitViewsAndEventsSurviveTheStrictCodec() {
        MatchEngine engine = new V2EngineFactory().create(V2TestMaps.cardLab(),
                V2TestRules.fast(), new MatchInit("codec-v2", V2TestData.POLICE, V2TestData.THIEF));
        V2WireCodec codec = codec();

        engine.step(V2TestData.step(1, List.of(V2TestData.police(1, 0, 0, null, false),
                V2TestData.thief(1, 0, 0, true)), List.of()));
        engine.step(V2TestData.step(2, List.of(V2TestData.police(2, 0, 0, null, false),
                V2TestData.thief(2, 0, 0, false)), List.of()));
        engine.step(V2TestData.step(3, List.of(V2TestData.police(3, 0, 0, null, false),
                V2TestData.thief(3, 0, 0, true)), List.of()));
        engine.step(V2TestData.step(4, List.of(V2TestData.police(4, 0, 0, null, false),
                V2TestData.thief(4, 0, 0, false)), List.of()));
        StepResult enter = engine.step(V2TestData.step(5,
                List.of(V2TestData.police(5, 0, 0, null, false),
                        V2TestData.thief(5, 0, 0, true)), List.of()));

        roundTripSnapshot(codec, "codec-v2", engine.viewFor(V2TestData.POLICE), 3);
        roundTripSnapshot(codec, "codec-v2", engine.viewFor(V2TestData.THIEF), 3);

        assertEquals(1, enter.events().size());
        PublicEvent event = enter.events().get(0);
        String eventText = codec.encodeServer("PUBLIC_EVENT", 4, null, "codec-v2", event);
        PublicEvent decodedEvent = codec.body(codec.decodeServer(eventText), PublicEvent.class);
        assertEquals(event.transitId(), decodedEvent.transitId());
        assertEquals(event.type(), decodedEvent.type());
    }

    @Test
    public void actionResultsSurviveTheStrictCodec() {
        MatchEngine engine = new V2EngineFactory().create(V2TestMaps.lab(),
                V2TestRules.fast(), new MatchInit("codec-v2", V2TestData.POLICE, V2TestData.THIEF));
        engine.step(V2TestData.step(1, List.of(V2TestData.police(1, 0, 0, null, false),
                V2TestData.thief(1, 0, 0, true)), List.of()));
        engine.step(V2TestData.step(2, List.of(V2TestData.police(2, 0, 0, null, false),
                V2TestData.thief(2, 0, 0, false)), List.of()));
        StepResult result = engine.step(V2TestData.step(3,
                List.of(V2TestData.police(3, 0, 0, null, false),
                        V2TestData.thief(3, 0, 0, false)),
                List.of(V2TestData.use(V2TestData.THIEF, 1, 0, "S1"))));

        ActionResult action = result.actionResults().get(0).result();
        assertNotNull(action);
        V2WireCodec codec = codec();
        String text = codec.encodeServer("ACTION_RESULT", 5, "a:1", "codec-v2", action);
        ActionResult decoded = codec.body(codec.decodeServer(text), ActionResult.class);
        assertEquals(action.success(), decoded.success());
        assertEquals(action.code(), decoded.code());
    }
}
