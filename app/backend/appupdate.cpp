#include "appupdate.h"

#include <QCoreApplication>
#include <QDesktopServices>
#include <QStandardPaths>
#include <QUrl>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QProcess>
#include <QTimer>

#if defined(Q_OS_WIN32)
#include <QDir>
#include <QFile>
#endif

static QString stripTagPrefix(const QString &tag)
{
    // Release tags are bare ("1.2.0"); tolerate a "v" prefix defensively.
    QString s = tag.trimmed();
    if (s.startsWith('v') || s.startsWith('V')) {
        s.remove(0, 1);
    }
    return s;
}

AppUpdate::AppUpdate(QObject *parent) :
    QObject(parent)
{
    m_Nam = new QNetworkAccessManager(this);
    m_Nam->setStrictTransportSecurityEnabled(true);
    m_Nam->setRedirectPolicy(QNetworkRequest::NoLessSafeRedirectPolicy);
}

void AppUpdate::checkLatest()
{
    QNetworkRequest request(QUrl("https://api.github.com/repos/onaiaku/ArtMoon/releases/latest"));
    request.setRawHeader("Accept", "application/vnd.github+json");
#if QT_VERSION >= QT_VERSION_CHECK(5, 15, 0)
    request.setAttribute(QNetworkRequest::Http2AllowedAttribute, true);
#endif
    QNetworkReply *reply = m_Nam->get(request);
    connect(reply, &QNetworkReply::finished, this, &AppUpdate::handleLatestFinished);
}

int AppUpdate::compareVersions(const QString &a, const QString &b) const
{
    const QStringList pa = stripTagPrefix(a).split('.');
    const QStringList pb = stripTagPrefix(b).split('.');
    const int n = qMax(pa.size(), pb.size());
    for (int i = 0; i < n; i++) {
        int va = (i < pa.size()) ? pa[i].toInt() : 0;
        int vb = (i < pb.size()) ? pb[i].toInt() : 0;
        if (va != vb) {
            return (va < vb) ? -1 : 1;
        }
    }
    return 0;
}

void AppUpdate::handleLatestFinished()
{
    QNetworkReply *reply = qobject_cast<QNetworkReply*>(sender());
    if (!reply) {
        return;
    }
    reply->deleteLater();

    if (reply->error() != QNetworkReply::NoError) {
        qWarning() << "ArtMoon update check failed:" << reply->errorString();
        emit checkFailed();
        return;
    }

    QJsonParseError err;
    QJsonDocument doc = QJsonDocument::fromJson(reply->readAll(), &err);
    if (doc.isNull() || !doc.isObject()) {
        qWarning() << "ArtMoon update manifest malformed:" << err.errorString();
        emit checkFailed();
        return;
    }

    QJsonObject obj = doc.object();
    QString tag = obj.value("tag_name").toString();
    QString htmlUrl = obj.value("html_url").toString();
    if (tag.isEmpty()) {
        emit checkFailed();
        return;
    }

#if defined(Q_OS_WIN32)
    // Locate the silent-installable installer asset for this platform.
    m_InstallerAssetUrl.clear();
    const QJsonArray assets = obj.value("assets").toArray();
    for (const auto &assetVal : assets) {
        const QJsonObject asset = assetVal.toObject();
        const QString name = asset.value("name").toString();
        if (name.compare("ArtMoon_Installer.exe", Qt::CaseInsensitive) == 0) {
            m_InstallerAssetUrl = asset.value("browser_download_url").toString();
            break;
        }
    }
#endif

    emit latestReady(tag, htmlUrl);
}

QString AppUpdate::findTerminal() const
{
    // Preference order: the user's default x-terminal-emulator, then the
    // terminals a desktop user is most likely to have. konsole first because
    // the target environments for the AppImage are KDE.
    const QStringList candidates = {
        "x-terminal-emulator", "konsole", "gnome-terminal",
        "xfce4-terminal", "qterminal", "mate-terminal", "xterm"
    };
    for (const QString &term : candidates) {
        const QString path = QStandardPaths::findExecutable(term);
        if (!path.isEmpty()) {
            return path;
        }
    }
    return QString();
}

void AppUpdate::updateNow()
{
    emit updateStarting();

#if defined(Q_OS_WIN32)
    // 1) Download the latest installer into %TEMP%.
    if (m_InstallerAssetUrl.isEmpty()) {
        // Nothing downloadable found in the release - fall back to the
        // releases page so the user can still update by hand.
        QDesktopServices::openUrl(QUrl("https://github.com/onaiaku/ArtMoon/releases"));
        QCoreApplication::quit();
        return;
    }

    const QString tmpDir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    const QString target = tmpDir + "/ArtMoon_Installer.exe";

    QNetworkAccessManager *dl = new QNetworkAccessManager(this);
    dl->setStrictTransportSecurityEnabled(true);
    dl->setRedirectPolicy(QNetworkRequest::NoLessSafeRedirectPolicy);
    QNetworkReply *reply = dl->get(QNetworkRequest(QUrl(m_InstallerAssetUrl)));

    connect(reply, &QNetworkReply::finished, this, [this, dl, target]() {
        QNetworkReply *r = qobject_cast<QNetworkReply*>(sender());
        if (!r) {
            return;
        }
        if (r->error() != QNetworkReply::NoError) {
            qWarning() << "ArtMoon installer download failed:" << r->errorString();
            QDesktopServices::openUrl(QUrl("https://github.com/onaiaku/ArtMoon/releases"));
            QCoreApplication::quit();
            return;
        }

        QFile f(target);
        if (!f.open(QIODevice::WriteOnly) || f.write(r->readAll()) < 0) {
            qWarning() << "ArtMoon installer write failed:" << target;
            QDesktopServices::openUrl(QUrl("https://github.com/onaiaku/ArtMoon/releases"));
            QCoreApplication::quit();
            return;
        }
        f.close();
        r->deleteLater();
        dl->deleteLater();

        // 2) Launch it silently - Inno will stop the app, upgrade, and restart
        //    it (/RESTARTAPPLICATIONS). The user sees one UAC prompt.
        QProcess::startDetached(target, {"VERYSILENT"});
        QCoreApplication::quit();
    });
#else
    // Linux: run the repo installer/updater script in a visible terminal so it
    // can ask for the sudo password it needs for /usr/local/bin, then relaunch.
    const QString terminal = findTerminal();
    const QString cmd =
        "curl -fsSL https://raw.githubusercontent.com/onaiaku/ArtMoon/main/install.sh | bash; "
        "echo; echo 'Update finished. Press Enter to relaunch ArtMoon...'; read -r; "
        "(setsid " + QCoreApplication::applicationFilePath() + " >/dev/null 2>&1 &)";

    if (terminal.isEmpty()) {
        // No terminal we know of - open the releases page as a fallback.
        QDesktopServices::openUrl(QUrl("https://github.com/onaiaku/ArtMoon/releases"));
        QCoreApplication::quit();
        return;
    }

    // x-terminal-emulator and konsole take -e; gnome-terminal takes --; the
    // rest take -e. Use -e for everything except gnome-terminal/mate-terminal.
    QStringList args;
    if (terminal.endsWith("gnome-terminal") || terminal.endsWith("mate-terminal")) {
        args << "--" << "bash" << "-c" << cmd;
    } else {
        args << "-e" << "bash" << "-c" << cmd;
    }

    QProcess::startDetached(terminal, args);
    // Give the terminal a moment to come up before we leave the stage.
    QTimer::singleShot(500, QCoreApplication::instance(), &QCoreApplication::quit);
#endif
}
