FROM docker.io/library/gcc:14-bookworm@sha256:5e927c284bf55a7dc796262e311a0703344f62f41f5621eb56843111b1d37e15
RUN apt-get update && apt-get install -y --no-install-recommends python3 coreutils && rm -rf /var/lib/apt/lists/*
COPY runners/phase.py /opt/codegrid/phase.py
ENV LANG=C.UTF-8
USER 65534:65534
WORKDIR /work
