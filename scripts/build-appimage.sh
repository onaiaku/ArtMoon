BUILD_CONFIG="release"

fail()
{
	echo "$1" 1>&2
	exit 1
}

BUILD_ROOT=$PWD/build
SOURCE_ROOT=$PWD
BUILD_FOLDER=$BUILD_ROOT/build-$BUILD_CONFIG
DEPLOY_FOLDER=$BUILD_ROOT/deploy-$BUILD_CONFIG
INSTALLER_FOLDER=$BUILD_ROOT/installer-$BUILD_CONFIG

LINUXDEPLOY=linuxdeploy-$(uname -m).AppImage

if [ -n "$CI_VERSION" ]; then
  VERSION=$CI_VERSION
else
  VERSION=`cat $SOURCE_ROOT/app/version.txt`
fi

command -v qmake6 >/dev/null 2>&1 || fail "Unable to find 'qmake6' in your PATH!"
command -v $LINUXDEPLOY >/dev/null 2>&1 || fail "Unable to find '$LINUXDEPLOY' in your PATH!"

echo Cleaning output directories
rm -rf $BUILD_FOLDER
rm -rf $DEPLOY_FOLDER
rm -rf $INSTALLER_FOLDER
mkdir $BUILD_ROOT
mkdir $BUILD_FOLDER
mkdir $DEPLOY_FOLDER
mkdir $INSTALLER_FOLDER

# Wayland support is ENABLED for the AppImage (unlike upstream Moonlight).
# Upstream disables it to avoid bundling an older libwayland-client.so that
# breaks host EGL symbol resolution. We keep Wayland but force linuxdeploy to
# EXCLUDE libwayland-client/cursor/egl from the bundle, so the Qt Wayland
# platform plugin links against the host libwayland at runtime - the same
# libraries KWin/GNOME already provide. Native Wayland, no XWayland fallback,
# no NVIDIA black window.
WAYLAND_EXCLUDES="--exclude-library libwayland-client.so.0 --exclude-library libwayland-cursor.so.0 --exclude-library libwayland-egl.so.1"

# Enable LTO for official builds
export CFLAGS=-flto=auto
export CXXFLAGS=-flto=auto
export LDFLAGS=-flto=auto

echo Configuring the project
pushd $BUILD_FOLDER
# NOTE: Wayland support is now ENABLED (this differs from upstream Moonlight,
# which disables it here). The historical concern was libwayland-client.so
# version skew breaking host EGL symbol resolution; we handle that by
# excluding the bundled libwayland libraries entirely (see WAYLAND_EXCLUDES),
# so the app always uses the host libwayland. See the block above.
#
# We disable DRM support because linuxdeploy doesn't bundle the appropriate libraries for Qt EGLFS.
qmake6 $SOURCE_ROOT/moonlight-qt.pro CONFIG+=disable-libdrm PREFIX=$DEPLOY_FOLDER/usr DEFINES+=APP_IMAGE || fail "Qmake failed!"
popd

echo Compiling Moonlight in $BUILD_CONFIG configuration
pushd $BUILD_FOLDER
make -j$(nproc) $(echo "$BUILD_CONFIG" | tr '[:upper:]' '[:lower:]') || fail "Make failed!"
popd

echo Deploying to staging directory
pushd $BUILD_FOLDER
make install || fail "Make install failed!"
popd

# Pre-seed the QML modules the app imports but linuxdeploy-plugin-qt's bundle
# step has historically missed when the host's Qt install lacks them (the 1.0.0
# AppImage shipped without QtQuick/Shapes and bounced on launch on every
# machine). Copying explicitly from the Qt we build with makes the bundle
# deterministic. Their library dependencies (libQt6QuickShapes etc.) are picked
# up by linuxdeploy's dependency walk.
QT_QML_DIR=$(qmake6 -query QT_INSTALL_QML) || fail "qmake -query failed!"
for MODULE in QtQuick/Shapes QtQuick/Effects QtQuick/Dialogs QtQuick/Window QtQuick/Layouts QtQuick/Templates QtQuick/Controls QtQuickControls2; do
    if [ -d "$QT_QML_DIR/$MODULE" ]; then
        echo "Bundling QML module: $MODULE"
        mkdir -p $DEPLOY_FOLDER/usr/qml/$(dirname $MODULE)
        cp -r "$QT_QML_DIR/$MODULE" $DEPLOY_FOLDER/usr/qml/$MODULE/ || fail "Failed to bundle QML module $MODULE"
    else
        echo "QML module not present in Qt install (skipping): $MODULE"
    fi
done

# Pre-seed the Qt Wayland platform plugin the same way we pre-seed QML
# modules: linuxdeploy-plugin-qt does not reliably bundle it, and without it
# Wayland-desktop users get XWayland fallback (black window on NVIDIA).
QT_PLUGIN_DIR=$(qmake6 -query QT_INSTALL_PLUGINS) || fail "qmake -query failed!"
# NB: glob against $QT_PLUGIN_DIR itself (the old form `for PLUG in
# platforms/libqwayland*.so` expanded the glob against the script's CWD, never
# matched, stayed a literal string, and bundled nothing — CI rounds 2026-09-02).
for PLUG in "$QT_PLUGIN_DIR"/platforms/libqwayland*.so; do
    [ -e "$PLUG" ] || continue
    echo "Bundling Qt platform plugin: $(basename "$PLUG")"
    mkdir -p $DEPLOY_FOLDER/usr/plugins/platforms
    cp "$PLUG" $DEPLOY_FOLDER/usr/plugins/platforms/ || fail "Failed to bundle $PLUG"
done
WAYLAND_PLUGS=$(ls $DEPLOY_FOLDER/usr/plugins/platforms/libqwayland* 2>/dev/null | wc -l)
[ "$WAYLAND_PLUGS" -gt 0 ] || fail "No Qt Wayland platform plugins found to bundle - check aqt Qt build"

# Bundle Qt's own libQt6WaylandClient too. The platform plugins link against
# it, but linuxdeploy does not see the dependency (it resolves at dlopen time),
# so without this the runtime loader grabs the HOST's libQt6WaylandClient -
# which may be built against a different Qt (e.g. system Qt 6.11) and demands
# Qt_6.11 symbols our bundled Qt 6.8.3 Core does not export. Result:
# "Could not load the Qt platform plugin wayland even though it was found"
# -> XWayland fallback -> black window. Ship the matching Qt 6.8.3 copy.
QT_LIB_DIR=$(qmake6 -query QT_INSTALL_LIBS) || fail "qmake -query failed!"
# Copy the real versioned file with -L; the unversioned symlinks in the aqt
# Qt tree dangle/are not present, and cp of a symlink failed round 11.
for WLIB in "$QT_LIB_DIR"/libQt6WaylandClient.so.*.*.*; do
    [ -e "$WLIB" ] || continue
    echo "Bundling Qt Wayland client library: $(basename "$WLIB")"
    cp -L "$WLIB" $DEPLOY_FOLDER/usr/lib/ || fail "Failed to bundle $WLIB"
done
# The plugin's DT_NEEDED entry is libQt6WaylandClient.so.6, so make sure the
# runtime name resolves inside the bundle.
if [ ! -e "$DEPLOY_FOLDER/usr/lib/libQt6WaylandClient.so.6" ]; then
    REAL=$(ls $DEPLOY_FOLDER/usr/lib/libQt6WaylandClient.so.*.*.* 2>/dev/null | head -1)
    [ -n "$REAL" ] || fail "No versioned libQt6WaylandClient bundled"
    ln -s "$(basename "$REAL")" "$DEPLOY_FOLDER/usr/lib/libQt6WaylandClient.so.6" || fail "symlink failed"
fi
[ -e "$DEPLOY_FOLDER/usr/lib/libQt6WaylandClient.so.6" ] || fail "libQt6WaylandClient.so.6 missing after bundling"
export QML_SOURCES_PATHS=$SOURCE_ROOT/app/gui
# Point linuxdeploy-plugin-qt at our aqtinstall Qt (it does not inherit PATH
# reliably); an empty QMAKE overrides its own fallback search, so only export
# when actually set.
export QMAKE="$(command -v qmake6)"

echo Creating AppImage
pushd $INSTALLER_FOLDER
VERSION=$VERSION $LINUXDEPLOY --appdir $DEPLOY_FOLDER \
  --library=/usr/local/lib/libSDL3.so.0 \
  $WAYLAND_EXCLUDES \
  --plugin qt --output appimage || fail "linuxdeploy failed!"
popd

echo Build successful