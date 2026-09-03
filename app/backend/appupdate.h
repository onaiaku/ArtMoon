#pragma once

#include <QObject>
#include <QNetworkAccessManager>

// Live GitHub-release version check + one-click self-update for ArtMoon.
//
// Linux:  spawns a terminal running the repo's install.sh (which doubles as the
//         updater), then quits so the script can relaunch the app fresh.
// Windows: downloads the latest ArtMoon_Installer.exe from the release assets
//         to %TEMP%, quits, and runs it silently — Inno's /RESTARTAPPLICATIONS
//         brings ArtMoon back up after the upgrade. One UAC prompt is expected;
//         Windows does not allow silent elevation for anything touching
//         Program Files, and the user should see it anyway.

class AppUpdate : public QObject
{
    Q_OBJECT

public:
    explicit AppUpdate(QObject *parent = nullptr);

    Q_INVOKABLE void checkLatest();
    Q_INVOKABLE void updateNow();

    // "1.2.10" style compare: negative if a < b, 0 equal, positive if a > b.
    Q_INVOKABLE int compareVersions(const QString &a, const QString &b) const;

signals:
    void latestReady(QString tag, QString releaseUrl);
    void checkFailed();
    void updateStarting();

private slots:
    void handleLatestFinished();

private:
    QString findTerminal() const;

    QNetworkAccessManager *m_Nam;
    QString m_InstallerAssetUrl; // Windows: browser_download_url of *_Installer.exe
};
