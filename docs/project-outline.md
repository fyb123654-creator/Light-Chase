# Project Outline

Updated: 19 November 2026. Group 23.

## 1. Application Information

### Application Name

**Light Chase** — a two-player asymmetric pursuit game for Android.

### Application Category

Entertainment / multiplayer game.

## 2. About the Application

### Project Idea Description

Light Chase is a two-player pursuit game with a classic top-down 2D mode and a proposed real-world mode, **Field Chase**. Players connect to the same Wi-Fi network or hotspot. One plays as a police officer, searching with a virtual flashlight; the other plays as a thief, collecting and delivering materials before escaping. A Java server running on a computer manages the match. The Android clients display the information available to their own role and send player inputs to the server.

The target users are friends who want a short competitive game on their phones. The roles have different objectives, controls and information. The police must predict routes and aim the flashlight, while the thief must decide when to complete tasks, use items or unlock a shortcut. Public travel effects give the police clues without allowing permanent tracking of a hidden thief.

The basic game is already playable. In our playtesting, two Android emulators on one computer could complete matches at different screen sizes, including a police marking win, a thief escape win and a police timeout win. The classic-mode extension adds items, card-unlocked one-way passages and independent flashlight aiming, including an optional gyroscope mode.

In Field Chase, both players move within an area set by the host. Phone location determines each character's position, and the officer aims a virtual beam with the phone's compass heading. The app shows the boundary and virtual objectives, handles interactions and decides the result. Classic is the default mode, and the host can select Field Chase in the lobby.

### Main Objectives

1. Deliver a complete local-network match flow: connect, choose roles, ready up, play, view the result and return for another match.
2. Combine flashlight detection, delivery tasks, limited inventories and public passage clues to create choices for both players.
3. Learn Android rendering, multi-touch controls, location and orientation input, runtime permissions and lifecycle handling, alongside Java networking and shared game logic.
4. Keep the rules consistent on both devices through a server-authoritative simulation and role-specific views.
5. Understand and explain our implementation, the libraries we reuse, the problems encountered and the changes made with coding assistants.
6. Develop and test a real-world mode in which physical movement and phone direction become game inputs while the app remains the referee for virtual objectives and scoring.

## 3. Similar / Related Applications

| Application Name | Description | Similar Features | Limitations / Improvement Opportunities | Link |
|---|---|---|---|---|
| Robbery Bob | A single-player stealth game about collecting loot while avoiding guards and other obstacles. | Thief theme, route planning, collection and avoiding detection. | Light Chase makes the pursuer another human player and adds flashlight aiming and local-network competition. | [Google Play](https://play.google.com/store/apps/details?id=com.chillingo.robberybobfree.android.row) |
| Identity V | An asymmetric mobile game in which four survivors complete objectives and escape from one hunter. | Different roles, pursuit, task completion and escape objectives. | Our design uses one player per role and a smaller 2D setting for a short local match; the main interaction is aiming light and managing information. | [Google Play](https://play.google.com/store/apps/details?id=com.netease.idv.googleplay) |
| Burgle Bros | A heist game built around teamwork, planning, tools and avoiding guards. | Heist objectives, tools and route decisions. | We explore these ideas in real-time competition, with thief-only passages whose use reveals a travelling light orb to the opponent. | [Google Play](https://play.google.com/store/apps/details?id=net.fowers.BurgleBros) |

### Analysis of Existing Applications

These games show that pursuit, stealth, tools and escape objectives are established mechanics. Our proposed contribution is their combination in a two-player Android game: the thief can unlock an exclusive route, but each use publicly reveals travel between its endpoints; the police can move in one direction while aiming in another, with a choice of touch or phone-motion input. Limited item slots add a decision about what to carry and when to use it.

Field Chase extends the pursuit and delivery loop into a real location, using measured movement and compass-based aiming rather than joystick movement. Its design challenge is making location quality, objective proximity and sustained detection usable in a shared physical area. This is a proposed addition to our project, not a claim that location-based games are a new category.

These are design comparisons, not claims that every mechanic is new. The listed commercial games are references, not source-code dependencies. We implement our own rules, maps, protocol and client flow on top of general-purpose open-source libraries.

## 4. Proposed Features

### Feature 1 — Classic matches, win conditions and rematches

**Description**

Two players join a local network room, select roles and ready up for the countdown. After seeing the result, they can return to the lobby for a rematch. A round lasts 180 seconds. The police flashlight covers 60 degrees and has a base range of four map cells. Walls block the beam. A continuous hit of 0.8 seconds earns one mark. The officer wins with three marks or on timeout. Each of the first two marks gives the thief 1.5 seconds of true invisibility, followed by one second of marking immunity. The thief can carry one material at a time and wins by making all three deliveries and escaping through an open exit.

Holding the interaction button locks onto the nearest eligible target, with ties resolved by ID order. Moving or releasing cancels the action. Players release the button before starting another action.

**Implementation**

Use Java-WebSocket to exchange inputs and game state. The server runs the room and processes movement, collisions, illumination and interactions on fixed simulation ticks. When multiple win conditions occur in the same tick, it checks timeout first, then the third mark, then escape. Android shows the current action, progress and connection state. A rematch resets input and round state, including inventory, effects, cards, passages and travel effects.

### Feature 2 — Two item slots and role-specific effects

**Description**

Each player has two item slots. They can carry two items of the same type, and uncollected items stay on the ground when both slots are full. Task materials are carried separately. The officer can use speed and flashlight range boosts together. The light boost increases range while keeping the angle fixed. A second boost of the same type stays in its slot while the first is active.

The thief can use speed boosts and shields. A shield clears unfinished illumination progress and grants true invisibility while preserving completed marks. Reusing a shield keeps the longer of the remaining invisibility time and the configured shield duration. Any existing marking immunity ends at its original time. During true invisibility, the thief can move and use held items. Other interactions pause.

**Implementation**

The server checks the slot, item instance and action ID to prevent duplicate consumption. Effect timers use simulation ticks. Rendering and detection use the same effective beam range.

### Feature 3 — Access cards, one-way passages and travel clues

**Description**

The extension map has two one-way passages for the thief, each with its own entrance, exit and matching access card. The thief can hold one card in a separate slot and spends it to unlock its passage for the round. A held card cannot be discarded or swapped. The thief holds the interaction button at the entrance to unlock it, then releases and presses again to travel, with or without a task material. During travel the thief is protected from surface illumination and cannot move, interact or use items. Round and effect timers keep running. Normal rules resume immediately at the exit, with zero extra protection or cooldown.

Each trip alerts the police and shows both players an orb moving in a straight line from entrance to exit. The upper-right minimap shows the layout, the player's own red marker, passage states, ground items and cards, and the full trip. The main screen displays the orb when it is in view and keeps its usual camera behaviour. The orb disappears on arrival. Opponent positions follow the normal visibility rules, and each player sees only their own inventory and card slot.

**Implementation**

Define passages, cards and fixed reachable spawn points in JSON. The server validates the card match and travel conditions and filters what each role can see on the existing minimap. Use event IDs so each trip sends one alert, and clear old markers on arrival or when the opponent leaves view.

### Feature 4 — Independent flashlight aiming and gyroscope controls

**Description**

The officer moves with the left joystick and aims with the right, keeping the last aim when the right joystick is released. Gyroscope mode can be selected outside a match. It uses phone orientation to aim and replaces the right joystick with a calibration button. Calibration resets the orientation reference and aligns aim with the current movement direction when available, then the last valid movement direction, then the spawn direction. Devices without a suitable sensor use touch controls.

**Implementation**

Treat movement and aim as independent values in inputs, messages and rules. Android computes the aim angle locally, sends the result to the server and manages sensor listeners with the app lifecycle. Verify calibration on a phone.

### Feature 5 — Field Chase with location and compass controls

**Description:** The host selects Field Chase in the lobby and sets the centre of a rectangular play area. A template places separated starting zones, three material/delivery pairs and two evacuation points. Both players preview the same mode and area before readying; changing either resets readiness, and countdown locks the configuration. The host checks that all points are reachable within a suitable, permitted area. The map displays the boundary and permitted game information, without drawing rooms or physical walls.

The movement joystick is hidden. Phone location drives position at approximately one map metre per physical metre, without a virtual movement-speed multiplier. The police points the indicated edge of the phone towards the thief; compass heading controls a fixed-range, fixed-angle virtual beam independently of walking direction. Sustained valid detection gives one mark, three marks win, and timeout also gives the police a win. No physical contact, camera or real flashlight is required.

The thief walks to a material or its delivery point, remains inside the interaction zone and holds the existing button. Leaving the zone or releasing cancels unfinished progress. Three deliveries open both evacuation points; completing the evacuation interaction at either wins. Retain task carrying, dropping, protection and role-specific information. Vibration cues can signal interactions, marks and approaching the boundary.

**Provisional field-test settings:** An approximately 60 m × 40 m open, single-level area; a four-minute round; a 60-degree beam with an 18 m range; about 1.5 seconds of sustained detection per mark; and an approximately 5 m interaction radius with a one-second hold. Initial quality gates are an estimated horizontal accuracy radius no greater than 5 m and location samples no older than two seconds. These are starting values for two-phone testing, not validated guarantees or changes to classic-mode parameters.

Start with the basic task/detection loop. The first item-enabled field version can reuse the thief shield to clear unfinished detection and temporarily prevent marks, while retaining completed marks. Field mode excludes speed boosts, virtual teleportation and range-boost items. A later optional reconnaissance point could give a brief coarse location clue, rather than continuous tracking; this is outside the first playable version.

**Technical implementation approach:**

- Use Android location updates and timestamps/accuracy information. Convert both players' coordinates to a shared local east/north frame in metres. Filter stale samples and implausible jumps; do not count repeated use of one old location as fresh evidence for a mark or interaction. Quality estimates must be checked against real tests. See [Android Location](https://developer.android.com/reference/android/location/Location).
- Derive compass heading from north-referenced orientation sensors, map it to the landscape screen's indicated pointing edge, and align heading and location to the same north reference. Keep this distinct from classic mode's relative gyroscope calibration. Use shortest-angle differences so headings near 359° and 1° behave correctly. See [Android position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position).
- Send mode-specific position, heading and quality/time information through the existing network architecture. The server validates input freshness, order and area bounds, then evaluates distance, beam angle, sustained detection, task progress and wins. This branch bypasses grid collision and wall-ray tests; it does not add simulated walls to the real area.
- If compass data becomes unreliable, disable detection and clear its unfinished progress. Invalid/stale positioning, confirmed boundary exit, network loss or backgrounding pauses field-mode scoring and the match clock while preserving completed work. Restore valid data and both players inside the area before a short resume countdown. Initially cap cumulative pauses at 30 seconds; unresolved equipment/site problems end the round without a capture win. Only a reliably established one-sided boundary violation can be treated as forfeiture. These thresholds remain provisional.
- Request precise foreground location for field setup, preparation and play; classic mode does not start location collection. Stop collection when leaving the foreground. Keep the shared geographic origin and relative positions in session memory, without persistent precise-location or route logs. Permission refusal or unsuitable hardware leaves classic mode available.

**Limits and acceptance:** Physical obstacles affect how people move, but the app has no wall information and cannot automatically prevent a detection through a wall. Indoor positioning and signals near buildings may also be unreliable, so the first demonstration uses an open, same-level area, checked routes and non-contact walking play. See [GPS accuracy limitations](https://archive.gps.gov/systems/gps/performance/accuracy/). Before integration, test two phones together for static drift, common-point alignment, a measured walking route and heading. Then complete both a police win and a thief-delivery/escape win, plus stale-location, boundary-pause/resume and denied-permission cases. Field Chase remains a design/prototype task until these tests are performed.

### Current Implementation Status

The base game and five-screen client flow are implemented, and the group has played complete matches using two emulators of different sizes. For the extension, shared version-2 types, a strict message codec, map/rule loaders, fixed test configurations and protocol examples are available. The running game still uses version 1. The new rules engine, server gateway and Android controls must be connected before the extension can be described as playable. The configured extension values are for functional testing and are not final balance values.

Field Chase has a gameplay and MVP design, but location-driven movement, compass detection and real-world matches have not been implemented or field-validated. The available version-2 baseline covers the classic extension and must not be assumed to include the new mode's messages or states. Field input, shared area configuration and pause/resume behaviour require their own agreed contract and implementation.

## 5. Application Design Approach

### Data Input

Input includes the host/port/room fields, role and ready controls, multi-touch movement and aiming, held interaction, item-slot presses and the optional gyroscope/calibration controls. Field Chase additionally uses mode/area selection, foreground location with quality/time information and compass heading. It replaces joystick movement with measured position. The client also receives room updates, role-specific match snapshots, action responses and final results from the server. Classic touch controls remain available independently of field-mode permission or hardware availability.

### Data Processing

The server runs the shared simulation at 30 ticks per second and normally publishes snapshots at 15 updates per second, with visibility changes sent promptly. It processes queued inputs, collisions, item effects, interactions, illumination, travel and win conditions in a defined order. Per-role filtering removes hidden opponent coordinates from the view sent to that player. The client renders received states and interpolates movement without authoritatively deciding pickups, marks or wins.

Field Chase adds position-quality checks, shared-coordinate conversion, proximity and compass-sector calculations, and bounded pause/resume states. Its simulation must distinguish a recent trusted measurement from a repeated stale one. It uses a separate movement/detection path while reusing task, result and presentation components where their rules match.

### Data Storage

Maps and rule parameters are bundled JSON resources, loaded and validated by `game-data`. Resource hashes help both sides detect mismatched configurations. Active rooms, inventories, card ownership, deadlines and results are held in server memory for the running session; this is not persistent match history. The current app does not use Room or a cloud database.

For Field Chase, store the shared area, current quality-qualified positions, heading and pause state in session memory. Do not persist precise geographic coordinates or movement histories. Field templates and provisional parameters can use the existing structured configuration approach, with mode-specific validation.

Local control preferences and a small Room-based match-history feature can be added later, but they are outside this extension's required scope. Further learning about Android persistence remains part of our plan. The current storage work focuses on structured resources, validation and consistent session state.

### Data Output / User Interface

The Android app uses a landscape layout with the connection, lobby, countdown, match and result screens. Match output includes the map, visible characters, flashlight cone, timer, marks, delivery count, exit state, item slots, card slot and interaction progress. The classic extension adds the top-right minimap, passage states, public travel orbs and police travel notifications. Field mode shows the area boundary, virtual points, the player's position, allowed opponent information, location/heading quality and pause feedback, with no movement joystick. Controls and HUD must remain readable at different screen sizes without overlapping the minimap or exit button.

These four areas connect the project to the course: touch/location/orientation for input, rule and network processing, structured map/session data for storage, and Android screens, maps and feedback for output. Integration is demonstrated through a complete match and replay on two clients; field-mode acceptance additionally requires two real phones.

## 6. Open Source Code, Libraries, APIs, and Tools

| Resource | Purpose | Link | License |
|---|---|---|---|
| libGDX 1.14.2 | Rendering, input handling, application lifecycle and Android backend. | [Source](https://github.com/libgdx/libgdx/tree/1.14.2) | Apache-2.0 |
| Java-WebSocket 1.6.0 | WebSocket client/server transport. | [Source](https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0) | MIT |
| Gson 2.11.0 | JSON map/rule loading and protocol serialization. | [Source](https://github.com/google/gson/tree/gson-parent-2.11.0) | Apache-2.0 |
| SLF4J 2.0.16 | Network and server logging. | [Source](https://github.com/qos-ch/slf4j/tree/v_2.0.16) | MIT |
| JUnit 4.13.2 | Development-time tests for rules, data, networking and client logic. | [Source](https://github.com/junit-team/junit4/tree/r4.13.2) | EPL-1.0 |
| Gradle Wrapper 9.6.0 | Reproducible multi-module builds and server packaging. | [Source](https://github.com/gradle/gradle/tree/v9.6.0) | Apache-2.0 |

The libraries provide infrastructure; they do not supply our game rules or finished game. Our work includes the rules engine, map/rule configuration, room and match lifecycle, protocol, visibility filtering, screens, controls and extensions. No Fugitive source, artwork, maps or audio has been imported. Third-party notices and license texts are retained in the project. AI coding assistants support implementation and debugging; members remain responsible for reading, understanding, testing and explaining their changes.

Field Chase additionally uses Android's location and sensor APIs. These supply measurements; area setup, quality gating, beam geometry, task eligibility and pause decisions remain our implementation. A commercial map service is not required for the initial boundary-and-points display.

## 7. Technology Stack

### Programming Language

Java, with Java 17 source compatibility and the project's JDK 21 toolchain.

### Development Environment

Android Studio, the Android emulator, Gradle and Git/GitHub. The current project uses Android Gradle Plugin 9.4.1, compile/target SDK 37 and minimum SDK 26. A computer runs the Java server; two Android clients connect over the local network. Two emulators can support basic functional checks, while sensor checks need suitable hardware.

### Database / Storage

Bundled JSON map/rule resources and in-memory server sessions, including temporary field-area and position state for the proposed real-world mode. Persistent match history is a possible later extension; precise-location/route storage is excluded from the field design.

### APIs / External Services

Android lifecycle, location and sensor APIs, plus local-network WebSocket communication. Field Chase requires suitable positioning and compass capability, with foreground precise-location permission; classic mode does not require location. Neither mode requires a paid API, AI service, account system or cloud backend. The first field version draws its own area and points without an external map API.

### Libraries / Frameworks

libGDX, Java-WebSocket, Gson and SLF4J at runtime; JUnit for development tests. The modules are `contracts`, `game-core`, `game-data`, `server`, `client-network`, `client-core` and `app`.

## 8. Development Timeline

The original course plan runs from Week 5 to Week 15. The table retains that structure and updates the remaining work; actual progress is stated separately below rather than assuming every earlier checkpoint was completed on its original date.

| Week | Planned Work | Expected Outcome |
|---|---|---|
| 5 | Agree on the idea, related games, rules and responsibilities. | An outline and a feasible local-network game scope. |
| 6–7 | Build shared contracts, the rules engine, map loading, server rooms and initial client screens. | Components that can be tested separately and then connected. |
| 8–9 | Complete the base match flow, playtest different results and screen sizes, and define the extension features. | A playable base game and an agreed extension design. |
| 10–11 | Prepare the extension contracts, message examples and fixed configurations; begin independent aiming and item-slot work. | A common interface for rules, network and Android development. |
| 12–13 | Integrate classic aiming, slots/effects, cards/unlocking, passage travel and minimap updates. In parallel, test two-phone location/heading feasibility and build the smallest Field Chase task/detection loop. | Classic extension integration progresses; field feasibility is measured before committing to final parameters. |
| 14 | Check gyroscope controls and run field matches on phones, including position loss, boundary pause/resume and both winning paths. Check classic visibility, results and rematches, then tune values. | A tested classic extension and a field prototype whose demonstrated capabilities and remaining issues are documented. |
| 15 | Fix remaining problems, prepare the final demonstration and explain implementation and learning outcomes. | Final deliverables and a project each member can explain. |

As of the 17 November development note, the base game is running and the extension's shared interfaces and test resources are ready. Extension gameplay integration remains to be completed. The immediate order is independent aiming → item slots and shield → cards and unlocking → travel and minimap → real-phone gyroscope → full-match and rematch checks. If integration takes longer, complete this sequence before adding random spawns, additional maps or match history.

The 19 November addition makes the field feasibility test an immediate parallel task, not something that must wait for all classic items and passages. First demonstrate position and heading on two phones, then connect field tasks and sustained detection, then add shield reuse if available. Optional reconnaissance, indoor maps and extra field items do not take priority over a complete playable round.

| Member | Main Responsibilities for the Next Stage |
|---|---|
| Hui Wang (24107693) | Rules engine, map/rule data and validation; independent aiming, item effects, interactions and passage states. For Field Chase: area/point rules, proximity and beam geometry, quality-dependent scoring and pause rules. |
| Yibo Fan (24107688) | Server and client gateway; version-2 messages, action deduplication, role-specific views and match lifecycle. For Field Chase: agree mode/position messages, synchronize area and readiness, validate incoming measurements and synchronize pause/results. |
| Bin Ju (24107677) | Android/client-core screens and rendering; dual controls, item/card slots, minimap, travel effects and gyroscope lifecycle. For Field Chase: foreground permission/location, compass alignment, mode/area UI, quality feedback and phone testing. |

The members review shared contracts together and test their components independently before integration. New runtime entry points and resource versions are switched together after the modules work together, rather than enabling version 2 by changing only a version number.

## 9. Expected Challenges

**Challenge 1 — Keeping three modules consistent.** An item may appear usable on a client even when the server has rejected it, or one side may use an incompatible message/map version.

**Possible solution:** Share contracts and fixtures, validate configuration hashes, return explicit action responses, and develop the client with a scripted gateway and the network layer with a substitute engine before connecting the real components.

**Challenge 2 — Combining protection, travel and interaction rules.** A shield, mark or passage action can occur near the same tick, and timers must continue during travel.

**Possible solution:** Use explicit states, fixed tick ordering and focused scenarios for interruption, shield reuse, unlock-then-travel and arrival. Test retained marks and the original immunity deadline, not only the visual effect.

**Challenge 3 — Sharing clues without leaking hidden movement.** The public orb and minimap must help the police reason about routes without revealing the thief after arrival.

**Possible solution:** Build separate public and private view fields, remove hidden coordinates on the server, and clear old opponent markers and interpolation when visibility changes. Deduplicate travel events so snapshots do not repeat notifications.

**Challenge 4 — Making aiming usable across devices.** Screen sizes, simultaneous touches and sensor orientation may differ, and some devices lack the required sensor.

**Possible solution:** Keep movement and aiming independent, assign touch pointers consistently, provide calibration and touch fallback, and test actual phone motion and lifecycle changes in addition to emulator layouts.

**Challenge 5 — Finishing the extension within the course schedule.** Extra maps, random item refresh and persistent history would increase work before the main mechanics are integrated.

**Possible solution:** Use the fixed test map and configured values first. Finish an extended match and rematch before balance tuning or optional features. Keep weekly records and technical decisions tied to the work each member can demonstrate and explain.

**Challenge 6 — Turning noisy real-world measurements into usable gameplay.** Drift, stale positions, compass errors and actual walls can make field detection unfair. There is no wall model with which to decide physical occlusion.

**Possible solution:** Start with an open, single-level site and two-phone measurement tests. Use shared metre coordinates, a consistent north reference, fresh quality-qualified samples and explicit pause/resume feedback. Do not promise indoor or through-wall correctness, compensate with invented motion, or treat equipment failure as a successful capture. Check permission refusal and ensure classic mode does not start location collection.

## 10. Project Changes

**20 October 2026 — Extension concepts.** Ideas discussed at this stage included using keys to unlock routes, using physical movement to control characters, and giving players temporary location clues.

**16 November 2026 — Confirmed extension rules.** The group settled on two item slots, role-specific effects, a separate card slot, two thief-only one-way passages, public travel orbs, a top-right minimap and independent police aiming. Gyroscope aiming is the selected sensor feature, replacing the earlier back-tap/noise proposal. One-way travel replaces the earlier bidirectional secret-passage concept. Fixed item/card positions come first; balance and random placement come later.

**17 November 2026 — Development sequence.** The shared extension baseline was ready, while the running application still used the basic rules. The next tasks were divided across rules, networking and Android so each member could begin with common data and test substitutes.

**19 November 2026 — Outline update.** This document replaces the unused course template and brings the project description, responsibilities and remaining plan into line with the confirmed scope. It distinguishes the playable base game from extensions still being integrated.

**19 November 2026 — Field Chase added to the design.** Add an optional mode in the lobby. The host sets the play area, players move using phone location, and the officer aims with the compass. Players visit physical points to complete virtual tasks and escape, and the server decides the result. Field Chase shares task and UI components with classic mode and has its own movement, item and timing rules. The proposed rules and values will be tested with two phones.
