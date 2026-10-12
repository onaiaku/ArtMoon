package io.github.onaiaku.artmoon.nvstream.http;

import io.github.onaiaku.artmoon.LimeLog;

public class NvApp {
    private String appName = "";
    private int appId;
    private boolean initialized;
    private boolean hdrSupported;

    /**
     * The server's own identity for the entry (applist &lt;UUID&gt;), empty on hosts that do not send
     * one. Needed to tell the 2.0 host controls apart: their titles are padded with spaces so they
     * sort first, and the running-game copy carries the game's own name, so neither the name nor the
     * id is reliable on its own. Ported from nvapp.h.
     */
    private String uuid = "";

    /** True on entries the server calls a game it can collect (applist &lt;IsAppCollectorGame&gt;). */
    private boolean isAppCollectorGame;
    
    public NvApp() {}
    
    public NvApp(String appName) {
        this.appName = appName;
    }
    
    public NvApp(String appName, int appId, boolean hdrSupported) {
        this.appName = appName;
        this.appId = appId;
        this.hdrSupported = hdrSupported;
        this.initialized = true;
    }
    
    public void setAppName(String appName) {
        this.appName = appName;
    }
    
    public void setAppId(String appId) {
        try {
            this.appId = Integer.parseInt(appId);
            this.initialized = true;
        } catch (NumberFormatException e) {
            LimeLog.warning("Malformed app ID: "+appId);
        }
    }
    
    public void setAppId(int appId) {
        this.appId = appId;
        this.initialized = true;
    }

    public void setHdrSupported(boolean hdrSupported) {
        this.hdrSupported = hdrSupported;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid == null ? "" : uuid;
    }

    public String getUuid() {
        return this.uuid;
    }

    public void setAppCollectorGame(boolean isAppCollectorGame) {
        this.isAppCollectorGame = isAppCollectorGame;
    }

    public boolean isAppCollectorGame() {
        return this.isAppCollectorGame;
    }
    
    public String getAppName() {
        return this.appName;
    }
    
    public int getAppId() {
        return this.appId;
    }

    public boolean isHdrSupported() {
        return this.hdrSupported;
    }
    
    public boolean isInitialized() {
        return this.initialized;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Name: ").append(appName).append("\n");
        str.append("HDR Supported: ").append(hdrSupported ? "Yes" : "Unknown").append("\n");
        str.append("ID: ").append(appId).append("\n");
        return str.toString();
    }
}
