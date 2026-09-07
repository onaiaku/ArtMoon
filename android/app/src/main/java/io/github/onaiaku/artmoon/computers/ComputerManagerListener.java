package io.github.onaiaku.artmoon.computers;

import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;

public interface ComputerManagerListener {
    void notifyComputerUpdated(ComputerDetails details);
}
