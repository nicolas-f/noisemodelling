#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$DIR/classes:$DIR/lib/*"
JAVA_CMD="${JAVA_HOME:-java}"
exec "$JAVA_CMD" -cp "$CLASSPATH" org.noise_planet.noisemodelling.webserver.Main "$@"

