package com.lightchase.client;

import com.lightchase.contracts.*;
import com.lightchase.game.V1EngineFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * What C draws, checked against the engine that actually decides it (A's {@code V1EngineFactory}).
 *
 * <p>A owns the rules and tests them thoroughly; this file exists because the HUD consumes a narrow
 * slice of {@link PlayerView} and a silent change to that slice would otherwise only be noticed on a
 * device. Every assertion names the widget it protects, so a failure says which part of the screen
 * would have shown the wrong thing.
 *
 * <p>The map here is a deliberate miniature: every task point sits under the thief's feet and there
 * are no walls, so the test exercises the <em>reported values</em> rather than navigation.
 */
public class EngineFieldStoryTest {

    private static final String POLICE = "p";
    private static final String THIEF = "t";

    // ------------------------------------------------------------------ fixtures

    private static MapDefinition storyMap(double policeX, double policeY, double policeHeading) {
        Map<Role, SpawnPose> spawns = new LinkedHashMap<>();
        spawns.put(Role.POLICE, new SpawnPose(policeX, policeY, policeHeading));
        spawns.put(Role.THIEF, new SpawnPose(5.5, 4.5, Math.PI));

        List<MaterialPoint> materials = new ArrayList<>();
        List<MapPoint> deliveries = new ArrayList<>();
        List<MapPoint> exits = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            materials.add(new MaterialPoint("M" + i, 5.5, 4.5, "D" + i));
            deliveries.add(new MapPoint("D" + i, 5.5, 4.5));
        }
        exits.add(new MapPoint("E1", 5.5, 4.5));
        exits.add(new MapPoint("E2", 5.5, 4.5));
        return new MapDefinition("c-story", 1, 12, 9, new ArrayList<>(), spawns,
                materials, deliveries, exits);
    }

    private static RuleConfig rules() {
        return new RuleConfig(3, 8, 16.0 / 9.0, 4, 60, 24, 45, 30, 0.8, 30, 3, 5400, 30, 90, 0.25);
    }

    private static MatchEngine engine(double policeX, double policeY, double policeHeading) {
        return new V1EngineFactory().create(storyMap(policeX, policeY, policeHeading), rules(),
                new MatchInit("m", POLICE, THIEF));
    }

    private static StepInput thiefHolds(long tick, boolean held) {
        return new StepInput(tick,
                Collections.singletonList(new PlayerCommand(THIEF, tick, 0, 0, held, null)),
                Collections.emptyList());
    }

    private static StepInput policeFaces(long tick, double x, double y) {
        return new StepInput(tick,
                Collections.singletonList(new PlayerCommand(POLICE, tick, x, y, false, null)),
                Collections.emptyList());
    }

    /** Run {@code steps} ticks of holding for the thief. */
    private static long hold(MatchEngine e, long tick, int steps) {
        for (int i = 0; i < steps; i++) { e.step(thiefHolds(tick++, true)); }
        return tick;
    }

    private static long release(MatchEngine e, long tick) {
        e.step(thiefHolds(tick++, false));
        return tick;
    }

    /** Exactly what the "ORIGINS TAKEN ..." line on the match HUD would print. */
    private static String takenLine(PlayerView v) {
        StringBuilder b = new StringBuilder();
        for (OriginStatus s : v.originStatus) {
            if (!s.everPicked) continue;
            if (b.length() > 0) b.append(' ');
            b.append(s.materialId);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    // ------------------------------------------------------------------ escape

    @Test public void theThiefStoryTheHudDraws() {
        MatchEngine e = engine(0.5, 0.5, 0.0);   // police far away and facing away: no marking

        List<Integer> delivered = new ArrayList<>();
        List<String> carried = new ArrayList<>();
        List<Integer> ground = new ArrayList<>();
        List<Boolean> exits = new ArrayList<>();
        List<String> originsTaken = new ArrayList<>();
        long tick = 1;

        for (int cycle = 1; cycle <= 7; cycle++) {
            tick = hold(e, tick, 30);
            PlayerView v = e.viewFor(THIEF);
            delivered.add(v.deliveredCount);
            carried.add(v.thief.carriedMaterialId);
            ground.add(v.thief.groundMaterials.size());
            exits.add(v.exitsOpen);
            originsTaken.add(takenLine(v));
            if (e.result() != null) break;
            tick = release(e, tick);
        }

        // "DELIVERED x/n" on the match HUD: one per completed delivery, never skipping.
        assertEquals("deliveredCount climbs one per delivery",
                java.util.Arrays.asList(0, 1, 1, 2, 2, 3, 3), delivered);
        // The carried indicator exists only while the thief actually holds a material.
        assertEquals("carried material alternates M1..M3 then null",
                java.util.Arrays.asList("M1", null, "M2", null, "M3", null, null), carried);
        // The gold dots drawn on the map come from groundMaterials, so they must disappear on pickup.
        assertEquals("ground materials shrink as they are taken",
                java.util.Arrays.asList(2, 2, 1, 1, 0, 0, 0), ground);
        // "EXITS OPEN" flips exactly once, on the third delivery.
        assertEquals("exits open only after the third delivery",
                java.util.Arrays.asList(false, false, false, false, false, true, true), exits);
        // "ORIGINS TAKEN <ids>" - the HUD joins every everPicked material id. Sampled after each hold,
        // so the first sample already includes M1.
        assertEquals("the origins line grows one material at a time",
                java.util.Arrays.asList("M1", "M1", "M1 M2", "M1 M2", "M1 M2 M3", "M1 M2 M3", "M1 M2 M3"),
                originsTaken);

        MatchResult r = e.result();
        assertNotNull("the escape must end the match", r);
        assertEquals(ResultReason.ESCAPED, r.reason);
        assertEquals(Role.THIEF, r.winner);
        assertEquals(3, r.deliveredCount);
        assertEquals("a clean escape means no marks", 0, r.marks);

        PlayerView end = e.viewFor(THIEF);
        assertTrue("the final frame keeps the exits open", end.exitsOpen);
        assertNull("nothing is carried at the end", end.thief.carriedMaterialId);
        assertTrue("no gold dots left", end.thief.groundMaterials.isEmpty());
        for (OriginStatus s : end.originStatus) {
            assertTrue("every origin was picked at least once", s.everPicked);
        }
    }

    @Test public void theGoldDotsSitWhereTheMapPutTheMaterials() {
        MatchEngine e = engine(0.5, 0.5, 0.0);
        // Before any pickup all three gold dots are on the map, at the exact material positions.
        PlayerView first = e.viewFor(THIEF);
        assertEquals(3, first.thief.groundMaterials.size());
        for (GroundMaterial g : first.thief.groundMaterials) {
            assertEquals("the dot must sit on the material, not at a cell corner", 5.5, g.x, 1e-9);
            assertEquals(4.5, g.y, 1e-9);
        }
        // After carrying one, that dot is gone and the other two keep their coordinates.
        long tick = hold(e, 1, 30);
        PlayerView held = e.viewFor(THIEF);
        assertEquals("M1", held.thief.carriedMaterialId);
        assertEquals(2, held.thief.groundMaterials.size());
        for (GroundMaterial g : held.thief.groundMaterials) {
            assertNotEquals("the carried material must not be drawn on the ground", "M1", g.materialId);
            assertEquals(5.5, g.x, 1e-9);
            assertEquals(4.5, g.y, 1e-9);
        }
    }

    @Test public void theProtectionCountdownTheHudShows() {
        MatchEngine e = engine(5.5, 1.5, Math.PI / 2);
        long tick = 1;
        List<Integer> firstWindow = new ArrayList<>();
        // The first mark needs 24 ticks of continuous beam, so step past that before sampling.
        for (int i = 0; i < 32; i++) {
            e.step(policeFaces(tick++, 0, 0));
            PlayerView v = e.viewFor(THIEF);
            if (v.marks == 1) firstWindow.add(v.thief.protectionRemainingTicks);
            if (firstWindow.size() >= 6) break;
        }
        assertEquals("six samples were expected inside the invisible window", 6, firstWindow.size());
        assertEquals("the badge starts at the full invisible window", 45, (int) firstWindow.get(0));
        for (int i = 1; i < firstWindow.size(); i++) {
            assertEquals("the remaining seconds must tick down by one",
                    (int) firstWindow.get(i - 1) - 1, (int) firstWindow.get(i));
        }
    }

    @Test public void theProtectionBadgeWalksInvisibleThenImmuneThenNormal() {
        MatchEngine e = engine(5.5, 1.5, Math.PI / 2);
        long tick = 1;
        List<Protection> seen = new ArrayList<>();
        int invisibleLeft = -1, immuneLeft = -1;
        for (int i = 0; i < 200 && e.result() == null; i++) {
            e.step(policeFaces(tick++, 0, 0));
            Protection p = e.viewFor(THIEF).thief.protection;
            if (seen.isEmpty() || seen.get(seen.size() - 1) != p) {
                seen.add(p);
                if (p == Protection.INVISIBLE) invisibleLeft = e.viewFor(THIEF).thief.protectionRemainingTicks;
                if (p == Protection.IMMUNE) immuneLeft = e.viewFor(THIEF).thief.protectionRemainingTicks;
            }
            if (seen.size() >= 4) break;
        }
        // The HUD prints exactly this state name plus its remaining seconds.
        assertEquals("normal, invisible, immune, normal",
                java.util.Arrays.asList(Protection.NORMAL, Protection.INVISIBLE,
                        Protection.IMMUNE, Protection.NORMAL), seen);
        assertEquals("the invisible window is full when it starts", 45, invisibleLeft);
        assertEquals("the immune window is full when it starts", 30, immuneLeft);
    }

    @Test public void theEngineHidesTheThiefFromThePolice() {
        // Police three tiles away, beam on the thief: the police view must carry no thief panel.
        MatchEngine e = engine(5.5, 1.5, Math.PI / 2);
        long tick = 1;
        for (int i = 0; i < 5; i++) { e.step(policeFaces(tick++, 0, 0)); }

        PlayerView police = e.viewFor(POLICE);
        PlayerView thief = e.viewFor(THIEF);
        assertEquals(Role.POLICE, police.role);
        assertEquals(Role.THIEF, thief.role);
        assertNull("the police must never receive the thief panel", police.thief);
        assertNotNull("the thief always receives its own panel", thief.thief);
        assertNotNull("the police does see the thief while it is visible", police.opponent);
        assertEquals("the visible opponent is the thief", Role.THIEF, police.opponent.role);
        for (OriginStatus s : police.originStatus) {
            assertNotNull("the police still gets the public origin status", s.materialId);
        }

        // Right after a mark the thief turns invisible, so the police view must drop it entirely -
        // this is what makes the red dot disappear on screen.
        while (tick < 30) { e.step(policeFaces(tick++, 0, 0)); }
        assertEquals("the beam landed one mark", 1, e.viewFor(THIEF).marks);
        assertEquals(Protection.INVISIBLE, e.viewFor(THIEF).thief.protection);
        assertNull("an invisible thief is not reported to the police", e.viewFor(POLICE).opponent);
    }

    // ----------------------------------------------------------------- capture

    @Test public void thePoliceStoryTheHudDraws() {
        // Police three tiles south of the thief, already facing north: the beam covers it from tick 1.
        MatchEngine e = engine(5.5, 1.5, Math.PI / 2);

        List<Integer> marks = new ArrayList<>();
        List<Protection> protection = new ArrayList<>();
        int lastMarks = -1;
        long tick = 1;
        for (int i = 0; i < 400 && e.result() == null; i++) {
            e.step(policeFaces(tick++, 0, 0));   // stand still but keep the heading
            PlayerView v = e.viewFor(THIEF);
            if (v.marks != lastMarks) {
                lastMarks = v.marks;
                marks.add(v.marks);
                protection.add(v.thief.protection);
            }
        }

        // "MARKS x/3" on the match HUD.
        assertEquals("marks climb 0,1,2,3", java.util.Arrays.asList(0, 1, 2, 3), marks);
        // The protection badge shows INVISIBLE immediately after each of the first two marks.
        assertEquals(Protection.INVISIBLE, protection.get(1));
        assertEquals(Protection.INVISIBLE, protection.get(2));

        MatchResult r = e.result();
        assertNotNull("the third mark must end the match", r);
        assertEquals(ResultReason.CAPTURED, r.reason);
        assertEquals(Role.POLICE, r.winner);
        assertEquals(3, r.marks);
        assertEquals("a capture means no deliveries", 0, r.deliveredCount);

        PlayerView end = e.viewFor(THIEF);
        assertEquals("protection is over when the match ends", Protection.NORMAL, end.thief.protection);
        assertEquals(0, end.thief.protectionRemainingTicks);
        assertFalse("the exits never opened", end.exitsOpen);
    }

    // ------------------------------------------------------------ interaction

    @Test public void theInteractionBadgeTheHudDraws() {
        MatchEngine e = engine(0.5, 0.5, 0.0);
        long tick = 1;

        // The HUD divides progressTicks by RuleConfig.interactTicks, so it must count 1..29 and then
        // complete on the 30th held step.
        for (int i = 1; i <= 30; i++) {
            tick = hold(e, tick, 1);
            InteractionView x = e.viewFor(THIEF).thief.interaction;
            assertNotNull("there is always an interaction view", x);
            if (i < 30) {
                assertEquals("progress counts up while holding", i, x.progressTicks);
                assertNotNull("a target is offered while holding",
                        x.active != null ? x.active : x.candidate);
            }
        }
        assertEquals("M1 is in hand after 30 held steps", "M1",
                e.viewFor(THIEF).thief.carriedMaterialId);
        assertEquals("a completed hold asks for a release", "RELEASE_REQUIRED",
                e.viewFor(THIEF).thief.interaction.blockedReason);

        tick = release(e, tick);
        assertEquals("releasing clears the requirement", "NONE",
                e.viewFor(THIEF).thief.interaction.blockedReason);
    }
}
