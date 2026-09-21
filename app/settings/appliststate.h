#pragma once

// Per-app library state: whether an app is a favourite, and when it was last launched.
//
// ⚠️ Deliberately NOT part of AppSettingsManager. Everything that class holds is a
// streaming setting — it is cascaded (global ← host profile ← per-game), it is applied to
// a StreamingPreferences clone at launch, and it changes how a stream runs. Nothing here
// reaches a stream: it only decides what order the library is read in. A value that cannot
// affect a stream should not be able to travel with the ones that do, and a store that is
// only ever read by a list should not have to be consulted when a stream starts.
//
// Keyed "<host uuid>_<app id>" under the "applist/" prefix — the same shape as the
// per-game keys in appsettings.h, so both can be swept by host the same way.

#include <QDateTime>
#include <QString>

class AppListStateManager
{
public:
    static AppListStateManager* get();

    bool isFavorite(const QString& hostUuid, int appId) const;
    void setFavorite(const QString& hostUuid, int appId, bool favorite);

    // Invalid when this app has never been launched from here. The "Recently played"
    // shelf is the only reader, and it skips anything it cannot date.
    QDateTime lastLaunched(const QString& hostUuid, int appId) const;

    // Called when a stream is started or resumed: "recently played" means playing it now,
    // and resuming counts for the same reason launching does.
    void recordLaunch(const QString& hostUuid, int appId);

    /// Drops every record belonging to a host. Same reasoning as
    /// AppSettingsManager::forgetHost: these are keyed by host uuid, so once the host is
    /// gone they are not "kept in case it comes back", they are unreachable bytes that
    /// live for the life of the install.
    void forgetHost(const QString& hostUuid);

private:
    AppListStateManager() = default;

    static QString keyFor(const QString& hostUuid, int appId);   // "<prefix><uuid>_<id>"
    static QString hostPrefix(const QString& hostUuid);          // "<prefix><uuid>_"
};
