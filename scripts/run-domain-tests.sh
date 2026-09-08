#!/bin/sh
# Run trusted repository development tests, never submitted user programs.
set -eu

mkdir -p /work/backend/domain
cp /source/backend/pom.xml /work/backend/pom.xml
cp /source/backend/domain/pom.xml /work/backend/domain/pom.xml
cp -R /source/backend/domain/src /work/backend/domain/src

exec mvn --batch-mode --no-transfer-progress \
    -f /work/backend/pom.xml \
    -Dmaven.repo.local=/maven-cache/repository \
    "$@"
