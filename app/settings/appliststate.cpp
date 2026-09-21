#include "appliststate.h"

#include <QSettings>
#include <QStringList>

#include <utility>

// Flat keys under a per-app group, exactly like "appoverrides/" in appsettings.cpp: a key
// is "<prefix><host uuid>_<app id>" and the values live inside it. QSettings reads the
// slash as a group separator, so this is a group per app, named by host and app id.
#define APPLIST_PREFIX "applist/"

AppListStateManager* AppListStateManager::get()
{
    static AppListStateManager instance;
    return &instance;
}

QString AppListStateManager::hostPrefix(const QString& hostUuid)
{
    return QStringLiteral(APPLIST_PREFIX) + hostUuid + QStringLiteral("_");
}

QString AppListStateManager::keyFor(const QString& hostUuid, int appId)
{
    return hostPrefix(hostUuid) + QString::number(appId);
}

bool AppListStateManager::isFavorite(const QString& hostUuid, int appId) const
{
    QSettings settings;
    return settings.value(keyFor(hostUuid, appId) + QStringLiteral("/favorite"), false).toBool();
}

void AppListStateManager::setFavorite(const QString& hostUuid, int appId, bool favorite)
{
    QSettings settings;

    // Clearing a favourite leaves the launch timestamp alone. The two answer different
    // questions — "is this pinned?" and "when was it played?" — and unpinning a game is
    // not a claim that it was never played. It also means Unpin-then-Pin does not lose the
    // app's place in the recent shelf, which is what a user checking what the toggle does
    // would see happen.
    settings.setValue(keyFor(hostUuid, appId) + QStringLiteral("/favorite"), favorite);
}

QDateTime AppListStateManager::lastLaunched(const QString& hostUuid, int appId) const
{
    QSettings settings;
    const qint64 ms = settings.value(keyFor(hostUuid, appId) + QStringLiteral("/lastLaunched"), 0)
                              .toLongLong();

    // ⚠️ 0 means "never". QDateTime::fromMSecsSinceEpoch(0) is a valid date — 1 Jan 1970 —
    // so converting unconditionally would date every never-played app and sort the entire
    // library into the recent shelf, oldest first.
    if (ms <= 0) {
        return QDateTime();
    }

    return QDateTime::fromMSecsSinceEpoch(ms);
}

void AppListStateManager::recordLaunch(const QString& hostUuid, int appId)
{
    QSettings settings;
    settings.setValue(keyFor(hostUuid, appId) + QStringLiteral("/lastLaunched"),
                      QDateTime::currentMSecsSinceEpoch());
}

void AppListStateManager::forgetHost(const QString& hostUuid)
{
    if (hostUuid.isEmpty()) {
        return;
    }

    // Collected first and removed afterwards, and found by prefix: these are flat keys, so
    // there is no group to delete, and removing while iterating allKeys() is not something
    // to rely on. (Same shape as AppSettingsManager::forgetHost.)
    QSettings settings;
    const QString prefix = hostPrefix(hostUuid);
    QStringList doomed;
    for (const QString& key : settings.allKeys()) {
        if (key.startsWith(prefix)) {
            doomed.append(key);
        }
    }

    for (const QString& key : std::as_const(doomed)) {
        settings.remove(key);
    }
}
