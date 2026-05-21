#!/bin/sh
# Gradle start up script for UN*X
APP_HOME=$(dirname "$(readlink -f "$0" 2>/dev/null || echo "$0")")
exec "$APP_HOME/gradle/wrapper/gradlew" "$@" 2>/dev/null || java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
