#!/bin/sh
set -u

kill_executable=$1
sleep_executable=$2
shift 2

terminate_owned_group() {
  trap - USR1 TERM INT HUP
  "$kill_executable" -KILL -- "-$$" 2>/dev/null
  exit 70
}

trap terminate_owned_group USR1 TERM INT HUP

exec 3<&0
"$@" <&3 3<&- &
supervisor_pid=$!
exec 3<&-
while "$kill_executable" -0 "$supervisor_pid" 2>/dev/null; do
  "$sleep_executable" 0.02
done

wait "$supervisor_pid"
terminate_owned_group
