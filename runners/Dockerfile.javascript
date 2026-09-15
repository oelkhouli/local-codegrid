FROM docker.io/library/node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5
RUN apt-get update && apt-get install -y --no-install-recommends python3 coreutils && rm -rf /var/lib/apt/lists/*
COPY runners/phase.py /opt/codegrid/phase.py
ENV LANG=C.UTF-8
USER 65534:65534
WORKDIR /work
