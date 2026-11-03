#include "computerseeker.h"
#include "computermanager.h"
#include <QTimer>

ComputerSeeker::ComputerSeeker(ComputerManager *manager, QString computerName, QObject *parent)
    : QObject(parent), m_ComputerManager(manager), m_ComputerName(computerName),
      m_TimeoutTimer(new QTimer(this))
{
    // If we know this computer, send a WOL packet to wake it up in case it is asleep.
    NvComputer* matchingComputer = findMatchingComputer();
    if (matchingComputer) {
        // A command-line launch is a wake too: drop the hold, or polling would never see the
        // host come back (NvComputer::heldAsleep).
        QString uuid;
        {
            QReadLocker lock(&matchingComputer->lock);
            uuid = matchingComputer->uuid;
        }
        m_ComputerManager->setHeldAsleep(uuid, false);
        matchingComputer->wake();
    }

    m_TimeoutTimer->setSingleShot(true);
    connect(m_TimeoutTimer, &QTimer::timeout,
            this, &ComputerSeeker::onTimeout);
    connect(m_ComputerManager, &ComputerManager::computerStateChanged,
            this, &ComputerSeeker::onComputerUpdated);
}

void ComputerSeeker::start(int timeout)
{
    m_TimeoutTimer->start(timeout);

    // If we don't know this computer by name, address, or UUID, try adding it
    // manually and see if we can find it by address, hostname, or mDNS.
    //
    // NB: We don't do this unconditionally because it will wipe out the user's
    // manual address if they pass another reachable hostname/address.
    if (!findMatchingComputer()) {
        m_ComputerManager->addNewHostManually(m_ComputerName);
    }

    m_ComputerManager->startPolling();

    /*
     * A host that is already online before this process started never emits a state change:
     * the manager marks it online while it loads the saved list, and computerStateChanged
     * fires only when a state MOVES. The seeker listened for that one signal, so
     * `stream <host>` against a host that was already up sat silent for the full 30 seconds
     * and then reported "Failed to connect" — reproduced 3 Nov 2026 with the second-screen
     * launcher, which by definition starts while the host is already streaming.
     *
     * So ask directly as well, every quarter second, instead of waiting to be told.
     */
    if (m_RecheckTimer == nullptr) {
        m_RecheckTimer = new QTimer(this);
        m_RecheckTimer->setInterval(250);
        connect(m_RecheckTimer, &QTimer::timeout, this, &ComputerSeeker::recheck);
    }
    m_RecheckTimer->start();
}

void ComputerSeeker::recheck()
{
    if (!m_TimeoutTimer->isActive()) {
        m_RecheckTimer->stop();
        return;
    }

    NvComputer *computer = findMatchingComputer();
    if (computer != nullptr && isOnline(computer)) {
        m_RecheckTimer->stop();
        m_ComputerManager->stopPollingAsync();
        m_TimeoutTimer->stop();
        emit computerFound(computer);
    }
}

void ComputerSeeker::onComputerUpdated(NvComputer *computer)
{
    if (!m_TimeoutTimer->isActive()) {
        return;
    }
    if (matchComputer(computer) && isOnline(computer)) {
        m_ComputerManager->stopPollingAsync();
        m_TimeoutTimer->stop();
        emit computerFound(computer);
    }
}

bool ComputerSeeker::matchComputer(NvComputer *computer) const
{
    QString value = m_ComputerName.toLower();

    if (computer->name.toLower() == value || computer->uuid.toLower() == value) {
        return true;
    }

    const auto uniqueAddresses = computer->uniqueAddresses();
    for (const NvAddress& addr : uniqueAddresses) {
        if (addr.address().toLower() == value || addr.toString().toLower() == value) {
            return true;
        }
    }

    return false;
}

NvComputer* ComputerSeeker::findMatchingComputer() const
{
    const auto computers = m_ComputerManager->getComputers();
    for (NvComputer* computer : computers) {
        if (this->matchComputer(computer)) {
            return computer;
        }
    }

    return nullptr;
}

bool ComputerSeeker::isOnline(NvComputer *computer) const
{
    return computer->state == NvComputer::CS_ONLINE;
}

void ComputerSeeker::onTimeout()
{
    m_TimeoutTimer->stop();
    m_ComputerManager->stopPollingAsync();
    emit errorTimeout();
}
