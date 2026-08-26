#!/usr/bin/env bash
# Compile gadget sources on THIS machine, against the built plugin jar and the Paper API.
#
#   scripts/compile-gadgets.sh                 # every gadget in brain/gadgets/
#   scripts/compile-gadgets.sh people navigate # just these
#
# Why this exists: project.md says to compile-check a gadget on the live server under a
# throwaway id, and that is still the real test - it is the same javac, with the same
# classpath the server will actually use. But on 2026-08-25 the server's gadget_define
# stopped answering entirely (every call hung, from a 160k-char gadget down to a ten-line
# one, while every other bridge command replied instantly), and four finished changes had
# no way to be checked at all. A local javac catches every compile error the server would
# have caught, needs nothing but this checkout, and takes about four seconds.
#
# It does NOT replace the server check: it cannot tell you that ctx is in scope at runtime,
# and a green local compile is not proof the gadget installed. Still verify with gadget_get
# once the server is answering again.
#
# Needs: a JDK (javac on PATH or Temurin in the usual place), plugin/target/MCAlive2.jar
# (mvn package in plugin/ if missing), and the Maven repo the plugin build already filled.

set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${TMPDIR:-/tmp}/mcalive2-gadget-compile"
SRC="$OUT/src/celestia/gadgets"
rm -rf "$OUT"
mkdir -p "$SRC" "$OUT/classes"

JAVAC="$(command -v javac || true)"
if [ -z "$JAVAC" ]; then
  JAVAC="$(ls -d /c/Program\ Files/Eclipse\ Adoptium/jdk-*/bin/javac 2>/dev/null | head -1 || true)"
fi
[ -n "$JAVAC" ] || { echo "no javac found - install a JDK or put it on PATH" >&2; exit 1; }

PLUGIN_JAR="plugin/target/MCAlive2.jar"
[ -f "$PLUGIN_JAR" ] || { echo "missing $PLUGIN_JAR - run 'mvn package' in plugin/ first" >&2; exit 1; }

# Windows javac wants Windows paths and ';' separators; cygpath is a no-op-ish elsewhere.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
SEP=";"; case "$(uname -s 2>/dev/null || echo)" in Linux|Darwin) SEP=":";; esac

M2="${HOME}/.m2/repository"
CP="$(win "$PLUGIN_JAR")"
while IFS= read -r j; do
  CP="$CP$SEP$(win "$j")"
done < <(find "$M2/io/papermc/paper/paper-api" "$M2/com/google/code/gson" "$M2/net/kyori" \
              "$M2/org/jetbrains" "$M2/org/joml" "$M2/com/google/guava" "$M2/net/md-5" \
              -name '*.jar' 2>/dev/null | grep -v -- '-sources' | grep -v -- '-javadoc')

# The gadget sources are named people.java but declare `public class People`, which javac
# refuses in a file of the wrong name. Copy each one to the name its class wants.
ids=("$@")
if [ ${#ids[@]} -eq 0 ]; then
  while IFS= read -r f; do ids+=("$(basename "$f" .java)"); done < <(ls brain/gadgets/*.java)
fi

files=()
for id in "${ids[@]}"; do
  f="brain/gadgets/$id.java"
  [ -f "$f" ] || { echo "no such gadget: $id" >&2; exit 1; }
  cls="$(grep -m1 -oE 'public (final )?class [A-Za-z0-9_]+' "$f" | awk '{print $NF}')"
  [ -n "$cls" ] || { echo "$id: no public class found" >&2; exit 1; }
  cp "$f" "$SRC/$cls.java"
  files+=("$(win "$SRC/$cls.java")")
done

echo "compiling ${#files[@]} gadget(s) against $PLUGIN_JAR"
if "$JAVAC" -nowarn -proc:none -cp "$CP" -d "$(win "$OUT/classes")" "${files[@]}"; then
  echo "OK - ${ids[*]}"
else
  echo "FAILED - ${ids[*]}" >&2
  exit 1
fi
