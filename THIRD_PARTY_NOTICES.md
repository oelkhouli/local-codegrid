# Dependencies used by the milestone 1 starter

No third-party binaries are included in the download. Build dependencies are fetched through Maven or the container engine.

| Dependency | Pinned version | License / reference |
| --- | --- | --- |
| Apache Maven | 3.9.11 in development image | Apache-2.0; [Maven reference](https://maven.apache.org/ref/3.9.11/) |
| Maven Compiler Plugin | 3.14.1 | Apache-2.0 |
| Maven Surefire Plugin | 3.5.4 | Apache-2.0 |
| JUnit Jupiter | 5.13.4 | EPL-2.0; [JUnit guide](https://docs.junit.org/5.13.4/user-guide/) |
| Eclipse Temurin/OpenJDK | JDK 21 in pinned image | OpenJDK GPLv2 with Classpath Exception; image includes additional OS components and their notices |

The development image index is pinned in `compose.dev.yaml`. A version pin is a reproducibility choice, not a claim that an old image can safely remain unpatched. Refresh and scan the build image before a public release. The finished execution platform will have a fuller license/SBOM inventory; this file describes only the starter.

Project license selection remains for the owner before publication. Nothing in this exercise requires a paid service or a proprietary runtime component.
