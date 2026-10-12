package io.github.onaiaku.artmoon.apps;

import java.util.Set;

/**
 * Who is what, in a host's app list — ported from app/backend/nvapp.h.
 *
 * <p>It lives in its own class because four separate callers have to agree: the sort order, play
 * time, the unlock flow and "last played". On the desktop these strings were once spelled out in
 * two separate sort orders which had to agree or an assert fired; the desktop put them next to the
 * type they describe and each caller asks instead of repeating them. Same here.
 *
 * <p>Everything takes primitives rather than an app object on purpose: the Android app type does
 * not carry a uuid yet, and this file must be usable the moment it does without a second copy of
 * the rules appearing next to whatever type grew first.
 */
public final class AppCategories {

    private AppCategories() {}

    /**
     * The host controls Vibeshine and Vibepollo 2.0 put in the app list (desktop 5.9.0).
     *
     * <p>They are not applications: the server synthesises them per caller and per session state.
     * Remote Input streams input only over a black picture; Remote Monitor gives this client a
     * virtual display of its own that survives the stream; Terminate and the two Disconnects are
     * actions that finish with HTTP 410 and a message instead of a stream; Resume and RunningGame
     * rejoin a session already running.
     */
    public enum HostControl {
        NONE,
        RESUME,
        DISCONNECT_MONITOR,
        DISCONNECT_INPUT,
        TERMINATE,
        REMOTE_MONITOR,
        REMOTE_INPUT,
        RUNNING_GAME,
        VIRTUAL_DISPLAY,
    }

    /**
     * The host's desktop, under either of the names a server gives it (desktop 6.1.0): "Desktop",
     * from apps.json, and "Desktop (fallback)", which Apollo and Vibepollo add on their own when
     * apps.json cannot be read. Without the second, the fallback sorted as a game.
     */
    public static boolean isDesktopName(String name) {
        String n = name.trim();
        return n.equalsIgnoreCase("Desktop") || n.equalsIgnoreCase("Desktop (fallback)");
    }

    /**
     * Entries in a host's app list that are not games, by name. ⚠️ Trimmed before comparing: the
     * 2.0 servers pad some control titles with leading spaces on purpose, so they sort first in a
     * client that alphabetises.
     */
    public static boolean isSystemApp(String name) {
        if (isDesktopName(name)) {
            return true;
        }
        String n = name.trim();
        for (String known : SYSTEM_NAMES) {
            if (n.equalsIgnoreCase(known)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] SYSTEM_NAMES = {
        "Steam Big Picture", "Virtual Display",
        "Remote Input", "Remote Monitor", "Terminate", "Resume",
        "Disconnect Monitor", "Disconnect Input",
    };

    /**
     * ⚠️ Recognised by the fixed UUID or id first and by name only as a fallback. The name is the
     * least reliable of the three — it is padded with spaces for sorting, and the running-game copy
     * carries the game's own title — while the ids and UUIDs are the server's stable identity.
     */
    public static HostControl hostControlKind(int id, String uuid, String name) {
        // remote_session::synthetic_uuid(): the control's enum value as the last digit, 1..7.
        if (uuid != null) {
            String u = uuid.trim();
            if (u.length() == SYNTH_PREFIX.length() + 1 && u.regionMatches(true, 0, SYNTH_PREFIX, 0, SYNTH_PREFIX.length())) {
                switch (u.charAt(SYNTH_PREFIX.length())) {
                case '1': return HostControl.RESUME;
                case '2': return HostControl.DISCONNECT_MONITOR;
                case '3': return HostControl.DISCONNECT_INPUT;
                case '4': return HostControl.TERMINATE;
                case '5': return HostControl.REMOTE_MONITOR;
                case '6': return HostControl.REMOTE_INPUT;
                case '7': return HostControl.RUNNING_GAME;
                default: break;
                }
            }
            if (u.equalsIgnoreCase("8CB5C136-DA67-4F99-B4A1-F9CD35005CF4")) return HostControl.REMOTE_INPUT;
            if (u.equalsIgnoreCase("E16CBE1B-295D-4632-9A76-EC4180C857D3")) return HostControl.TERMINATE;
            if (u.equalsIgnoreCase("8902CB19-674A-403D-A587-41B092E900BA")) return HostControl.VIRTUAL_DISPLAY;
        }

        // remote_session.h: primary ids 2147483501-507, legacy 2147483601-606, and the "secondary
        // client" copies 511/514/515/516 that differ only in their sort padding.
        switch (id) {
        case 2147483501: case 2147483601: case 2147483511: return HostControl.RESUME;
        case 2147483502: case 2147483602:                  return HostControl.DISCONNECT_MONITOR;
        case 2147483503: case 2147483603:                  return HostControl.DISCONNECT_INPUT;
        case 2147483504: case 2147483604: case 2147483514: return HostControl.TERMINATE;
        case 2147483505: case 2147483605: case 2147483515: return HostControl.REMOTE_MONITOR;
        case 2147483506: case 2147483606: case 2147483516: return HostControl.REMOTE_INPUT;
        case 2147483507:                                   return HostControl.RUNNING_GAME;
        default: break;
        }

        // Older servers that send no UUID. "Resume" is deliberately not matched by name alone.
        String n = name.trim();
        if (n.equalsIgnoreCase("Remote Input"))       return HostControl.REMOTE_INPUT;
        if (n.equalsIgnoreCase("Remote Monitor"))     return HostControl.REMOTE_MONITOR;
        if (n.equalsIgnoreCase("Terminate"))          return HostControl.TERMINATE;
        if (n.equalsIgnoreCase("Virtual Display"))    return HostControl.VIRTUAL_DISPLAY;
        if (n.equalsIgnoreCase("Disconnect Monitor")) return HostControl.DISCONNECT_MONITOR;
        if (n.equalsIgnoreCase("Disconnect Input"))   return HostControl.DISCONNECT_INPUT;
        return HostControl.NONE;
    }

    private static final String SYNTH_PREFIX = "9a1c5a25-58fe-40e0-b9aa-7d3f0000000";

    /** The string the interface shows for a control — empty for an ordinary app. */
    public static String hostControlName(HostControl c) {
        switch (c) {
        case RESUME:            return "resume";
        case DISCONNECT_MONITOR:return "disconnectMonitor";
        case DISCONNECT_INPUT:  return "disconnectInput";
        case TERMINATE:         return "terminate";
        case REMOTE_MONITOR:    return "remoteMonitor";
        case REMOTE_INPUT:      return "remoteInput";
        case RUNNING_GAME:      return "runningGame";
        case VIRTUAL_DISPLAY:   return "virtualDisplay";
        default:                return "";
        }
    }

    /**
     * An entry that is not an application but a control the server synthesises. Virtual Display is
     * <b>not</b> one: it launches a stream like Desktop does.
     *
     * <p>These stay on APPS whatever happens (desktop 6.1.0): they are never on ALL — the
     * running-game copy would show the game twice — and the user cannot move them to GAMES.
     */
    public static boolean isHostControlEntry(int id, String uuid, String name) {
        HostControl c = hostControlKind(id, uuid, name);
        if (c == HostControl.VIRTUAL_DISPLAY) {
            return false;
        }
        if (c != HostControl.NONE) {
            return true;
        }
        // A system name that is none of the three launchers: "Resume" and the like on a server that
        // sends neither a UUID nor a known id.
        if (!isSystemApp(name)) {
            return false;
        }
        String n = name.trim();
        return !isDesktopName(n)
            && !n.equalsIgnoreCase("Steam Big Picture")
            && !n.equalsIgnoreCase("Virtual Display");
    }

    /**
     * GAMES or APPS on the picker (desktop 5.9.0): anything that is not a game by name, and every
     * host control. Everything else counts as a game — which is why a host with no configured
     * titles lands on APPS by itself.
     */
    public static boolean isAppsCategory(int id, String uuid, String name) {
        return isSystemApp(name) || hostControlKind(id, uuid, name) != HostControl.NONE;
    }

    /**
     * The ALL tab (desktop 6.1.0): everything but the host controls — every game, Desktop, Virtual
     * Display, Steam Big Picture, and whatever the user moved to APPS by hand — so a user who
     * starts from the desktop or a virtual display does not have to change tab for it.
     */
    public static boolean isAllCategory(int id, String uuid, String name) {
        return !isHostControlEntry(id, uuid, name);
    }

    /**
     * How a game's name is compared wherever the <b>name</b> is the key — play time and pins: runs
     * of whitespace collapsed and case folded, so "Hollow  Knight" and "hollow knight" are one game.
     *
     * <p>⚠️ One spelling. PlaytimeManager::normalise() delegates here, and appSortOrder() uses it
     * for pins, so the sort and the store cannot disagree about whether two names match. Locale.ROOT
     * rather than the default locale: a Turkish device would otherwise fold "I" differently and the
     * record for one game would split in two.
     */
    public static String normaliseGameName(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Where an entry sits in the list: the game you last played first, then the games you pinned
     * (desktop 6.0.0), then the desktop, the virtual display, Steam's shell, then everything else
     * alphabetically.
     *
     * <p>{@code lastPlayedName} is empty on every host where nothing has been streamed yet, and
     * {@code pinned} (normalised names) is empty until something is pinned — with both empty this is
     * exactly the order it always was. A pinned game that is also the last played one sorts as last
     * played: it is shown once, at the top.
     */
    public static int appSortOrder(String name, String lastPlayedName, Set<String> pinned) {
        if (lastPlayedName != null && !lastPlayedName.isEmpty() && name.equalsIgnoreCase(lastPlayedName)) {
            return 0;
        }
        if (pinned != null && !pinned.isEmpty() && pinned.contains(normaliseGameName(name))) {
            return 1;
        }
        String n = name.trim();
        if (isDesktopName(n))                    return 2;
        if (n.equalsIgnoreCase("Virtual Display")) return 3;
        if (n.equalsIgnoreCase("Steam Big Picture")) return 4;
        return 5;
    }
}
