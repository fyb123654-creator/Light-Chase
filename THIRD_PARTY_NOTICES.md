# Third-party notices

This scaffold uses the following open-source libraries. Versions are pinned in Gradle build files. Original game code has no project-wide license selected yet; the licenses below apply to their respective third-party works.

| Component | Version | Use | License / upstream |
|---|---|---|---|
| libGDX (gdx, gdx-backend-android, platform natives) | 1.14.2 | Rendering, lifecycle, input, Android backend | Apache-2.0; https://github.com/libgdx/libgdx/tree/1.14.2 |
| Java-WebSocket | 1.6.0 | Client/server WebSocket transport | MIT; https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0 |
| Gson | 2.11.0 | JSON resources and wire envelopes | Apache-2.0; https://github.com/google/gson/tree/gson-parent-2.11.0 |
| SLF4J API / Simple | 2.0.16 | Transport logging; Simple on server only | MIT; https://github.com/qos-ch/slf4j/tree/v_2.0.16 |
| Error Prone annotations | 2.27.0 | Transitive Gson annotations | Apache-2.0; https://github.com/google/error-prone/tree/v2.27.0 |
| JUnit | 4.13.2 | Development tests only | EPL-1.0; https://github.com/junit-team/junit4/tree/r4.13.2 |
| Hamcrest Core | 1.3 | Transitive development test dependency | BSD-3-Clause; https://github.com/hamcrest/JavaHamcrest/tree/hamcrest-java-1.3 |

License texts are stored in third-party/licenses. The server distribution includes this notice and those texts. Android packages this notice and third-party/licenses as assets/third-party. Development-only JUnit and Hamcrest are not included in application runtime packages.

Gradle Wrapper is generated using Gradle 9.6.0 (Apache-2.0), https://github.com/gradle/gradle/tree/v9.6.0. Its generated scripts retain their copyright headers. Android Gradle Plugin 9.4.1 is a build tool, not gameplay code.

No Fugitive source, artwork, maps or audio has been imported. Our test map and primitive shape UI are newly authored scaffolding. If code or assets from another project are adopted later, record the exact commit, original file, modifications, attribution and license in docs/development/07_开源复用登记表.md before distribution.

Before a public release, audit the resolved dependency graph and packaged native libraries for any additional component-specific notices; this development inventory does not certify a final release.
