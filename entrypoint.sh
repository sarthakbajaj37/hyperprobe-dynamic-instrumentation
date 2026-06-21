#!/usr/bin/env bash
set -euo pipefail

# Launch the pristine target application with JDWP enabled.
# This is a JVM launch flag only - the application's source code is untouched.
java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" \
     -jar /app/target-app.jar &

# Start the instrumentation agent in the foreground (it retries the attach until
# the target's debug port is up, so no explicit readiness wait is needed).
exec java --add-modules jdk.jdi \
     -DbreakpointSpec="${BREAKPOINT}" \
     -DjdwpAddress="${JDWP_ADDRESS}" \
     -DcaptureDir="${CAPTURE_DIR}" \
     -jar /app/agent.jar
