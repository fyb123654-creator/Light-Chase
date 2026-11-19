# Technical Decisions

## Team Information

Group Number: 23

Project Name: Light Chase — a two-player, asymmetric pursuit game for Android

---

# 1. Application Architecture

Architecture Used:

A **server-authoritative client–server architecture with a shared contract**, built as a
Gradle multi-module project:

| Module | Depends on | Contains |
|---|---|---|
| `contracts` | — | Engine API, per-role `PlayerView`, room state, gateway and listener, protocol types, geometry helpers |
| `game-core` | `contracts` | The deterministic rules engine and its tests (no Android, libGDX, socket or clock) |
| `game-data` | `contracts`, Gson | Map and rule JSON, the loader/validator and the SHA-256 hashes |
| `server` | `contracts`, `game-core`, `game-data`, `client-network` | Authoritative rooms, fixed-step loop, transport, packaging |
| `client-network` | `contracts`, Java-WebSocket, Gson | WebSocket gateway implementing `ClientGateway` |
| `client-core` | `contracts`, `game-data`, libGDX | Pages, input and rendering; sees only `PlayerView` |
| `app` | `client-core`, `client-network`, libGDX Android backend | Android launcher, permissions, lifecycle |

The graph has no cycles and no library module depends on the application module; `contracts`
is the only module that every other one may depend on. The server owns every rule decision;
the phone owns rendering and input and nothing else. Inside the client, screens read one
immutable `PlayerView` snapshot per frame and write one input command; there is no local copy
of the match state and no local win/lose calculation.

Reason for Selection:

- **Two devices must agree on the match.** The server owns positions, marks, carried
  materials and the result. It checks player input and sends each client the state its
  role can see.
- **The rules must be provable without a phone.** Because `game-core` has no Android,
  libGDX, socket or system clock, the whole rule set runs in ordinary JVM tests (108 tests)
  and can be driven tick by tick.
- **Three people work in parallel.** One frozen `contracts` module lets the client be built
  against a fake gateway and the network layer be tested against a fake client while the
  rules engine is finished. A single application module would have serialised the work.
- **MVVM/MVC were considered and rejected.** MVVM exists to keep UI logic testable around a
  local data layer; here there is no local business logic to observe, only an immutable
  snapshot from the network. Adding a ViewModel layer would create a second copy of state and
  a place for the client and the server to disagree.

Threading model: one logic thread per room on the server (network threads only decode,
validate and enqueue), and client callbacks are queued to the render thread so that no
`Stage`, texture or actor is touched from a socket callback.

The classic extension uses the same module split. The shared v2 message types, codec and
test configuration are ready for integration. The playable build still uses v1. The rules
engine, gateway and Android client will move to v2 together once they can run the extended
match with the same protocol and map. Field Chase is a separate room option in the design.
It can reuse the lobby, task, result and display components, with its own position input
and detection rules.

---

# 2. Programming Language and Development Environment

## Programming Language

Selection: Java, source and target release 17, built with a JDK 21 toolchain.

Reason:

The server and the rules engine are plain JVM code that must run outside Android; Java keeps
one language across the JVM modules and the Android module, and libGDX is Java-first. It also
keeps the sources close to the interfaces in `contracts` without Kotlin/Java interop work in a
three-person project. The release 17 target keeps the code usable on `minSdk 26` devices while
the build itself runs on JDK 21.

## Development Tools

Tools Used: Android Studio (editor and emulator), the Gradle wrapper, Android Gradle Plugin,
`compileSdk 37`, `minSdk 26`, libGDX, JUnit, Git and the group repository on GitHub.

Reason:

The wrapper pins one build for all three members and for a Windows and a macOS machine, so
"works on my machine" differences in Gradle or the Android plugin cannot appear. The JVM
modules build and test without the Android SDK, which means the rules and the protocol can be
verified on a laptop before an emulator is started. SDK 37 is the course target; `minSdk 26`
covers the devices we expect to use. Versions are pinned in the build files and upgrading
them is a team decision, not a per-member choice.

---

# 3. Data Storage Decisions

## Storage Technology

Technology Used: at present the game has no database. The map and rule data are JSON
resources on the classpath (`game-data/src/main/resources/maps/*.json` and `rules/v1.json`)
that are read, validated and hashed at startup; everything else — the room, the match state
and the last view on each phone — is held in memory for the duration of the session.

Reason:

For the V1 scope a match lasts 180 s and nothing has to survive a restart, so a database
would add schema, migration and lifecycle work with no user-visible benefit. What matters
instead is that the map and the rule parameters are **identical on both phones and the
server**; versioned JSON compared by SHA-256 during the handshake gives that guarantee more
directly than any local store.

The choice is deliberately open for later versions. If accounts, match history, settings or
reconnect support are added, persistent storage becomes necessary and would be introduced as
a separate layer rather than being ruled out now: a local store (Room or SQLite on the
device, DataStore for settings) for player-side data, and a server-side store only if
profiles or history must outlive a session. The current architecture keeps that option open,
because no module except a future data layer would need to change.

## Data Stored

- **Map**: dimensions, walls, spawn points, three materials, three delivery points, two exits
  (`maps/v1.json` for the formal level, `maps/test-room.json` while developing).
- **Rules**: tick counts and tolerances — 30 Hz step, 5400-tick match, 24-tick mark, 45-tick
  invisibility, 30-tick immunity, 30-tick interaction, speed and vision bounds.
- **Match state**: positions, marks, protection, materials, timer and result — in memory
  while a match is running, discarded when the room returns to the lobby.
- **Client state**: the last room state, the last `PlayerView` and the connection status —
  in memory only; the server address and room code are entered by the player.
- No user accounts, personal data, analytics, images or uploaded files at present.

The prepared classic extension JSON defines fixed item and card points, matching cards
and paths, and test values for effects and travel. As gameplay is integrated, the two
item slots, card slot, effect deadlines and open passages belong to the current round in
server memory. Keeping the placements and durations in configuration lets us adjust
them after playtesting.

The Field Chase design keeps the area centre and boundary, recent relative positions,
heading and pause state in session memory. Location and route history are outside the
storage design. A future local preferences store could remember control choices, such as
the gyro setting, across launches.

---

# 4. External APIs and Services

| API / Service | Purpose | Link |
|---|---|---|
| Project server | The phone connects to the team's Java server at `ws://<host>:8080/game` on the local network | [Project outline](project-outline.md) |
| Android sensor framework (planned) | Device-relative orientation for optional classic aiming, and north-referenced heading for Field Chase | [Android sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview) |
| Android location APIs (planned) | Foreground position samples, sample age and accuracy estimates for Field Chase | [Location reference](https://developer.android.com/reference/android/location/Location), [location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime) |

## Justification

Both modes are designed for two phones and a server on the same network. Android provides the
planned motion and location readings locally. Field Chase builds its display from an area
template and virtual points, so the project does not need a cloud backend or map service.

---

# 5. Libraries and Open Source Components

| Library / Resource | Purpose | Link | License |
|---|---|---|---|
| libGDX | Rendering, input, lifecycle and math on Android and desktop | https://libgdx.com/ | Apache-2.0 |
| Java-WebSocket | RFC 6455 WebSocket client on the phone and server on the PC | https://github.com/TooTallNate/Java-WebSocket | MIT |
| Gson | JSON encoding/decoding of the protocol and of the map/rule data | https://github.com/google/gson | Apache-2.0 |
| SLF4J | Logging facade for the server | https://www.slf4j.org/ | MIT |
| JUnit | Test framework for the JVM modules | https://junit.org/junit4/ | EPL-1.0 |
| Gradle (wrapper) | Build and dependency management | https://gradle.org/ | Apache-2.0 |

## Modifications Made

No upstream source code was copied, forked or modified. The libraries are consumed as
published binaries; the game rules, the protocol and the client are written by the team. The
outline phase studied existing pursuit games (including the open-source *Fugitive 2D*) as
references for mechanics, but no code or asset from them is part of the project.

Licence texts are shipped with the artefacts: `THIRD_PARTY_NOTICES.md` and
`third-party/licenses/` are copied into the build outputs, so the notices travel with the
build rather than living only in the repository.

---

# 6. AI Tools Usage

## Tool Used

Large-language-model coding assistants used through their chat interfaces (ChatGPT and
Claude). No AI model runs inside the application, and no user data is sent to a model at
runtime.

## Purpose

- Scaffolding the multi-module Gradle build and the Android manifest/permission setup.
- Explaining Android and libGDX APIs to the members who had not used them before (lifecycle,
  GL context, touch events, WebSocket threading).
- Drafting documentation, protocol examples and test cases.
- Reviewing diffs and pointing out missed contract cases — for example, what happens when a
  `WELCOME` arrives after the control deadline.

## How It Was Used

AI output was treated as a proposal, never as an answer: the member who committed a change
had to be able to explain every line of it and had to run the build and the tests.
Concretely:

- every change is compiled and exercised by the JVM test suites (`checkJvm`) before it is
  pushed, and the counts are recorded in the verification documents;
- rule semantics were decided by the team and then pinned by boundary tests (a mark at tick
  23 vs tick 24, the protection windows, both players leaving in the same tick), so an AI
  rewrite that changes behaviour fails the suite;
- protocol changes were reviewed by all three against the interface contract before merging —
  the AI was not allowed to invent fields or error codes unilaterally;
- documentation claims were checked against real build and test output rather than generated
  from the model's expectations.

---

# 7. Important Technical Decisions

## Decision 1

Problem: two phones must show the same match, and a player must not be able to move or score
by editing the client.

Chosen Solution: the server is authoritative. It owns positions, marks, materials, the timer
and the result. The client only sends input (move vector, interact, drop) and renders the
`PlayerView` it receives.

Reason: it removes a whole class of cheating and desynchronisation, and it makes the rules
testable without a device. The cost is network latency, which is hidden by interpolation on
the client and is acceptable on a local network.

## Decision 2

Problem: floating-point accumulation of 33 ms frames drifts, and the two devices would see
different tick counts.

Chosen Solution: an integer 30 Hz fixed step. The server accumulates elapsed time and runs
whole steps (a bounded number per wake-up, never skipping); every rule duration is expressed
in ticks — 24 ticks = 0.8 s mark, 45 ticks invisible, 5400 ticks = 180 s. Tests inject a
virtual clock.

Reason: the same input at the same tick always produces the same state, which is what makes
the rule tests deterministic and what makes a match replayable.

## Decision 3

Problem: the police must not be able to read the thief's position from the data it receives.

Chosen Solution: each role gets its own projection. `PlayerView` for the police simply has no
thief object when the thief is not visible — the field is absent, not a flag and not a hidden
object that still carries coordinates.

Reason: a flag is a promise; an absent field is a guarantee. A modified client can only render
what it was sent, and the tests assert the field is absent at the exact tick the beam loses
the thief.

## Decision 4

Problem: three people were coding against the same protocol, and early drafts left
responsibilities overlapping — who fills the transport-only fields of a view, and how an
expired input lease interacts with sequence de-duplication.

Chosen Solution: one normative contract document (`docs/interface-contract.md`) plus a `contracts`
module. Every message, error code and callback is defined once; control requests are
correlated by `requestId` and always end in a success or a failure with a contract error code;
the input lease and the sequence rules are stated explicitly; and a rule change is a reviewed
change to that document, not a private extension of the interface.

Reason: the contract is the only place where the modules meet, and leaving it informal costs
rework in more than one module. Writing the rule down once makes each module's tests
independent of the others.

## Decision 5

Problem: if the server and a phone load different map or rule files, the game is silently
unfair or fails mid-match.

Chosen Solution: the handshake carries `mapHash` and `configHash` (SHA-256 of the exact
bytes); a mismatch is a fatal error and the connection is refused before the player enters a
room.

Reason: cheap, exact and independent of file formatting. The cost is that the formal map
cannot be switched by one module — the server and the client must change in the same step,
which is why the formal map is loaded explicitly but is not yet the default.

## Decision 6

Problem: a client that stops reading, or sends a flood, could grow the server's queue without
bound and take the room down for the other player.

Chosen Solution: the contract bounds the inbound side per connection (128 unprocessed
messages shared by text, binary and heartbeat frames), has oversized text rejected before it
is enqueued, closes only the offending connection with a diagnostic reason, and leaves the
logic thread and the other connections untouched. The outbound side keeps a single pending
snapshot per connection and a bounded control queue.

Reason: memory is bounded by a constant per connection and the failure is contained to the
client that caused it. Stating the rule in the contract means the network module has a
measurable requirement instead of an implementation detail.

## Decision 7. Keep item use and passage travel in the rules engine

**Problem.** Both clients need to agree on what was consumed, which passages are open and
when a travel clue appears. A delayed request can refer to a slot that now holds a different item.

**Chosen solution.** The v2 design identifies an item use by slot, item instance and action ID.
The server checks and consumes the item and reports the result to the requesting player. The
engine manages the shield, card matching, passage unlock and timed travel as round state. A
successful trip sends a public orb and a police alert linked to the trip ID. Inventories and
ordinary opponent positions follow the existing role visibility rules.

**Reason.** Both clients can draw the same passage and effect state from server snapshots.
The action ID and item instance let the server ignore duplicate or delayed requests. The v2
message and map definitions now support these cases for integration.

## Decision 8. Give the officer separate movement and aim input

**Problem.** Using movement direction for aim prevents players from backing away while keeping
the beam on a route. Phones also vary in which sensors they provide.

**Chosen solution.** Use separate movement and aim values in the v2 contract. In the Android
design the player moves with the left joystick and uses either the right joystick or relative
phone orientation to aim. The gyro mode has a calibration button. The phone computes the angle
locally and sends it to the server. Sensor listeners follow the app lifecycle and touch remains
available when the sensor is unavailable.

**Reason.** The server can use one aiming rule regardless of how an Android client produced
the angle. Device testing can then focus on calibration, touch fallback and pausing the app.

## Decision 9. Use a separate position and compass input for Field Chase

**Problem.** Location samples can be delayed or inaccurate, and compass readings need a
consistent reference between phones. The classic grid has walls that a real play area does
not describe to the app.

**Chosen solution.** Let the host select a rectangular area and use a shared local coordinate
system in metres. The phone maps compass heading to the landscape pointing direction and
aligns heading with the area's north reference. The server will check location age, accuracy
and bounds before evaluating distance, beam angle, sustained hits and task interactions.
Invalid heading suspends beam scoring. A position or connection problem pauses the match until
both players have valid positions in bounds or the pause limit is reached.

**Reason.** The classic match can keep joystick movement and grid collision while Field Chase
uses measured positions. The first prototype uses an open, same-level site because the field
rules do not have wall data. Two-phone measurements are needed to choose the interaction radius,
beam range and sample limits.

---

# 8. Security and Privacy Considerations

- **User data.** The current classic game uses a random player ID per connection. The Field
  Chase design adds a temporary shared area and recent location measurements for the active
  session. Precise coordinates and movement histories will be kept out of persistent storage
  and diagnostic logs.
- **Authentication.** There is none: a player joins a room code on a local network. Anyone on
  that network who knows the room code can join, which is acceptable for a two-player course
  game but is the main limitation if the game were ever exposed publicly.
- **Permissions.** The current manifest declares network access and the launcher requests
  `ACCESS_LOCAL_NETWORK` in its API 37 path, with an on-screen explanation. The planned Field
  Chase flow requests precise location only during foreground setup, readiness and play.
  Leaving the foreground stops sampling. If the player declines location access, classic mode
  is still available.
- **API keys / secrets.** None. `local.properties` (the SDK path) is git-ignored; there are no
  credentials, tokens or signing keys in the repository, and the debug build uses the default
  debug signing.
- **Sensitive information.** The server sends each player a role-filtered view. The classic
  design shares the public travel orb and keeps ordinary hidden positions and private items
  out of the opponent's view. In Field Chase, the server will receive only the relative
  position, heading and quality information needed to run the round after the shared area
  has been set. The server still decides scoring and results. Diagnostics record lifecycle
  events, message types and error codes.
- **Transport.** Plain WebSocket (`ws://`, no TLS). The traffic is a two-player match on a
  trusted LAN with no credentials to protect. Playing outside a LAN would require TLS and a
  pairing step, which is listed under future improvements rather than pretended to be done.
- **Resource limits.** Bounded input queue, message-size limit, one logic thread per room and
  a per-connection budget keep a misbehaving client from exhausting server memory.

---

# 9. Future Improvements

1. Connect the classic v2 rules, gateway and Android client. Check a full match and rematch,
   plus gyro calibration, touch fallback and backgrounding on a phone.
2. Measure location drift and heading on two phones before setting Field Chase values.
   Complete the core round, then test both win paths, location loss, boundary handling and
   permission refusal.
3. Switch the formal map on both client and server together. Tune item and material placement,
   travel and effect timings as a separate playtest step. Add accessible indicators and
   larger touch targets where needed.
4. Persist local control preferences or match summaries if useful. DataStore fits simple
   settings and Room could store structured match history.
5. Explore reconnect, replay and play outside the LAN as later work. Internet play would
   require a secure transport and a separate pairing design.

---

# 10. Learning Outcomes

## Applying course topics in the project

- **Activities, layouts and resources (Lectures 03 and 08).** We used the activity lifecycle
  to decide what should happen when the Android game loses focus. The client clears active
  touches on pause and starts a fresh connection flow on resume. We split the screens so that
  the lobby and the match each handle their own controls. The game and the HUD use separate
  viewports, which helped us account for different screen ratios. We saw the result by playing
  the base game on two emulator sizes.
- **Input, processing, storage and output.** We can trace a held interaction from touch input
  to the server rule that checks a task point loaded from JSON, and then to the progress or
  result shown in the appropriate role's view. Game timing and decisions run on the server.
  The Android client renders the view and keeps rendering separate from network callbacks.
  This helped us understand where shared state belongs in a two-device application.
- **Data storage (Lecture 09).** We compared preferences, files and databases by asking which
  data must survive a restart. Maps and rules are versioned JSON files. A room and its running
  match live in memory. Validating the file contents and comparing hashes taught us how to
  check that the clients and server start with the same configuration. Settings and match
  history would have different storage needs.
- **Sensors, location and permissions (Lectures 12 and 13).** We applied the course's hardware
  availability checks and listener lifecycle examples to the aiming and field designs. In
  classic mode, calibration sets a relative direction. Field Chase needs a common north
  reference, screen-axis remapping and recent location readings with usable accuracy.
  Designing these paths taught us to handle sensor availability and permission refusal as
  part of the game flow. The existing Android launcher already checks LAN access. Calibration
  and field measurement are the next phone checks.
- **Debugging (Lecture 14).** The emulator run checked the screens and all three base-game
  results. JVM scenarios let us step through the exact tick of a mark, and protocol examples
  let us inspect a message without running Android. We learned to choose a check that
  isolates the problem, then use a device for hardware input and the full on-screen flow.

## Using open-source components

We used libGDX's Android backend, input callbacks and viewports for the client. We wrote
the room screens, HUD, flashlight and minimap with those APIs. Java-WebSocket supplies the
client and server socket implementations. Our gateway and lobby add the handshake, room
states, player messages and role-filtered snapshots on top of that transport.

Gson handles JSON conversion for maps, rules and wire messages. We added our own checks
for valid map points and expected message fields. JUnit lets us exercise the resulting
rules and codecs in plain Java, and the Gradle wrapper and third-party notices keep the
dependency versions and licences traceable.

We reviewed Fugitive as a pursuit-game reference and recorded it separately from library
dependencies in our reuse notes. Its game code and assets were not imported. That distinction
helped us explain which behaviour we built and which facilities came from libraries.
