#include "startstream.h"
#include "backend/computermanager.h"
#include "backend/computerseeker.h"
#include "settings/appsettings.h"
#include "streaming/session.h"

#include <QCoreApplication>
#include <QTimer>

#include <SDL.h>

#define COMPUTER_SEEK_TIMEOUT 30000
#define APP_SEEK_TIMEOUT 10000

namespace CliStartStream
{

enum State {
    StateInit,
    StateSeekComputer,
    StateSeekApp,
    StateStartSession,
    StateFailure,
};

class Event
{
public:
    enum Type {
        AppQuitCompleted,
        AppQuitRequested,
        ComputerFound,
        ComputerUpdated,
        Executed,
        Timedout,
    };

    Event(Type type)
        : type(type), computerManager(nullptr), computer(nullptr) {}

    Type type;
    ComputerManager *computerManager;
    NvComputer *computer;
    QString errorMessage;
};

class LauncherPrivate
{
    Q_DECLARE_PUBLIC(Launcher)

public:
    LauncherPrivate(Launcher *q) : q_ptr(q) {}

    void handleEvent(Event event)
    {
        Q_Q(Launcher);
        Session* session;
        NvApp app;

    /*
     * Look for our app in the list we hold *now*. Called when the app search begins and again
     * on every host update, because the list can already be complete before the search starts:
     * the poller fetches it, finds nothing changed, and therefore emits no update at all — so a
     * waiter that only reacts to updates waits out the full timeout while holding the answer.
     * That is 3 Nov 15:36 exactly: the timeout message printed the host's list and "Remote
     * Monitor" (id 2147483505) was in it, unpadded and spelled right.
     */
    auto tryStartSession = [&]() {
        if (m_State != StateSeekApp) {
            return;
        }

        const int index = getAppIndex();
        if (index == -1) {
            return;
        }

        app = m_Computer->appList[index];
        m_TimeoutTimer->stop();
        if (isNotStreaming() || isStreamingApp(app) || isSecondScreenInstance()) {
            m_State = StateStartSession;

            /*
             * The same cascade the app picker uses — global, then this host's profile, then
             * this app's overrides — rather than the raw global object. Launching from the
             * command line used to bypass it completely, so a per-app override was silently
             * ignored. The second window is always launched from here, which is how Nik's
             * 1440p138 override for Remote Monitor produced four launches all asking for
             * 3840x2160x138 (3 Nov 2026) and looked like the setting did nothing.
             */
            StreamingPreferences* prefs = AppSettingsManager::get()->buildPrefs(
                m_Preferences, m_Computer->uuid, app.id);

            /*
             * buildPrefs clones through StreamingPreferences::clone(), which deliberately does
             * not carry streamOnDisplay: that field is a session's placement from --on-display,
             * and a copy of it would make every session believe it was the second screen.
             * The clone is exactly where we need it though, because this process *is* the
             * second screen, and the placement is the instruction it was started with. Without
             * this copy the child lost its own placement: isSecondScreenInstance() went false,
             * and the session then spawned a second screen of its own - three ArtMoon windows
             * and a Remote Monitor session colliding with itself (3 Nov, 16:41).
             */
            prefs->streamOnDisplay = m_Preferences->streamOnDisplay;

            /*
             * The second screen is the only window that knows which panel it is on. Everything
             * else in the chain - the host, the virtual display - can only repeat whatever mode
             * it is told, so until now it was told the MAIN stream's mode and built a 4K display
             * for a 1440p screen. Ask the panel it will actually be shown on for its own numbers.
             *
             * A per-app override still wins: that is someone deliberately choosing a mode, and
             * a deliberate choice beats a default we can read.
             */
            if (!prefs->streamOnDisplay.isEmpty() &&
                !AppSettingsManager::get()->hasOverride(m_Computer->uuid, app.id)) {
                const bool videoReady = SDL_WasInit(SDL_INIT_VIDEO) != 0 ||
                                        SDL_InitSubSystem(SDL_INIT_VIDEO) == 0;
                if (videoReady) {
                    const int displays = SDL_GetNumVideoDisplays();
                    for (int i = 0; i < displays; i++) {
                        const char *name = SDL_GetDisplayName(i);
                        if (name == nullptr || prefs->streamOnDisplay != QString::fromUtf8(name)) {
                            continue;
                        }
                        SDL_DisplayMode panel {};
                        if (SDL_GetDesktopDisplayMode(i, &panel) == 0 &&
                            panel.w > 0 && panel.h > 0) {
                            prefs->width = panel.w;
                            prefs->height = panel.h;
                            if (panel.refresh_rate > 0) {
                                prefs->fps = panel.refresh_rate;
                            }
                        }
                        break;
                    }
                }
            }

            /*
             * The second screen's own numbers, from Settings → Display → Second screen.
             *
             * Applied last, and only for the window that is the second screen, because
             * everything above is a default: a resolution and frame rate read off the panel,
             * and a bitrate inherited from the main stream. A deliberate choice has to beat
             * both, and 0 means "nothing was chosen" rather than "choose nothing".
             */
            if (!prefs->streamOnDisplay.isEmpty()) {
                if (prefs->secondScreenWidth > 0 && prefs->secondScreenHeight > 0) {
                    prefs->width = prefs->secondScreenWidth;
                    prefs->height = prefs->secondScreenHeight;
                }
                if (prefs->secondScreenFps > 0) {
                    prefs->fps = prefs->secondScreenFps;
                }
                if (prefs->secondScreenBitrateKbps > 0) {
                    prefs->bitrateKbps = prefs->secondScreenBitrateKbps;
                }
                logSecondScreen(QStringLiteral("stream settings: %1x%2 @ %3 fps, %4 Mbps")
                                    .arg(prefs->width).arg(prefs->height).arg(prefs->fps)
                                    .arg(prefs->bitrateKbps / 1000.0, 0, 'f', 1));
            }

            session = new Session(m_Computer, app, prefs);
            prefs->setParent(session);
            emit q->sessionCreated(app.name, session);
        } else {
            emit q->appQuitRequired(getCurrentAppName());
        }
    };

        switch (event.type) {
        // Occurs when CliStartStreamSegue becomes visible and the UI calls launcher's execute()
        case Event::Executed:
            if (m_State == StateInit) {
                m_State = StateSeekComputer;
                m_ComputerManager = event.computerManager;

                m_ComputerSeeker = new ComputerSeeker(m_ComputerManager, m_ComputerName, q);
                q->connect(m_ComputerSeeker, &ComputerSeeker::computerFound,
                           q, &Launcher::onComputerFound);
                q->connect(m_ComputerSeeker, &ComputerSeeker::errorTimeout,
                           q, &Launcher::onTimeout);
                m_ComputerSeeker->start(COMPUTER_SEEK_TIMEOUT);

                q->connect(m_ComputerManager, &ComputerManager::computerStateChanged,
                           q, &Launcher::onComputerUpdated);
                q->connect(m_ComputerManager, &ComputerManager::quitAppCompleted,
                           q, &Launcher::onQuitAppCompleted);

                emit q->searchingComputer();
            }
            break;
        // Occurs when searched computer is found
        case Event::ComputerFound:
            if (m_State == StateSeekComputer) {
                if (event.computer->pairState == NvComputer::PS_PAIRED) {
                    m_State = StateSeekApp;
                    m_Computer = event.computer;
                    m_TimeoutTimer->start(APP_SEEK_TIMEOUT);
                    emit q->searchingApp();
                    tryStartSession();
                } else {
                    m_State = StateFailure;
                    QString msg = QObject::tr("Computer %1 has not been paired. "
                                              "Please open ArtMoon to pair before streaming.")
                            .arg(event.computer->name);
                    emit q->failed(msg);
                }
            }
            break;
        // Occurs when a computer is updated
        case Event::ComputerUpdated:
            tryStartSession();
            break;
        // Occurs when there was another app running on computer and user accepted quit
        // confirmation dialog
        case Event::AppQuitRequested:
            if (m_State == StateSeekApp) {
                m_ComputerManager->quitRunningApp(m_Computer);
            }
            break;
        // Occurs when the previous app quit has been completed, handles quitting errors if any
        // happened. ComputerUpdated event's handler handles session start when previous app has
        // quit.
        case Event::AppQuitCompleted:
            if (m_State == StateSeekApp && !event.errorMessage.isEmpty()) {
                m_State = StateFailure;
                emit q->failed(QObject::tr("Quitting app failed, reason: %1").arg(event.errorMessage));
            }
            break;
        // Occurs when computer or app search timed out
        case Event::Timedout:
            if (m_State == StateSeekComputer) {
                m_State = StateFailure;
                emit q->failed(QObject::tr("Failed to connect to %1").arg(m_ComputerName));
            }
            if (m_State == StateSeekApp) {
                m_State = StateFailure;
                emit q->failed(QObject::tr("Failed to find application %1 — %2")
                                     .arg(m_AppName, appListNames()));
            }
            break;
        }
    }

    int getAppIndex() const
    {
        /*
         * Trimmed, case-insensitive. ArtLight ranks its host controls for Moonlight's launch
         * list by prefixing spaces that sort them after the configured apps, so the same tile
         * is "Remote Monitor" while the host is idle and "    Remote Monitor" while a game is
         * running - and the second screen is only ever started while a game is running. An
         * exact comparison found the one case that never happens and missed the one that
         * always does: 3 Nov 15:13 and 15:23 both failed this way, while the headless test
         * with nothing streaming at 14:04 passed.
         */
        const QString wanted = m_AppName.trimmed();
        for (int i = 0; i < m_Computer->appList.length(); i++) {
            if (m_Computer->appList[i].name.trimmed().compare(wanted, Qt::CaseInsensitive) == 0) {
                return i;
            }
        }
        return -1;
    }

    // Names as the host listed them, for the failure line. A list we could not search is a
    // fact worth printing; the old message named only what we wanted and nothing we had.
    QString appListNames() const
    {
        QStringList names;
        for (const NvApp& entry : m_Computer->appList) {
            names.append(QStringLiteral("'%1' (id %2)").arg(entry.name).arg(entry.id));
        }
        return names.isEmpty() ? QStringLiteral("the host listed no applications")
                                : QStringLiteral("the host listed %1").arg(names.join(QStringLiteral(", ")));
    }

    bool isNotStreaming() const
    {
        return m_Computer->currentGameId == 0;
    }

    /*
     * The second-screen window is started by this program while the main stream is already
     * running, so the host always answers that some other app — Desktop, the game — is the
     * current one. The check below reads that as "you must close it first" and pops the
     * "quit the running application?" question, which is exactly wrong here: the thing it
     * offers to close is the stream this window is being added to.
     *
     * ArtLight gives a client a Remote Monitor session *alongside* its game session, so the
     * two are not alternatives. Only ever set by our own launcher, so this cannot loosen the
     * rule for anyone typing the command by hand.
     */
    bool isSecondScreenInstance() const
    {
        return m_Preferences != nullptr && !m_Preferences->streamOnDisplay.isEmpty();
    }

    bool isStreamingApp(NvApp app) const
    {
        return m_Computer->currentGameId == app.id;
    }

    QString getCurrentAppName() const
    {
        for (const NvApp& app : std::as_const(m_Computer->appList)) {
            if (m_Computer->currentGameId == app.id) {
                return app.name;
            }
        }
        return "<UNKNOWN>";
    }

    Launcher *q_ptr;
    QString m_ComputerName;
    QString m_AppName;
    StreamingPreferences *m_Preferences;
    ComputerManager *m_ComputerManager;
    ComputerSeeker *m_ComputerSeeker;
    NvComputer *m_Computer;
    State m_State;
    QTimer *m_TimeoutTimer;
};

Launcher::Launcher(QString computer, QString app,
                   StreamingPreferences* preferences, QObject *parent)
    : QObject(parent),
      m_DPtr(new LauncherPrivate(this))
{
    Q_D(Launcher);
    d->m_ComputerName = computer;
    d->m_AppName = app;
    d->m_Preferences = preferences;
    d->m_State = StateInit;
    d->m_TimeoutTimer = new QTimer(this);
    d->m_TimeoutTimer->setSingleShot(true);
    connect(d->m_TimeoutTimer, &QTimer::timeout,
            this, &Launcher::onTimeout);
}

Launcher::~Launcher()
{
}

void Launcher::execute(ComputerManager *manager)
{
    Q_D(Launcher);
    Event event(Event::Executed);
    event.computerManager = manager;
    d->handleEvent(event);
}

void Launcher::quitRunningApp()
{
    Q_D(Launcher);
    Event event(Event::AppQuitRequested);
    d->handleEvent(event);
}

bool Launcher::isExecuted() const
{
    Q_D(const Launcher);
    return d->m_State != StateInit;
}

void Launcher::onComputerFound(NvComputer *computer)
{
    Q_D(Launcher);
    Event event(Event::ComputerFound);
    event.computer = computer;
    d->handleEvent(event);
}

void Launcher::onComputerUpdated(NvComputer *computer)
{
    Q_D(Launcher);
    Event event(Event::ComputerUpdated);
    event.computer = computer;
    d->handleEvent(event);
}

void Launcher::onTimeout()
{
    Q_D(Launcher);
    Event event(Event::Timedout);
    d->handleEvent(event);
}

void Launcher::onQuitAppCompleted(QVariant error)
{
    Q_D(Launcher);
    Event event(Event::AppQuitCompleted);
    event.errorMessage = error.toString();
    d->handleEvent(event);
}

}
