#!/bin/sh

#
# 精简版 Gradle Wrapper：本运行环境的 POSIX shell 缺少 uname/xargs，
# 官方 gradlew 无法自检通过。此处保留其最终等价行为——直接以 wrapper JAR 启动 Gradle。
# 官方完整脚本原样保存于 gradlew.original。
#

# 解析脚本所在目录（APP_HOME）。
app_path=$0
app_dir=${app_path%"${app_path##*/}"}
APP_HOME=$(cd "${app_dir:-.}" >/dev/null && pwd) || exit

WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
else
    JAVACMD=java
fi

exec "$JAVACMD" \
    -Dorg.gradle.appname=gradlew \
    -Dfile.encoding=UTF-8 \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    -jar "$WRAPPER_JAR" "$@"
