#!/usr/bin/env bash
APP_HOME="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAVA_HOME="${JAVA_HOME:-/d/Android/Android Studio/jbr}"
JAVA_CMD="$JAVA_HOME/bin/java"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVA_CMD" \
    -classpath "$CLASSPATH" \
    -Dorg.gradle.appname=gradlew \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
