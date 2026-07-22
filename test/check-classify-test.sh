#!/bin/bash
# Tests for ./check's exit-code classifier.
#
# The classifier suppresses one specific failure: a non-zero exit that happens
# *after* every test reported passing, with the known iOS-simulator signature.
# Everything else must still fail. Task 1.3 of stabilize-headless-test-flakes
# calls that out explicitly ("don't over-suppress"), so it gets real tests
# rather than a claim.
#
# Usage: test/check-classify-test.sh
set -u
here="$(cd "$(dirname "$0")" && pwd)"
check="$here/../check"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

pass=0
fail=0

expect() { # description, expected_code, raw_code, log_contents
  local desc=$1 want=$2 raw=$3 body=$4
  local log="$tmp/log"
  printf '%s\n' "$body" >"$log"
  local got
  got=$("$check" --classify "$log" "$raw" 2>/dev/null)
  if [ "$got" = "$want" ]; then
    pass=$((pass + 1))
    printf 'ok   %s\n' "$desc"
  else
    fail=$((fail + 1))
    printf 'FAIL %s (raw=%s want=%s got=%s)\n' "$desc" "$raw" "$want" "$got"
  fi
}

PASSED_NATIVE='[==========] 84 tests from 17 test cases ran. (1275 ms total)
[  PASSED  ] 84 tests.'
PASSED_JVM='[       109 tests successful      ]
[         0 tests failed          ]'
SIM_405='ERROR :mock-support:testIosSimulatorArm64Release An error was encountered processing the command (domain=com.apple.CoreSimulator.SimError, code=405):
ERROR :mock-support:testIosSimulatorArm64Release Unable to boot device in current state: Booted'
SIM_BOOT='ERROR :mock-support:testIosSimulatorArm64Debug Simulator boot timeout'

# ── Suppressed: the one case this exists for ────────────────────────────────
expect "clean exit passes through"                 0 0   "$PASSED_NATIVE"
expect "passed + CoreSim 405 is suppressed"        0 1   "$PASSED_NATIVE
$SIM_405"
expect "passed + boot timeout is suppressed"       0 1   "$PASSED_NATIVE
$SIM_BOOT"
expect "passed + bare exit 149 is suppressed"      0 149 "$PASSED_NATIVE"
expect "jvm summary + 405 is suppressed"           0 1   "$PASSED_JVM
$SIM_405"

# ── NOT suppressed: everything else must still fail ─────────────────────────
expect "a failing native test still fails"         1 1   "[  FAILED  ] SomeTest.thing
$SIM_405"
expect "a failing jvm test still fails"            1 1   "$PASSED_NATIVE
Failed deletingAPersonaRemovesItFromState()
$SIM_405"
expect "a non-zero jvm failure count still fails"  1 1   "[       108 tests successful      ]
[         1 tests failed          ]
$SIM_405"
expect "a compile error is never suppressed"       1 1   "e: file:///x/Foo.kt:3:1: error: unresolved reference
$SIM_405"
expect "no simulator signature is not suppressed"  1 1   "$PASSED_NATIVE"
expect "sim signature without any tests fails"     1 1   "$SIM_405"

# ── Strict mode disables the suppression entirely ───────────────────────────
# A real file, not a <(process substitution): classify() greps the log several
# times, and a /dev/fd pipe only yields its contents to the first reader.
printf '%s\n%s\n' "$PASSED_NATIVE" "$SIM_405" >"$tmp/strict.log"
got=$(KOTLIN_CHECK_STRICT=1 "$check" --classify "$tmp/strict.log" 1 2>/dev/null)
if [ "$got" = "1" ]; then
  pass=$((pass + 1)); printf 'ok   KOTLIN_CHECK_STRICT=1 surfaces the suppressed exit\n'
else
  fail=$((fail + 1)); printf 'FAIL KOTLIN_CHECK_STRICT=1 surfaces the suppressed exit (want=1 got=%s)\n' "$got"
fi

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
