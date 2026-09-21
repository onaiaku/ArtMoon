import Theme 1.0
import QtQuick 2.15
import QtQuick.Controls 2.5
// Unversioned on purpose. This module's QML version tracks the Qt release it was built
// with (the module is registered with VERSION ${PROJECT_VERSION}, 6.8.3 in our build), so
// a versioned import pinned to a Qt 5 number would be a guess that a future Qt could
// simply refuse. The Qt 6 documentation uses the bare form for this module.
import QtQuick.VirtualKeyboard

/*
 * The on-screen keyboard, for handhelds that have no physical one.
 *
 * ⚠️ Loaded through a Loader in main.qml and never imported from anywhere else. The Qt
 * Virtual Keyboard module is bundled in the Linux build only — the Windows build
 * deliberately drops it (--no-virtualkeyboard in scripts/build-arch.bat) — and a failing
 * `import` takes the whole component down with it. Behind a Loader, a missing module
 * costs the user a keyboard, which is exactly what they had before this file existed.
 *
 * ⚠️ QT_IM_MODULE must name qtvirtualkeyboard before the QGuiApplication is constructed,
 * or InputPanel does nothing at all. main.cpp sets it, and only when no other input
 * method is configured, so a desktop user's ibus keeps working. It also sets
 * QT_VIRTUALKEYBOARD_DESKTOP_DISABLE: the module's desktop integration puts the keyboard
 * in a top-level window of its own, and this app runs fullscreen under gamescope on a
 * Steam Deck, where a second window has no window manager to place it. In-window is the
 * only form that can be relied on.
 *
 * ⚠️ Parented into Overlay.overlay rather than left beside the app's content. The dialogs
 * that need it — Custom resolution, Add host — are Popups, and popups are drawn in the
 * overlay layer. A keyboard parented to the window's content item would sit UNDER the
 * popup's dim: dimmed keys, and taps aimed at them going to the dim instead.
 */
InputPanel {
    id: inputPanel

    parent: Overlay.overlay
    anchors.left: parent ? parent.left : undefined
    anchors.right: parent ? parent.right : undefined

    // Above the popups it serves. Popups stack in the overlay by insertion order, so a
    // panel added once at startup would otherwise sit under every dialog opened later.
    z: 10000

    // Parked just below the bottom edge while there is nothing to type into. Only width
    // and y may be set on an InputPanel — its height comes from the style's aspect ratio,
    // which is what keeps the keys square at any window size.
    y: inputPanel.parent ? inputPanel.parent.height : 0

    // `active` is the input method telling us a text field has focus. The panel rises when
    // it is true and drops away when it is not — nothing has to know about the keyboard
    // except this binding.
    states: State {
        name: "visible"
        when: inputPanel.active
        PropertyChanges {
            target: inputPanel
            y: inputPanel.parent ? inputPanel.parent.height - inputPanel.height : 0
        }
    }

    transitions: Transition {
        enabled: !Theme.reduceAnimations
        NumberAnimation { property: "y"; duration: 200; easing.type: Easing.OutQuad }
    }
}
