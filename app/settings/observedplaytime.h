#pragma once

#include <QElapsedTimer>
#include <QHash>
#include <QObject>
#include <QSet>
#include <QString>

class QTimer;

// Play time for the sessions this client WATCHED rather than started.
//
// A client that merely has the host page open still knows a host is streaming: the poller reads
// currentGameId from every serverinfo response, and the host card draws the whole session off it —
// Streaming now, the artwork, the title, Resume, Stop. What it never did was keep a record. Play
// time was only ever written by the session's OWNER (Session::flushPlaytime/endPlaytime), so the
// moment another client stopped the stream this client had nothing to fall back on and its
// "Last played" block went empty — for a session it had been displaying on screen the entire time.
//
// This closes that. The record written here is the same record the owner would have written, to the
// same store (PlaytimeManager), through the same two calls: addSeconds every kMinSessionSeconds
// while the session is up, then one endSession when it ends. Nothing about the shape is
// re-invented — that is the point.
//
// ⚠️ BOTH clocks must never run for one session. If this client owns the stream, Session is already
// counting it; setOwnSession() tells this object to stand down, and the minutes it watched before
// the takeover are banked first. That is why every entry point marshals onto the Qt main thread,
// where Session's own writes happen, and why the state is only ever touched there.
class ObservedPlaytime : public QObject
{
    Q_OBJECT

public:
    static ObservedPlaytime* get();

    /**
     * One host's poll result, straight from ComputerManager::handleComputerStateChanged.
     *
     * Called on the POLLING thread, so every value is copied before it is handed over.
     *
     * ⚠️ An offline host is a HOLD, never an end. A laptop that loses Wi-Fi sees its host go
     * offline while the stream carries on perfectly well, and banking there would split one
     * session in two and count it twice. Holding costs nothing: when the host answers again the
     * id is 0 and the session banks then.
     */
    void onHostStateChanged(const QString& hostUuid, bool online, int runningGameId,
                            const QString& runningGameName);

    /**
     * This client just took the stream over (or started it). Stand the watcher down for that host —
     * Session's own clock is the record from here — after banking what was watched before the
     * takeover, which was real time the session was up.
     */
    void setOwnSession(const QString& hostUuid, bool own);

private:
    ObservedPlaytime();

    struct Watch
    {
        int gameId = 0;
        QString name;
        QElapsedTimer timer;
        qint64 flushedSecs = 0;
    };

    void handlePoll(const QString& hostUuid, int runningGameId, const QString& runningGameName);
    void endWatch(const QString& hostUuid, const QString& why);
    void flushAll();
    void startTicker();
    void stopTicker();

    QHash<QString, Watch> m_Watches;
    QSet<QString> m_OwnSessions;
    QTimer* m_Ticker = nullptr;
};
