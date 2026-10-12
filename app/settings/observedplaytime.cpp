#include "observedplaytime.h"

#include "playtime.h"

#include <QCoreApplication>
#include <QMetaObject>
#include <QThread>
#include <QTimer>

ObservedPlaytime* ObservedPlaytime::get()
{
    static ObservedPlaytime instance;
    return &instance;
}

ObservedPlaytime::ObservedPlaytime()
{
    // The flush writes PlaytimeManager, which Session also writes from the Qt main thread, so this
    // object belongs there with it: that is what keeps the two clocks for one session from writing
    // the same store at the same time. First call can come from the polling thread, hence the move.
    QCoreApplication* app = QCoreApplication::instance();
    if (app && QThread::currentThread() != app->thread()) {
        moveToThread(app->thread());
    }
}

void ObservedPlaytime::onHostStateChanged(const QString& hostUuid, bool online, int runningGameId,
                                          const QString& runningGameName)
{
    if (hostUuid.isEmpty() || !online) {
        return;
    }

    QMetaObject::invokeMethod(this, [this, hostUuid, runningGameId, runningGameName]() {
        handlePoll(hostUuid, runningGameId, runningGameName);
    }, Qt::QueuedConnection);
}

void ObservedPlaytime::setOwnSession(const QString& hostUuid, bool own)
{
    if (hostUuid.isEmpty()) {
        return;
    }

    QMetaObject::invokeMethod(this, [this, hostUuid, own]() {
        if (own) {
            m_OwnSessions.insert(hostUuid);
            endWatch(hostUuid, QStringLiteral("own stream took over"));
        }
        else {
            m_OwnSessions.remove(hostUuid);
        }
    }, Qt::QueuedConnection);
}

void ObservedPlaytime::handlePoll(const QString& hostUuid, int gameId, const QString& name)
{
    const bool watching = m_Watches.contains(hostUuid);

    // Nothing running: the session ended wherever it was started.
    if (gameId == 0) {
        endWatch(hostUuid, QStringLiteral("host idle"));
        return;
    }

    // Running, but this client cannot name it yet. Hold rather than end: an app list that has not
    // synced is not a stream that has stopped. A DIFFERENT id does mean the old session is over.
    if (name.isEmpty()) {
        if (watching && m_Watches.value(hostUuid).gameId != gameId) {
            endWatch(hostUuid, QStringLiteral("host switched to an unnamed game"));
        }
        return;
    }

    // The host's Desktop entry and its controls are entries in the app list but not something
    // anyone "played" (refused by the same gate the owner uses).
    if (!PlaytimeManager::isTracked(name)) {
        endWatch(hostUuid, QStringLiteral("untracked app"));
        return;
    }

    // This client owns the stream: Session is counting it, not us.
    if (m_OwnSessions.contains(hostUuid)) {
        endWatch(hostUuid, QStringLiteral("own stream owns it"));
        return;
    }

    // Same session still up — the clock keeps running and nothing is written until the flush.
    if (watching && m_Watches.value(hostUuid).gameId == gameId) {
        return;
    }

    // A different game: the previous session is over, this one starts now.
    if (watching) {
        endWatch(hostUuid, QStringLiteral("host switched games"));
    }

    Watch watch;
    watch.gameId = gameId;
    watch.name = name;
    watch.timer.start();
    m_Watches.insert(hostUuid, watch);
    startTicker();

    qInfo() << "watching session:" << hostUuid << gameId << name;
}

/**
 * Banks the seconds since the last flush, on the same interval Session uses, so a session that ends
 * without teardown — a kill, a power cut — keeps all but its last minute.
 */
void ObservedPlaytime::flushAll()
{
    if (m_Watches.isEmpty()) {
        stopTicker();
        return;
    }

    for (auto it = m_Watches.begin(); it != m_Watches.end(); ++it) {
        Watch& watch = it.value();

        const qint64 elapsed = watch.timer.elapsed() / 1000;
        const qint64 unbanked = elapsed - watch.flushedSecs;
        if (unbanked <= 0) {
            continue;
        }

        PlaytimeManager::get()->addSeconds(it.key(), watch.name, watch.gameId, unbanked);
        watch.flushedSecs = elapsed;
    }
}

/**
 * Closes a watched session: banks the time the flushes have not already taken, then records it as
 * that host's last played game.
 *
 * ⚠️ The two durations are not interchangeable, exactly as in Session::endPlaytime. `unbanked` is
 * what the flushes have not taken — passing the whole session would count every flushed minute
 * twice. `elapsed` is the session's real length, which is what the 60-second threshold is judged
 * against.
 */
void ObservedPlaytime::endWatch(const QString& hostUuid, const QString& why)
{
    if (!m_Watches.contains(hostUuid)) {
        return;
    }

    const Watch watch = m_Watches.take(hostUuid);

    const qint64 elapsed = watch.timer.elapsed() / 1000;
    const qint64 unbanked = qMax<qint64>(0, elapsed - watch.flushedSecs);

    // A default-constructed set of stats is every field at -1 — "never measured" — which is the
    // truth here: a client that is not streaming has no decoder to read them off, and the panel
    // prints dashes for it rather than inventing zeroes.
    PlaytimeManager::get()->endSession(hostUuid, watch.name, watch.gameId,
                                       unbanked, elapsed, PlaytimeSessionStats());

    qInfo() << "watched session ended:" << hostUuid << watch.name << elapsed << "s"
            << "(unbanked" << unbanked << "s," << why << ")";

    if (m_Watches.isEmpty()) {
        stopTicker();
    }
}

void ObservedPlaytime::startTicker()
{
    // Created here rather than in the constructor: a QTimer must be made on the thread that will
    // run it, and startTicker() only ever runs on the main thread.
    if (!m_Ticker) {
        m_Ticker = new QTimer(this);
        m_Ticker->setInterval(static_cast<int>(PlaytimeManager::kMinSessionSeconds * 1000));
        m_Ticker->setSingleShot(false);
        connect(m_Ticker, &QTimer::timeout, this, &ObservedPlaytime::flushAll);
    }

    if (!m_Ticker->isActive()) {
        m_Ticker->start();
    }
}

void ObservedPlaytime::stopTicker()
{
    if (m_Ticker) {
        m_Ticker->stop();
    }
}
