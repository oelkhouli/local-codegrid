# Third-party software

The MIT license applies to original CodeGrid source only. Dependencies, base images, compilers, container engines and monitoring tools retain their own licenses and notices. Do not delete license files from images or represent their contents as MIT licensed.

| Component | Upstream / license family |
|---|---|
| Spring Boot, Spring Framework, Spring Security, Maven, Jackson, Micrometer | Apache-2.0 (see each distributed artifact's LICENSE/NOTICE) |
| React, Vite, TypeScript, Playwright, react-markdown | MIT / Apache-2.0 as recorded in individual npm packages |
| PostgreSQL | PostgreSQL License |
| Redis 8 | Available under AGPLv3, RSALv2 or SSPLv1; choose AGPLv3 for the open-source path |
| Prometheus | Apache-2.0 |
| Grafana OSS | AGPLv3 |
| Eclipse Temurin / OpenJDK | GPLv2 with the Classpath Exception for applicable runtime components |
| GCC | GPLv3 with applicable GCC Runtime Library Exception |
| CPython | Python Software Foundation license and included component notices |
| Node.js | MIT plus included third-party notices |
| Nginx | BSD-style license and included notices |
| Podman / Docker Engine (Moby) | Apache-2.0 and component-specific licenses |
| Debian / Alpine base images | A collection of packages with individual licenses; see installed copyright/license files |

`infra/images.lock.json` records the exact base-image manifest digests. `frontend/package-lock.json` records npm dependency versions. Maven's declared Spring Boot parent and resolved artifacts retain upstream notices; the worker shaded jar appends LICENSE/NOTICE resources rather than silently selecting a single dependency's notice.

Use Podman or open-source Docker Engine to avoid requiring Docker Desktop's proprietary distribution. No paid compiler, API, image registry plan, Grafana Cloud, Redis Cloud, or hosted runtime is required. Redis and Grafana's copyleft terms still apply when modifying or redistributing those components. Consult the exact upstream license texts for redistribution, rather than interpreting this inventory as legal advice.

Primary project references: [Redis licenses](https://redis.io/legal/licenses/), [Grafana licensing](https://grafana.com/licensing/), [OpenJDK license](https://openjdk.org/legal/gplv2+ce.html), [GCC licenses](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html), [Podman](https://github.com/containers/podman/blob/main/LICENSE).
