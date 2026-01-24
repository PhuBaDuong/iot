#!/bin/sh
# Maven Wrapper script - downloads and runs Maven
set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ -f "$MAVEN_WRAPPER_PROPERTIES" ]; then
    MAVEN_DIST_URL=$(grep "distributionUrl" "$MAVEN_WRAPPER_PROPERTIES" | cut -d'=' -f2-)
fi

MAVEN_HOME="$HOME/.m2/wrapper/dists/apache-maven-3.9.6"
MAVEN_CMD="$MAVEN_HOME/bin/mvn"

if [ ! -f "$MAVEN_CMD" ]; then
    echo "Downloading Maven..."
    mkdir -p "$MAVEN_HOME"
    TEMP_FILE=$(mktemp)
    curl -fsSL "$MAVEN_DIST_URL" -o "$TEMP_FILE"
    unzip -q -o "$TEMP_FILE" -d "$HOME/.m2/wrapper/dists"
    mv "$HOME/.m2/wrapper/dists/apache-maven-3.9.6"/* "$MAVEN_HOME/" 2>/dev/null || true
    rm -f "$TEMP_FILE"
fi

exec "$MAVEN_CMD" "$@"

