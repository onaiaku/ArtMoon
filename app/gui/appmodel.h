#pragma once

#include "backend/boxartmanager.h"
#include "backend/computermanager.h"
#include "streaming/session.h"

#include <QAbstractListModel>
#include <QSet>
#include <QVariant>

class AppModel : public QAbstractListModel
{
    Q_OBJECT

    enum Roles
    {
        NameRole = Qt::UserRole,
        RunningRole,
        BoxArtRole,
        HiddenRole,
        AppIdRole,
        DirectLaunchRole,
        AppCollectorGameRole,
        OverriddenRole,
        FavoriteRole,
        SectionRole,
    };

public:
    explicit AppModel(QObject *parent = nullptr);

    // Must be called before any QAbstractListModel functions
    Q_INVOKABLE void initialize(ComputerManager* computerManager, int computerIndex, bool showHiddenGames);

    Q_INVOKABLE Session* createSessionForApp(int appIndex);

    // Index of a visible app by name, or -1. Used by the remote-unlock flow to find the
    // Desktop app, which is the only thing worth launching on a host where nobody has
    // logged in yet.
    Q_INVOKABLE int indexOfAppNamed(const QString& name) const;

    Q_INVOKABLE int getDirectLaunchAppIndex();

    Q_INVOKABLE int getRunningAppId();

    Q_INVOKABLE QString getRunningAppName();

    Q_INVOKABLE QUrl getRunningAppBoxArt();

    Q_INVOKABLE void quitRunningApp();

    Q_INVOKABLE void setAppHidden(int appIndex, bool hidden);

    Q_INVOKABLE void setAppDirectLaunch(int appIndex, bool directLaunch);

    // ── Library order: the two shelves (5.5.0) ──────────────────────────────
    //
    // What has been played most recently, and what the user pinned by hand, are lifted out
    // of the list and placed above it. cgarst's report is the reason: with a real library
    // installed, reaching the game you are actually playing is a long walk down an
    // alphabetical list, and the answer to "where is the thing I was playing?" should be
    // "at the top".
    //
    // ⚠️ Each app appears ONCE. Steam repeats a title in its shelf and again in the grid
    // below, because there the shelf scrolls sideways across a separate surface. Here the
    // shelf IS the list, so a repeat would mean scrolling past the same game twice — the
    // exact tax this exists to remove.
    Q_INVOKABLE bool isAppFavorite(int appIndex) const;
    Q_INVOKABLE void setAppFavorite(int appIndex, bool favorite);

    // Moves the rows to match the shelves. Called when the per-app dialog closes, and after
    // a launch.
    //
    // ⚠️ NOT called from setAppFavorite() itself, and that is the whole reason it exists as
    // a separate step. That dialog addresses the model by INDEX — every override it reads or
    // writes goes through appIndex — so a re-shelf while it is open, with the user's finger
    // still on the toggle, would leave it editing whichever app had slid into that position.
    // The pin is recorded immediately (the row's marker updates, so the toggle is visibly
    // doing something); the list reorders once nothing is addressing it by position.
    Q_INVOKABLE void applyShelfOrder();

    // Per-game settings overrides (see AppSettingsManager). The map keys are a
    // subset of: width, height, fps, bitrate, hdr, codec, framepacing, audio.
    // A missing key means "inherit the global setting".
    Q_INVOKABLE QVariantMap getAppOverride(int appIndex);
    Q_INVOKABLE void setAppOverride(int appIndex, const QVariantMap& ov);
    Q_INVOKABLE bool appHasOverride(int appIndex);
    Q_INVOKABLE void clearAppOverride(int appIndex);

    // What a per-game row set to "inherit" will actually run at: the global settings with
    // this host's active profile applied on top — one level down, not the full cascade,
    // because the level above is the dialog the user is looking at. Values are formatted
    // for display; see inheritedValueLabels() in settings/appsettings.h.
    Q_INVOKABLE QVariantMap inheritedLabels() const;

    QVariant data(const QModelIndex &index, int role) const override;

    int rowCount(const QModelIndex &parent) const override;

    virtual QHash<int, QByteArray> roleNames() const override;

private slots:
    void handleComputerStateChanged(NvComputer* computer);

    void handleBoxArtLoaded(NvComputer* computer, NvApp app, QUrl image);

signals:
    void computerLost();

private:
    void updateAppList(QVector<NvApp> newList);

    QVector<NvApp> getVisibleApps(const QVector<NvApp>& appList);

    // Puts the shelves on top: recently played (most recent first), then pinned favourites,
    // then everything else in the host's own order. The single place the library's order is
    // decided — updateAppList() only makes the model match what this returns, so a change
    // here cannot leave the two disagreeing.
    QVector<NvApp> orderForDisplay(const QVector<NvApp>& appList);

    // Records which apps are on which shelf, for the SectionRole and the row's pin marker.
    // Rebuilt by orderForDisplay() so the roles can never describe an older arrangement.
    QSet<int> m_RecentShelfIds;
    QSet<int> m_FavoriteIds;

    bool isAppCurrentlyVisible(const NvApp& app);

    // Both were uninitialised until 04/08/2026 and read as garbage before initialize() ran.
    // Harmless while nothing looked at them first — and then the re-initialise guard in
    // initialize() did exactly that, and crashed on whatever the pointer happened to be.
    NvComputer* m_Computer = nullptr;
    BoxArtManager m_BoxArtManager;
    ComputerManager* m_ComputerManager = nullptr;
    QVector<NvApp> m_VisibleApps, m_AllApps;
    int m_CurrentGameId;
    bool m_ShowHiddenGames;
};
