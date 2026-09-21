#include "appmodel.h"

#include "settings/appliststate.h"
#include "settings/appsettings.h"
#include "settings/streamingpreferences.h"

#include <QTimer>

#include <algorithm>

// How many titles the "Recently played" shelf holds. Five is short enough that the shelf is
// still a shortcut rather than a second library: the whole point is to save a walk down the
// list, and a shelf you have to scroll past has spent the walk it was meant to save. It is
// a ceiling, not a target — a host with two played games shows two.
static const int kRecentShelfMax = 5;

AppModel::AppModel(QObject *parent)
    : QAbstractListModel(parent)
{
    connect(&m_BoxArtManager, &BoxArtManager::boxArtLoadComplete,
            this, &AppModel::handleBoxArtLoaded);
}

void AppModel::initialize(ComputerManager* computerManager, int computerIndex, bool showHiddenGames)
{
    // Re-initialising is legitimate (the unlock flow reuses one model across wakes) and used
    // to stack a second connection on top of the first, so every state change was handled
    // twice. AppsScreen never hit it because it builds a fresh model each time.
    if (m_ComputerManager != nullptr) {
        disconnect(m_ComputerManager, &ComputerManager::computerStateChanged,
                   this, &AppModel::handleComputerStateChanged);
    }

    m_ComputerManager = computerManager;
    connect(m_ComputerManager, &ComputerManager::computerStateChanged,
            this, &AppModel::handleComputerStateChanged);

    Q_ASSERT(computerIndex < m_ComputerManager->getComputers().count());
    m_Computer = m_ComputerManager->getComputers().at(computerIndex);
    m_CurrentGameId = m_Computer->currentGameId;
    m_ShowHiddenGames = showHiddenGames;

    updateAppList(m_Computer->appList);
}

int AppModel::getRunningAppId()
{
    return m_CurrentGameId;
}

QString AppModel::getRunningAppName()
{
    if (m_CurrentGameId != 0) {
        for (int i = 0; i < m_AllApps.count(); i++) {
            if (m_AllApps[i].id == m_CurrentGameId) {
                return m_AllApps[i].name;
            }
        }
    }

    return nullptr;
}

// The artwork for the app currently running on the host, so the screens that close it can
// stand on the same picture the rest of the app uses. Looks in the full list rather than
// the visible one for the same reason getRunningAppName() does: a hidden game is still the
// one that is running.
QUrl AppModel::getRunningAppBoxArt()
{
    if (m_CurrentGameId != 0) {
        for (int i = 0; i < m_AllApps.count(); i++) {
            if (m_AllApps[i].id == m_CurrentGameId) {
                return m_BoxArtManager.loadBoxArt(m_Computer, m_AllApps[i]);
            }
        }
    }

    return QUrl();
}

int AppModel::indexOfAppNamed(const QString& name) const
{
    for (int i = 0; i < m_VisibleApps.count(); i++) {
        if (m_VisibleApps.at(i).name.compare(name, Qt::CaseInsensitive) == 0) {
            return i;
        }
    }
    return -1;
}

Session* AppModel::createSessionForApp(int appIndex)
{
    Q_ASSERT(appIndex < m_VisibleApps.count());
    NvApp app = m_VisibleApps.at(appIndex);

    // Note the launch, which is what the "Recently played" shelf reads. Recorded here rather
    // than when the stream is confirmed up: picking a game is the event — a host that takes
    // six seconds to answer, or refuses, has still been asked for that game now.
    AppListStateManager::get()->recordLaunch(m_Computer->uuid, app.id);

    // ⚠️ Deferred by one event-loop turn, not done inline. This call comes from the focused
    // row's own handler, so re-shelving inside it would move the rows out from under the
    // delegate that is still executing — the same class of re-entrancy that turned the
    // 12/08 host-offline case into a hang. The order is only ever read off a later frame.
    QTimer::singleShot(0, this, [this]() { updateAppList(m_AllApps); });

    // Apply this game's per-app overrides on top of a clone of the global
    // preferences (the global object is never mutated). The clone is owned by
    // the Session.
    StreamingPreferences* prefs = AppSettingsManager::get()->buildPrefs(
        StreamingPreferences::get(), m_Computer->uuid, app.id);
    Session* session = new Session(m_Computer, app, prefs);
    prefs->setParent(session);
    return session;
}

bool AppModel::isAppFavorite(int appIndex) const
{
    if (appIndex < 0 || appIndex >= m_VisibleApps.count()) {
        return false;
    }

    return AppListStateManager::get()->isFavorite(m_Computer->uuid, m_VisibleApps.at(appIndex).id);
}

void AppModel::setAppFavorite(int appIndex, bool favorite)
{
    if (appIndex < 0 || appIndex >= m_VisibleApps.count()) {
        return;
    }

    const int appId = m_VisibleApps.at(appIndex).id;
    AppListStateManager::get()->setFavorite(m_Computer->uuid, appId, favorite);

    // The row itself is told now, so the pin marker and the section it reports are current
    // the moment the toggle moves. What is NOT done here is the reorder — see
    // applyShelfOrder(): the dialog that calls this addresses the model by index, and moving
    // rows underneath it would point it at a different game.
    m_FavoriteIds.clear();
    for (const NvApp& app : std::as_const(m_VisibleApps)) {
        if (AppListStateManager::get()->isFavorite(m_Computer->uuid, app.id)) {
            m_FavoriteIds.insert(app.id);
        }
    }

    emit dataChanged(createIndex(appIndex, 0), createIndex(appIndex, 0),
                     QVector<int>() << FavoriteRole << SectionRole);
}

void AppModel::applyShelfOrder()
{
    updateAppList(m_AllApps);
}

QVariantMap AppModel::getAppOverride(int appIndex)
{
    QVariantMap m;
    if (appIndex < 0 || appIndex >= m_VisibleApps.count()) {
        return m;
    }
    // ⚠️ Through the shared helpers, never a copy of them. These two functions used to
    // convert by hand and had fallen a release behind: "hue" was missing from both, so the
    // dialog's Philips Hue row wrote a key nobody read and always came back reading Global.
    // A per-game override is stored, cascaded and displayed by three different call sites,
    // and a fourth private idea of which keys exist is how one of them quietly stops working.
    return appOverrideToMap(
        AppSettingsManager::get()->getOverride(m_Computer->uuid, m_VisibleApps.at(appIndex).id));
}

QVariantMap AppModel::inheritedLabels() const
{
    if (m_Computer == nullptr) {
        return QVariantMap();
    }

    // Same two steps buildPrefs() takes, minus the third: global, then the host's active
    // profile. Reusing buildPrefs() itself would fold the game's own overrides in and the
    // dialog would end up quoting the user their own answer back as what they inherit.
    QScopedPointer<StreamingPreferences> p(StreamingPreferences::get()->clone());
    applyAppOverride(p.data(), HostProfileManager::get()->activeOverride(m_Computer->uuid));
    return inheritedValueLabels(p.data());
}

void AppModel::setAppOverride(int appIndex, const QVariantMap& src)
{
    if (appIndex < 0 || appIndex >= m_VisibleApps.count()) {
        return;
    }
    // Same shared helpers as the read above. The caller rebuilds the whole map from its
    // controls on every change, so a key it does not offer is an override being dropped —
    // which is correct here, because the per-game dialog is the only thing that writes this.
    AppSettingsManager::get()->setOverride(m_Computer->uuid, m_VisibleApps.at(appIndex).id,
                                           appOverrideFromMap(src));
    QModelIndex idx = index(appIndex, 0);
    emit dataChanged(idx, idx, { OverriddenRole });
}

bool AppModel::appHasOverride(int appIndex)
{
    if (appIndex < 0 || appIndex >= m_VisibleApps.count()) {
        return false;
    }
    return AppSettingsManager::get()->hasOverride(m_Computer->uuid, m_VisibleApps.at(appIndex).id);
}

void AppModel::clearAppOverride(int appIndex)
{
    if (appIndex < 0 || appIndex >= m_VisibleApps.count()) {
        return;
    }
    AppSettingsManager::get()->clearOverride(m_Computer->uuid, m_VisibleApps.at(appIndex).id);
    QModelIndex idx = index(appIndex, 0);
    emit dataChanged(idx, idx, { OverriddenRole });
}

int AppModel::getDirectLaunchAppIndex()
{
    for (int i = 0; i < m_VisibleApps.count(); i++) {
        if (m_VisibleApps[i].directLaunch) {
            return i;
        }
    }

    return -1;
}

int AppModel::rowCount(const QModelIndex &parent) const
{
    // For list models only the root node (an invalid parent) should return the list's size. For all
    // other (valid) parents, rowCount() should return 0 so that it does not become a tree model.
    if (parent.isValid())
        return 0;

    return m_VisibleApps.count();
}

QVariant AppModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid())
        return QVariant();

    Q_ASSERT(index.row() < m_VisibleApps.count());
    NvApp app = m_VisibleApps.at(index.row());

    switch (role)
    {
    case NameRole:
        return app.name;
    case RunningRole:
        return m_Computer->currentGameId == app.id;
    case BoxArtRole:
        // FIXME: const-correctness
        return const_cast<BoxArtManager&>(m_BoxArtManager).loadBoxArt(m_Computer, app);
    case HiddenRole:
        return app.hidden;
    case AppIdRole:
        return app.id;
    case DirectLaunchRole:
        return app.directLaunch;
    case AppCollectorGameRole:
        return app.isAppCollectorGame;
    case OverriddenRole:
        return AppSettingsManager::get()->hasOverride(m_Computer->uuid, app.id);
    case FavoriteRole:
        return m_FavoriteIds.contains(app.id);
    case SectionRole:
        // Every part of the list gets a caption, because a caption is the only thing that
        // tells the eye where a shelf ends and the library begins. The shelves are "recent"
        // and "favorites"; the tail of the list is "all", and it is captioned AS such —
        // without it the library reads as a continuous run under the last shelf above it
        // (the 21/09 report: "it's put every app under recently played" — the shelf held
        // one app; the tail simply had no caption left to say otherwise).
        if (m_RecentShelfIds.contains(app.id)) {
            return QStringLiteral("recent");
        }
        if (m_FavoriteIds.contains(app.id)) {
            return QStringLiteral("favorites");
        }
        return QStringLiteral("all");
    default:
        return QVariant();
    }
}

QHash<int, QByteArray> AppModel::roleNames() const
{
    QHash<int, QByteArray> names;

    names[NameRole] = "name";
    names[RunningRole] = "running";
    names[BoxArtRole] = "boxart";
    names[HiddenRole] = "hidden";
    names[AppIdRole] = "appid";
    names[DirectLaunchRole] = "directLaunch";
    names[AppCollectorGameRole] = "appCollectorGame";
    names[OverriddenRole] = "overridden";
    names[FavoriteRole] = "favorite";
    names[SectionRole] = "section";

    return names;
}

void AppModel::quitRunningApp()
{
    m_ComputerManager->quitRunningApp(m_Computer);
}

bool AppModel::isAppCurrentlyVisible(const NvApp& app)
{
    for (const NvApp& visibleApp : std::as_const(m_VisibleApps)) {
        if (app.id == visibleApp.id) {
            return true;
        }
    }

    return false;
}

QVector<NvApp> AppModel::getVisibleApps(const QVector<NvApp>& appList)
{
    QVector<NvApp> visibleApps;

    for (const NvApp& app : appList) {
        // Don't immediately hide games that were previously visible. This
        // allows users to easily uncheck the "Hide App" checkbox if they
        // check it by mistake.
        if (m_ShowHiddenGames || !app.hidden || isAppCurrentlyVisible(app)) {
            visibleApps.append(app);
        }
    }

    return visibleApps;
}

QVector<NvApp> AppModel::orderForDisplay(const QVector<NvApp>& appList)
{
    m_RecentShelfIds.clear();
    m_FavoriteIds.clear();

    // ── Who is pinned ───────────────────────────────────────────────────────
    // Read once for the whole list rather than per comparison. Each lookup is a QSettings
    // read, and the "everything else" pass below would otherwise ask the same question about
    // the same app a second time.
    for (const NvApp& app : appList) {
        if (AppListStateManager::get()->isFavorite(m_Computer->uuid, app.id)) {
            m_FavoriteIds.insert(app.id);
        }
    }

    // ── Shelf 1: recently played, most recent first ─────────────────────────
    QVector<QPair<qint64, NvApp>> played;
    for (const NvApp& app : appList) {
        const QDateTime when = AppListStateManager::get()->lastLaunched(m_Computer->uuid, app.id);
        if (when.isValid()) {
            played.append(QPair<qint64, NvApp>(when.toMSecsSinceEpoch(), app));
        }
    }

    // Ties broken by name so the order cannot depend on the order the host happened to send
    // its list in — two apps played in the same millisecond is unlikely and
    // non-deterministic-looking is worse than arbitrary.
    std::sort(played.begin(), played.end(), [](const QPair<qint64, NvApp>& a,
                                              const QPair<qint64, NvApp>& b) {
        if (a.first != b.first) {
            return a.first > b.first;
        }
        return a.second.name.toLower() < b.second.name.toLower();
    });

    QVector<NvApp> ordered;
    for (int i = 0; i < played.count() && i < kRecentShelfMax; i++) {
        ordered.append(played.at(i).second);
        m_RecentShelfIds.insert(played.at(i).second.id);
    }

    // ── Shelf 2: pinned favourites, in the host's own order ─────────────────
    // Deliberately not also sorted by recency: these were placed by hand, and the only order
    // a hand-placed list reads as is the one the rest of the library uses. An app that is a
    // favourite *and* recently played is already above, on the recent shelf, and is not
    // repeated here.
    for (const NvApp& app : appList) {
        if (m_FavoriteIds.contains(app.id) && !m_RecentShelfIds.contains(app.id)) {
            ordered.append(app);
        }
    }

    // ── The library proper ──────────────────────────────────────────────────
    // Whatever is left, still in the order the host sorted it (Desktop, Steam Big Picture,
    // then A–Z) — so with no favourites and nothing played, this is the list as it was.
    for (const NvApp& app : appList) {
        if (!m_RecentShelfIds.contains(app.id) && !m_FavoriteIds.contains(app.id)) {
            ordered.append(app);
        }
    }

    Q_ASSERT(ordered.count() == appList.count());
    return ordered;
}

void AppModel::updateAppList(QVector<NvApp> newList)
{
    m_AllApps = newList;

    // Ask once, and make the model match. Every ordering decision lives in orderForDisplay();
    // this function only moves rows until the two agree, and the assert at the end is the
    // proof that it did.
    QVector<NvApp> newVisibleList = orderForDisplay(getVisibleApps(newList));

    // Rows for apps the host no longer offers.
    for (int i = 0; i < m_VisibleApps.count(); i++) {
        bool found = false;
        for (const NvApp& newApp : std::as_const(newVisibleList)) {
            if (m_VisibleApps.at(i).id == newApp.id) {
                found = true;
                break;
            }
        }

        if (!found) {
            beginRemoveRows(QModelIndex(), i, i);
            m_VisibleApps.removeAt(i);
            endRemoveRows();
            i--;
        }
    }

    /*
     * Then walk the wanted order and make the two lists agree, position by position. One
     * loop covers all three cases — a row that is new, a row that has moved, and a row that
     * is already where it belongs — because every step leaves the prefix correct, so the
     * index in the wanted list is always the index to fix up next.
     *
     * ⚠️ A row that only changed shelf is MOVED, not removed and re-inserted. Re-inserting
     * would destroy and rebuild the delegate: the focused row would lose its focus, the
     * cover its loaded artwork, and the row its position on screen — for pinning a game,
     * which is meant to be a one-press toggle on a list the user is looking at.
     */
    for (int i = 0; i < newVisibleList.count(); i++) {
        const NvApp& wanted = newVisibleList.at(i);

        if (i < m_VisibleApps.count() && m_VisibleApps.at(i).id == wanted.id) {
            // Already in place. Only the row's own data can still be stale: a rename, a new
            // cover, a favourite toggled by another screen.
            if (m_VisibleApps.at(i) != wanted) {
                m_VisibleApps.replace(i, wanted);
                emit dataChanged(createIndex(i, 0), createIndex(i, 0));
            }
            continue;
        }

        int from = -1;
        for (int j = i + 1; j < m_VisibleApps.count(); j++) {
            if (m_VisibleApps.at(j).id == wanted.id) {
                from = j;
                break;
            }
        }

        if (from >= 0) {
            beginMoveRows(QModelIndex(), from, from, QModelIndex(), i);
            m_VisibleApps.move(from, i);
            endMoveRows();
        }
        else {
            beginInsertRows(QModelIndex(), i, i);
            m_VisibleApps.insert(i, wanted);
            endInsertRows();
        }
    }

    Q_ASSERT(newVisibleList == m_VisibleApps);
}

void AppModel::setAppHidden(int appIndex, bool hidden)
{
    Q_ASSERT(appIndex < m_VisibleApps.count());
    int appId = m_VisibleApps.at(appIndex).id;

    {
        QWriteLocker lock(&m_Computer->lock);

        for (NvApp& app : m_Computer->appList) {
            if (app.id == appId) {
                app.hidden = hidden;
                break;
            }
        }
    }

    m_ComputerManager->clientSideAttributeUpdated(m_Computer);
}

void AppModel::setAppDirectLaunch(int appIndex, bool directLaunch)
{
    Q_ASSERT(appIndex < m_VisibleApps.count());
    int appId = m_VisibleApps.at(appIndex).id;

    {
        QWriteLocker lock(&m_Computer->lock);

        for (NvApp& app : m_Computer->appList) {
            if (directLaunch) {
                // We must clear direct launch from all other apps
                // to set it on the new app.
                app.directLaunch = app.id == appId;
            }
            else if (app.id == appId) {
                // If we're clearing direct launch, we're done once we
                // find our matching app ID.
                app.directLaunch = false;
                break;
            }
        }
    }

    m_ComputerManager->clientSideAttributeUpdated(m_Computer);
}

void AppModel::handleComputerStateChanged(NvComputer* computer)
{
    // Ignore updates for computers that aren't ours
    if (computer != m_Computer) {
        return;
    }

    // If the computer has gone offline or we've been unpaired,
    // signal the UI so we can go back to the PC view.
    if (m_Computer->state == NvComputer::CS_OFFLINE ||
            m_Computer->pairState == NvComputer::PS_NOT_PAIRED) {
        emit computerLost();
        return;
    }

    // First, process additions/removals from the app list. This
    // is required because the new game may now be running, so
    // we can't check that first.
    if (computer->appList != m_AllApps) {
        updateAppList(computer->appList);
    }

    // Finally, process changes to the active app
    if (computer->currentGameId != m_CurrentGameId) {
        // First, invalidate the running state of newly running game
        for (int i = 0; i < m_VisibleApps.count(); i++) {
            if (m_VisibleApps[i].id == computer->currentGameId) {
                emit dataChanged(createIndex(i, 0),
                                 createIndex(i, 0),
                                 QVector<int>() << RunningRole);
                break;
            }
        }

        // Next, invalidate the running state of the old game (if it exists)
        if (m_CurrentGameId != 0) {
            for (int i = 0; i < m_VisibleApps.count(); i++) {
                if (m_VisibleApps[i].id == m_CurrentGameId) {
                    emit dataChanged(createIndex(i, 0),
                                     createIndex(i, 0),
                                     QVector<int>() << RunningRole);
                    break;
                }
            }
        }

        // Now update our internal state
        m_CurrentGameId = m_Computer->currentGameId;
    }
}

void AppModel::handleBoxArtLoaded(NvComputer* computer, NvApp app, QUrl /* image */)
{
    Q_ASSERT(computer == m_Computer);

    int index = m_VisibleApps.indexOf(app);

    // Make sure we're not delivering a callback to an app that's already been removed
    if (index >= 0) {
        // Let our view know the box art data has changed for this app
        emit dataChanged(createIndex(index, 0),
                         createIndex(index, 0),
                         QVector<int>() << BoxArtRole);
    }
    else {
        qWarning() << "App not found for box art callback:" << app.name;
    }
}
