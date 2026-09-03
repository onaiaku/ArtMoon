#include "storemigration.h"

#include <QDebug>
#include <QSettings>
#include <QStringList>

// The store ArtMoon used before 1.2.0 — the fork's original working title,
// kept all the way through the rebrand for settings continuity. It names the
// INI path (~/.config/FoggyBytes/StreamLight.conf) on Linux and the registry
// key Software\FoggyBytes\StreamLight on Windows.
#define LEGACY_ORG "FoggyBytes"
#define LEGACY_APP "StreamLight"

// Store-shape version, mirroring storereset.cpp. 2 was the FoggyBytes/StreamLight
// era; 3 is our own name.
#define SER_STOREVERSION "storeversion"
#define STORE_VERSION 3

namespace
{
    // Recursively copy every key and group from src into dst. Overwrites
    // same-named entries: the legacy store is strictly older, so whatever the
    // new store holds for this key was written by a pre-rename build and the
    // old value is the authoritative one.
    void copyGroup(QSettings& src, QSettings& dst)
    {
        const QStringList keys = src.allKeys();
        for (const QString& key : keys) {
            dst.setValue(key, src.value(key));
        }
        dst.sync();
    }
}

void StoreMigration::run()
{
    QSettings legacy(QSettings::defaultFormat(), QSettings::UserScope,
                     QStringLiteral(LEGACY_ORG), QStringLiteral(LEGACY_APP));
    QSettings settings;

    // Stamp check first and against the NEW store only: once stamped, this
    // launch is a later run (or the migration already completed) and the old
    // store must not be touched again. No legacy read happens below this
    // point unless we are actually migrating.
    if (settings.contains(QStringLiteral(SER_STOREVERSION))) {
        return;
    }

    const QStringList legacyKeys = legacy.allKeys();

    if (legacyKeys.isEmpty()) {
        // Genuinely new install (or a user who never saved a setting). Stamp
        // and go; there is nothing to copy and no notice owed.
        settings.setValue(QStringLiteral(SER_STOREVERSION), STORE_VERSION);
        settings.sync();
        return;
    }

    // Carry everything across, then stamp. The stamp is written only after
    // sync() succeeded on the copy, so an interrupted migration just re-runs.
    copyGroup(legacy, settings);

    // Preserve the store-version marker semantics from storereset.cpp: the
    // old store may carry a storeversion of its own (value 2); overwriting it
    // with 3 here is correct — it describes the store this key names, which
    // is now the ArtMoon one.
    settings.setValue(QStringLiteral(SER_STOREVERSION), STORE_VERSION);
    settings.sync();

    qInfo() << "Settings migrated from legacy store:" << legacy.fileName()
            << "->" << settings.fileName()
            << "(" << legacyKeys.count() << "keys )";
}
