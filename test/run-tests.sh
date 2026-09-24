#!/usr/bin/env bash
# Tests jimagePatcher.jar against the lib/modules file of one or more JDKs:
#   extract lib/modules -> patch it -> recreate it -> extract it again and compare
#   -> boot a copy of the JDK with the recreated jimage and read the patch back.
# The same is done with the jlink plugins --compress and --strip-debug,
# and the error handling of the tool is checked.
#
# Usage: test/run-tests.sh [<jdk-home>...]
#   Without arguments the JDK in $JAVA_HOME, or else the JDK of the java on the PATH, is tested.
#   Build the jar first with ./build.sh. Each JDK needs about three times its size in
#   temporary disk space; the temporary files are deleted unless a check fails.
set -u
cd "$(dirname "$0")/.."
JAR="$PWD/jimagePatcher.jar"
if [ ! -f "$JAR" ]; then
	echo "jimagePatcher.jar not found, run ./build.sh first" >&2
	exit 2
fi
if [ $# -eq 0 ]; then
	java_home=${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')}
	set -- "$java_home"
fi

MARKER="patched by jimagePatcher"
total_failures=0

# check <description> <command...>: the command has to succeed
check() {
	local description=$1
	shift
	echo "### $description: $*" >>"$LOG"
	if "$@" >>"$LOG" 2>&1; then
		echo "  PASS  $description"
	else
		echo "  FAIL  $description"
		failures=$((failures + 1))
	fi
}

# expect_exit <exit code> <description> <command...>: the command has to fail with the exit code
expect_exit() {
	local expected=$1 description=$2
	shift 2
	echo "### $description: $*" >>"$LOG"
	"$@" >>"$LOG" 2>&1
	local actual=$?
	if [ "$actual" -eq "$expected" ]; then
		echo "  PASS  $description"
	else
		echo "  FAIL  $description (exit code $actual, expected $expected)"
		failures=$((failures + 1))
	fi
}

test_jdk() {
	local jdk=$1
	local java="$jdk/bin/java"
	local work
	work=$(mktemp -d "${TMPDIR:-/tmp}/jimagePatcher-test.XXXXXX")
	LOG="$work/test.log"
	failures=0
	local feature
	feature=$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
	echo "== $jdk ($("$java" -version 2>&1 | head -1))"

	jimage() {
		if [ -x "$jdk/bin/jimage" ]; then "$jdk/bin/jimage" "$@"; else "$java" -m jdk.jlink/jdk.tools.jimage.Main "$@"; fi
	}
	# same_content <jimage> <folder>: the jimage contains exactly the files of the folder
	same_content() {
		rm -rf "$work/reextracted" && jimage extract --dir="$work/reextracted" "$1" && diff -r "$2" "$work/reextracted"
	}
	smaller() {
		[ "$(wc -c <"$1")" -lt "$(wc -c <"$2")" ]
	}
	# boot <jimage>: installs the jimage in the JDK copy, regenerates CDS and reads the patched resource
	boot() {
		cp "$1" "$work/jdk/lib/modules" &&
			"$work/jdk/bin/java" -Xshare:dump &&
			[ "$("$work/jdk/bin/java" -Xshare:on "$work/PatchCheck.java")" = "$MARKER" ]
	}

	check "extract lib/modules" jimage extract --dir="$work/extracted" "$jdk/lib/modules"
	# the patch: a new resource in java.base, and a changed resource in java.base
	echo "$MARKER" >"$work/extracted/java.base/jimagepatcher-test.txt"
	local properties
	properties=$(find "$work/extracted/java.base" -name '*.properties' | sort | head -1)
	echo "# $MARKER" >>"$properties"
	cat >"$work/PatchCheck.java" <<-'EOF'
		public class PatchCheck {
			public static void main(String[] args) throws Exception {
				try (java.io.InputStream in = Object.class.getResourceAsStream("/jimagepatcher-test.txt")) {
					System.out.print(new String(in.readAllBytes(), "UTF-8").trim());
				}
			}
		}
	EOF
	cp -a "$jdk" "$work/jdk"

	check "recreate the jimage (java -jar)" "$java" -jar "$JAR" "$work/extracted" "$work/modules"
	check "the jimage contains the patched files" same_content "$work/modules" "$work/extracted"
	check "the JDK boots with the jimage and sees the patch" boot "$work/modules"
	check "recreate the jimage (module path)" "$java" --add-opens jdk.jlink/jdk.tools.jlink.internal=jimagePatcher \
		--module-path "$JAR" -m jimagePatcher/jimagePatcher.Run "$work/extracted" "$work/modules-module-path"
	check "both launch modes create the same file" cmp "$work/modules" "$work/modules-module-path"

	local compress=2
	if [ "$feature" -ge 21 ]; then compress=zip-6; fi
	check "recreate with --compress=$compress" "$java" -jar "$JAR" --compress=$compress "$work/extracted" "$work/modules-compressed"
	check "the compressed jimage is smaller" smaller "$work/modules-compressed" "$work/modules"
	check "the compressed jimage contains the patched files" same_content "$work/modules-compressed" "$work/extracted"
	check "the JDK boots with the compressed jimage" boot "$work/modules-compressed"
	check "recreate with --strip-debug" "$java" -jar "$JAR" --strip-debug "$work/extracted" "$work/modules-stripped"
	check "the stripped jimage is smaller" smaller "$work/modules-stripped" "$work/modules"
	check "the JDK boots with the stripped jimage" boot "$work/modules-stripped"

	expect_exit 2 "no arguments: usage error" "$java" -jar "$JAR"
	expect_exit 2 "unknown plugin: usage error" "$java" -jar "$JAR" --no-such-plugin "$work/extracted" "$work/failed"
	expect_exit 2 "invalid plugin argument: usage error" "$java" -jar "$JAR" --compress=invalid "$work/extracted" "$work/failed"
	expect_exit 1 "missing input folder: error" "$java" -jar "$JAR" "$work/missing" "$work/failed"
	expect_exit 1 "module path without --add-opens: error" "$java" --module-path "$JAR" -m jimagePatcher "$work/extracted" "$work/failed"
	check "failed runs leave no output file" test ! -e "$work/failed" -a ! -e "$work/failed.tmp"

	if [ "$failures" -eq 0 ]; then
		rm -rf "$work"
	else
		echo "  $failures check(s) failed, see $LOG"
		total_failures=$((total_failures + failures))
	fi
}

for jdk in "$@"; do
	test_jdk "$jdk"
done
if [ "$total_failures" -ne 0 ]; then
	echo "FAILED: $total_failures check(s)"
	exit 1
fi
echo "All checks passed"
