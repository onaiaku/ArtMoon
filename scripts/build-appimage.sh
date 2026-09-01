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

# Enable LTO for official builds
export CFLAGS=-flto=auto
export CXXFLAGS=-flto=auto
export LDFLAGS=-flto=auto

echo Configuring the project
pushd $BUILD_FOLDER
# Building with Wayland support will cause linuxdeploy to include libwayland-client.so in the AppImage.
# Since we always use the host implementation of EGL, this can cause libEGL_mesa.so to fail to load due
# to missing symbols from the host's version of libwayland-client.so that aren't present in the older
# version of libwayland-client.so from our AppImage build environment. When this happens, EGL fails to
# work even in X11. To avoid this, we will disable Wayland support for the AppImage.
#
# We disable DRM support because linuxdeploy doesn't bundle the appropriate libraries for Qt EGLFS.
qmake6 $SOURCE_ROOT/moonlight-qt.pro CONFIG+=disable-wayland CONFIG+=disable-libdrm PREFIX=$DEPLOY_FOLDER/usr DEFINES+=APP_IMAGE || fail "Qmake failed!"
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

export QML_SOURCES_PATHS=$SOURCE_ROOT/app/gui
# Point linuxdeploy-plugin-qt at our aqtinstall Qt (it does not inherit PATH
# reliably); an empty QMAKE overrides its own fallback search, so only export
# when actually set.
export QMAKE="$(command -v qmake6)"

echo Creating AppImage
pushd $INSTALLER_FOLDER
VERSION=$VERSION $LINUXDEPLOY --appdir $DEPLOY_FOLDER \
  --library=/usr/local/lib/libSDL3.so.0 \
  --plugin qt --output appimage || fail "linuxdeploy failed!"
popd

echo Build successful