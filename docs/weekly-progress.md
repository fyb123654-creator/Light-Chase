# Biweekly Progress Report

## Team Information

- **Group Number:** 23
- **Project Name:** Light Chase — a two-player, asymmetric pursuit game for Android

------------------------------------

# 🔵 Week 6–7 Progress

## Overall Progress

**Tasks Completed:**

- **Gradle project.** One wrapper and one Java 17 release target for every module
  (`contracts`, `game-core`, `game-data`, `server`, `client-network`, `client-core` and the
  Android application module), one JUnit wiring for the JVM modules, a `checkJvm` aggregate
  task, and the third-party notices and licence texts copied into the build outputs.
- **Shared contract.** The 40 classes in `contracts/` (engine API, per-role `PlayerView`,
  room state, input command, gateway, protocol types, error codes, geometry) plus the
  normative interface document `docs/interface-contract.md`: envelope and sequence rules,
  `requestId` correlation, input lease, permission rules for what each role may receive,
  error-code table, timing and backpressure rules, and the change procedure.
- **Rules engine V1.** Fixed 30 Hz integer-tick match: movement with sub-stepped collision
  and wall sliding, a 60° / four-cell flashlight cone occluded by walls, three marks with
  45-tick invisibility and 30-tick immunity, the thief's rectangular view, three materials
  with hold-to-interact and target locking, drop and re-pick, three deliveries, two exits,
  escape, 180 s timeout, both players leaving in the same tick, and one idempotent result.
- **Map and rule data with strict validation.** JSON maps (walls, spawns, materials,
  deliveries, exits) and rules (tick timings, speeds, vision bounds), validated before a
  match: integer dimensions, unique IDs, points on cell centres, walls inside bounds, and a
  reachability check for every material, delivery and exit. Both files are hashed with
  SHA-256 and compared during the handshake.
- **Draft map validated.** The 24 × 18 draft had 334 walkable cells and two 3 × 3 obstacle
  islands. All materials, delivery points and exits were reachable. This layout was a
  reference for testing. The game continued to use the test room as its default map.
- **Tests and documentation.** 154 JVM tests in the modules delivered so far, a README that
  explains the game, the module layout, how to build and run it and the third-party
  licences, and the interface contract described above.

**Verification**

| Check | Result |
|---|---|
| `contracts`, `game-data`, `game-core` test suites | 154 tests (8 / 38 / 108), 0 failures |
| Draft map validation | 24 × 18, 334 walkable cells, all objective points reachable, hash pinned |
| Build configuration | One wrapper, Java 17 release target, `checkJvm` aggregate task |
| Android build and device run | Not part of the modules delivered so far |

## Individual Contributions

### Student 1

- **Name:** Hui Wang
- **GitHub Username:** Jokes-teller
- **Work Completed:** Gradle project and toolchain; the shared contract in `contracts/` and
  the interface contract `docs/interface-contract.md`; map and rule data with strict validation,
  SHA-256 hashing and reachability checks; the V1 rules engine (movement and collision,
  flashlight and occlusion, marking and protection, thief view, materials, interactions,
  exits, outcome precedence, per-role `PlayerView`); the 24 × 18 draft map; 154 JVM tests;
  the README.
- **Related Commits / Pull Requests:** `107c183` (build configuration and third-party
  notices), `35e5832` (shared contract types), `f1fb5ac` (V1 rules engine), `98d09f4` (V1
  map and rule data), `143faef` (interface contract), `d55d77d` (README)

### Student 2

- **Name:** Yibo Fan
- **GitHub Username:** fyb123654-creator
- **Work Completed:** Developed and extended the server (`server`) and client network gateway
  (`client-network`); improved connection handshakes, role selection, preparation and countdown;
  integrated the rules engine, processed player inputs and synchronised per-role views;
  implemented heartbeat and disconnect handling, result acknowledgement, return to the lobby
  and rematch; added protocol validation, input de-duplication, queue limits and rate limiting,
  with regression tests; organised protocol test fixtures, adjusted build configuration and
  verified server packaging.
- **Related Commits / Pull Requests:** `708464b` (integrate the server and client-network
  modules, add protocol test fixtures and adjust build settings)

### Student 3

- **Name:** Bin Ju
- **GitHub Username:** binju-lab
- **Work Completed:** Client architecture rebuilt as single-purpose classes with one file per
  screen; the action button state machine and its caption partition; the five screens with their
  fade and press-feedback rules; match presentation including the minimap panel, the task props
  and the flashlight cone; the offline demo driving the shared rules engine; the Android entry
  point and the local-network permission flow; 92 JVM tests in `client-core`.
- **Related Commits / Pull Requests:** `df31112` (Android client modules added)

## Challenges

- **The contract had to be written before the code.** The first drafts of the interface
  contract left responsibilities overlapping — who fills the transport-only fields of a
  `PlayerView`, and how an expired input lease interacts with `inputSeq` de-duplication. The
  fix was to write the rule into the normative section of `docs/interface-contract.md` and correct the
  affected module, rather than letting each side keep its own reading.
- **Changing the default map affects both endpoints.** The handshake compares the map and
  rule hashes. When the final map is ready, the client and server will switch together.
  The draft can be loaded separately for testing.
- **Determinism has to be designed in.** Floating-point frame accumulation drifts and a
  rules engine that reads the system clock cannot be replayed. Every duration is expressed
  in integer ticks, the engine never reads the clock, and the tests drive it tick by tick —
  which is also what makes the boundaries (mark at tick 23 vs 24, the protection windows)
  cheap to assert.
- **Rule correctness can be proved without a device, but not the presentation.** The engine
  is verified headless; whether the beam, HUD and protection states look right on a phone
  still needs the client and a real screen, and that is not covered by the tests in these
  modules.

## Plan for Weeks 8–9

1. Finalise the map and switch the client and server together. Playtest the starting
   positions, material placement and 180-second round.
2. **Run the engine against the real server and client** and verify both winning paths end
   to end (three marks; three deliveries and escape), including timeout and disconnect.
3. **Specify one extension feature beyond the base game** — for example a key that unlocks a
   second area of the map — and write the rule change into the contract before coding it.
4. **Keep the interface contract and this report current** as the other modules are added to
   the repository.

---

# 🔵 Week 8–9 Progress

## Overall Progress

**Tasks Completed**

- Ran complete LAN matches in the test room with both Android clients connected to the Java server. We used two emulators of different screen sizes on the same computer and checked the match and result screens.
- Selected access-card passages and role-specific items for the classic extension. We planned to implement them in Weeks 10–11.

**Verification**

| Check | Result |
|---|---|
| Police marking win | The officer won after the third mark. |
| Thief escape win | The thief won after delivering all three materials and escaping. |
| Timeout | The officer won when the timer ran out. |
| Player disconnect | We checked the match result after a player disconnected. |

## Individual Contributions

### Student 1

- **Name** Hui Wang
- **GitHub Username** Jokes-teller
- **Contribution** The shared rules engine, validated map data and match tests supported the full base-game checks.

### Student 2

- **Name** Yibo Fan
- **GitHub Username** fyb123654-creator
- **Contribution** Provided the LAN room and gateway flow used in the online matches, including readiness, role-specific state, disconnection and results.

### Student 3

- **Name** Bin Ju
- **GitHub Username** binju-lab
- **Contribution** Refined the connection and lobby layouts, separated the client screens and connected the offline demo to the shared engine.

## Challenges

- We checked that the controls and result screens worked at both emulator sizes.
- A disconnection ends the match through a different path from a normal win or timeout, so we checked it separately.

## Plan for Weeks 10–11

1. Work on the classic extension with two item slots, card-unlocked passages and independent aiming with optional gyroscope control.
2. Prepare the shared messages and fixed test map so the rules, network and Android modules can use the same cases.

---

# 🔵 Week 10–11 Progress

## Overall Progress

Work this period focused on the classic extension. We specified the three features below and prepared the shared v2 messages, codec, test map and rule configuration. The playable build still uses v1. The v2 rules engine, gateway and Android controls are the next integration work.

**Classic extension features**

- **Items.** Each player has two item slots, separate from carried task materials. The officer can use speed and flashlight range boosts. The thief can use speed boosts and a shield that clears unfinished exposure and provides temporary true invisibility. Marks already earned are kept. The server confirms each use.
- **Secret passages.** The thief uses matching access cards to unlock two one-way passages. Unlocking and entering take separate interactions. The minimap shows passage states. Both players can see a light orb during travel, and the officer receives an alert.
- **Gyroscope and independent aim.** The officer moves with the left joystick and aims with the right. Optional gyroscope control uses phone orientation to aim and replaces the right joystick with a calibration button. The phone sends the resulting aim angle to the server. Touch is available as a fallback.

**Map status**

The final gameplay map is still pending. The extension uses a separate test layout with fixed card, item and passage positions. This lets us run the same scenarios before tuning the map and making it the default.

## Individual Contributions

### Student 1

- **Name** Hui Wang
- **GitHub Username** Jokes-teller
- **Work Completed** Prepared the classic rules, shared v2 types and message examples, test configurations and integration notes.

### Student 2

- **Name** Yibo Fan
- **GitHub Username** fyb123654-creator
- **Integration Responsibility** Connect the server and client gateway to the new actions, replies and role-specific views.

### Student 3

- **Name** Bin Ju
- **GitHub Username** binju-lab
- **Integration Responsibility** Add the independent controls, item and card slots, travel cues and gyroscope calibration to the Android client.

## Challenges

- The engine, server and Android client need to agree on which item was used, when a passage opens and when the travel clue disappears. Shared examples and fixed test placements give us the same cases for integration.
- The gyroscope needs a physical phone to check calibration and how it feels during touch movement.

## Plan for Weeks 12–13

1. Integrate and playtest classic aiming, items, cards and passage travel. Run a full match and rematch, and check gyroscope calibration and touch fallback on a phone.
2. Finalise the gameplay map after testing the routes and placements. Switch the map on the client and server together.
3. Start the Field Chase prototype as a separate lobby option. Use two phones to test location and compass heading in an open area. Use those results to set the play boundary and prototype virtual pickups, deliveries, exits and beam detection.
