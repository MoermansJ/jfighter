#!/bin/bash
# Stop-hook: rebuild and restart jfighter after Claude finishes a turn,
# but only when Java sources actually changed since the last restart.
# A failed build leaves the currently running game untouched.
cd "$(dirname "$0")/.." || exit 0

STAMP=.claude/.last-restart
if [ -f "$STAMP" ] && [ -z "$(find core/src lwjgl3/src -name '*.java' -newer "$STAMP" -print -quit 2>/dev/null)" ]; then
    exit 0 # no source changes since the last restart
fi

if ! ./gradlew :lwjgl3:classes -q >/tmp/jfighter-build.log 2>&1; then
    echo '{"systemMessage":"jfighter: build FAILED - game not restarted (see /tmp/jfighter-build.log)"}'
    exit 0
fi
touch "$STAMP"

# stop the previous instance: the game JVM and any foreground `gradlew lwjgl3:run`
pkill -f 'be.jfighter.lwjgl3.Lwjgl3Launcher' 2>/dev/null
pkill -f 'GradleWrapperMain lwjgl3:run' 2>/dev/null
sleep 0.5

nohup ./gradlew lwjgl3:run >/tmp/jfighter-run.log 2>&1 &
echo '{"systemMessage":"jfighter rebuilt and restarted"}'
