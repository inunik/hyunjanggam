#!/usr/bin/env sh
SCRIPT=$(readlink -f "$0")
APP_HOME=$(dirname "$SCRIPT")
DEFAULT_JVM_OPTS=""
JAVA_EXE="java"

exec "$JAVA_EXE" $DEFAULT_JVM_OPTS -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
