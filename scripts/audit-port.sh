#!/usr/bin/env bash
# audit-port.sh — find ported surface with no engine behind it.
#
# Answers one question: for every preference we declare, does anything OUTSIDE the
# preferences class actually READ it?
#
# A settings row that binds a preference is not a consumer. It is a switch.
# Run this before ticking any port item. See /projects/artmoon-port-audit.md
#
# Usage:  audit-port.sh [REPO_DIR]        (default: the current directory)
#
# ⚠️ False positives are the danger. A preference can be consumed through an ACCESSOR
# METHOD whose name does not contain the property name (overlayFontSize is read as
# overlayFontPixelSize()). Always check methods and members before calling one dead.
# This script narrows the field; a human confirms the verdict.

set -uo pipefail

REPO="${1:-$PWD}"
cd "$REPO" 2>/dev/null || { echo "audit-port: cannot enter '$REPO'" >&2; exit 2; }

HDR=app/settings/streamingpreferences.h
if [ ! -f "$HDR" ]; then
    echo "audit-port: '$HDR' not found under $PWD" >&2
    echo "            pass the repo root:  audit-port.sh /path/to/repo" >&2
    exit 2
fi

SELF="app/settings/streamingpreferences.h app/settings/streamingpreferences.cpp"
# Files that only EDIT settings — reading a pref here is not consumption.
EDITORS="SettingsScreen.qml HostProfilesDialog.qml AppSettingsDialog.qml"

props=$(grep -oE 'Q_PROPERTY\([^)]*\)' "$HDR" \
        | sed -E 's/.*[[:space:]]([A-Za-z_][A-Za-z0-9_]*)[[:space:]]+(MEMBER|READ).*/\1/' \
        | grep -E '^[A-Za-z_][A-Za-z0-9_]*$' \
        | sort -u)

# ⚠️ Fail loudly. A parse that finds nothing must never report "0 problems" — that is a
# wrong answer dressed as a clean one, which is worse than an error.
if [ -z "$props" ]; then
    echo "audit-port: parsed 0 Q_PROPERTY entries from $HDR" >&2
    echo "            the parser is out of step with the file — fix it before trusting output" >&2
    exit 2
fi

dead=0; total=0
printf '%-28s %-10s %s\n' "PREFERENCE" "ENGINE" "READ BY"
printf -- '------------------------------------------------------------------------\n'

for p in $props; do
    total=$((total+1))
    cpp=""
    for f in $(grep -rl --include='*.cpp' --include='*.h' -w "$p" app/ 2>/dev/null); do
        case " $SELF " in *" $f "*) continue;; esac
        cpp="$cpp $(basename "$f")"
    done

    if [ -n "$cpp" ]; then
        printf '%-28s %-10s %s\n' "$p" "yes" "$(echo $cpp | tr ' ' ',' | cut -c1-44)"
    else
        # ⚠️ A preference can be live through an ACCESSOR whose name does not contain the
        # property name — overlayFontSize is read as overlayFontPixelSize(). Look for any
        # identifier sharing a distinctive stem with the property before calling it dead.
        stem=$(echo "$p" | sed -E 's/(Size|Value|Mode|Sync|Vsync|Rate|Count|Index)$//' | cut -c1-12)
        accessor=""
        if [ ${#stem} -ge 8 ]; then
            for f in $(grep -rl --include='*.cpp' --include='*.h' -iE "${stem}[A-Za-z]+\(" app/ 2>/dev/null); do
                case " $SELF " in *" $f "*) continue;; esac
                accessor="$accessor $(basename "$f")"
            done
        fi

        qml=""
        for f in $(grep -rl --include='*.qml' -w "$p" app/ 2>/dev/null); do
            qml="$qml $(basename "$f")"
        done
        only_editors=1
        for f in $qml; do
            case " $EDITORS " in *" $f "*) ;; *) only_editors=0;; esac
        done
        if [ -n "$accessor" ]; then
            printf '%-28s %-10s %s\n' "$p" "accessor" "via ${stem}*():$(echo $accessor|tr ' ' ',')"
        elif [ -z "$qml" ]; then
            printf '%-28s %-10s %s\n' "$p" "NONE" "⚠️  nothing reads it at all"
        elif [ "$only_editors" = "1" ]; then
            dead=$((dead+1))
            printf '%-28s %-10s %s\n' "$p" "NONE" "⚠️  only a settings row:$(echo $qml|tr ' ' ',')"
        else
            printf '%-28s %-10s %s\n' "$p" "n/a" "screen:$(echo $qml|tr ' ' ',')"
        fi
    fi
done

printf -- '------------------------------------------------------------------------\n'
echo "$total preferences declared · $dead with no engine behind them"
if [ "$dead" -gt 0 ]; then
    echo "⚠️  each of those is a switch that saves a value nothing honours."
    echo "    Confirm by hand first — an 'accessor' row above may be a false positive,"
    echo "    and a stem match can clear a genuinely dead preference."
fi
exit 0
