FROM docker.io/library/eclipse-temurin:21-jdk-jammy@sha256:ce5767b7222312d42395f5bab033cd91f09e44032a2f21bdfd7b5b912dbe1e77
RUN apt-get update && apt-get install -y --no-install-recommends python3 coreutils && rm -rf /var/lib/apt/lists/*
COPY runners/phase.py /opt/codegrid/phase.py
ENV LANG=C.UTF-8
USER 65534:65534
WORKDIR /work
