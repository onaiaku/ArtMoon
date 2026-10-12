package io.github.onaiaku.artmoon;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import io.github.onaiaku.artmoon.computers.ComputerManagerListener;
import io.github.onaiaku.artmoon.computers.ComputerManagerService;
import io.github.onaiaku.artmoon.grid.AppAmbientArtwork;
import io.github.onaiaku.artmoon.apps.AppLibrary;
import io.github.onaiaku.artmoon.settings.PlaytimeManager;
import io.github.onaiaku.artmoon.grid.AppGridAdapter;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.nvstream.http.NvHTTP;
import io.github.onaiaku.artmoon.nvstream.http.PairingManager;
import io.github.onaiaku.artmoon.preferences.PreferenceConfiguration;
import io.github.onaiaku.artmoon.preferences.StreamSettings;
import io.github.onaiaku.artmoon.artlight.InputModeManager;
import io.github.onaiaku.artmoon.ui.AdapterFragment;
import io.github.onaiaku.artmoon.ui.AdapterFragmentCallbacks;
import io.github.onaiaku.artmoon.utils.CacheHelper;
import io.github.onaiaku.artmoon.utils.Dialog;
import io.github.onaiaku.artmoon.utils.ServerHelper;
import io.github.onaiaku.artmoon.utils.ShortcutHelper;
import io.github.onaiaku.artmoon.utils.SpinnerDialog;
import io.github.onaiaku.artmoon.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.KeyEvent;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends io.github.onaiaku.artmoon.ArtMoonActivity implements AdapterFragmentCallbacks {
    private io.github.onaiaku.artmoon.artlight.PromptBar promptBar;
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    // Which tab the library is on. The host remembers the last one (PlaytimeManager), and the
    // desktop opens on it rather than always on ALL.
    private String currentCategory = AppLibrary.CAT_ALL;
    // The host's app list as it last arrived, kept so the page can be rebuilt when something other
    // than the list changes: playtime recorded, a pin toggled, an entry moved, a tab chosen.
    private List<NvApp> lastAppList = new ArrayList<NvApp>();
    // Ids the host listed last time, so a shortcut is enabled once for a new entry and disabled
    // once for one the host has dropped.
    private final Set<Integer> knownAppIds = new HashSet<Integer>();
    // Pinning and moving are statements about which tab the user wants; they must not be undone
    // by the rule that opens a host on APPS because its Remote Monitor is up.
    private boolean tabChosenByUser = false;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    // Ambient backdrop (desktop CoverAmbient parity): the focused app's cover,
    // blurred, behind the whole picker. Landscape only — the layouts carry the view.
    private ImageView ambientView;
    private AppAmbientArtwork ambientArtwork;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    // Landscape master-detail picker: the app highlighted in the master list
    // and shown in the detail panel. Null in portrait (tap-to-start there).
    private AppObject selectedApp;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int HIDE_APP_ID = 7;
    private final static int PIN_APP_ID = 8;
    private final static int MOVE_APP_ID = 9;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    // The host remembers the tab, so coming back to this machine opens where it
                    // was left rather than always on ALL.
                    String savedTab = PlaytimeManager.lastTabOn(AppView.this, uuidString);
                    if (savedTab != null && !savedTab.isEmpty()) {
                        currentCategory = savedTab;
                    }
                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // ArtLight integration: fetch the host's app -> store map
                    // and feed it to the adapter for the badge chips. Best
                    // effort — empty map on unreachable/older hosts.
                    final AppGridAdapter adapterForStores = appGridAdapter;
                    // Prefer the active (reachable) address; fall back through
                    // remote/local/manual like the rest of the client does.
                    final String hostAddress = (computer.activeAddress != null) ? computer.activeAddress.address :
                            (computer.remoteAddress != null) ? computer.remoteAddress.address :
                            (computer.localAddress != null) ? computer.localAddress.address :
                            (computer.manualAddress != null) ? computer.manualAddress.address : null;
                    if (hostAddress != null) {
                        new Thread() {
                        @Override
                        public void run() {
                            io.github.onaiaku.artmoon.artlight.ArtLightBridge bridge =
                                    new io.github.onaiaku.artmoon.artlight.ArtLightBridge(AppView.this);
                            bridge.requestAppStores(hostAddress, new io.github.onaiaku.artmoon.artlight.ArtLightBridge.ResponseCallback() {
                                @Override
                                public void onResult(final String response) {
                                    if (response == null || response.isEmpty()) {
                                        return;
                                    }
                                    try {
                                        org.json.JSONObject obj = new org.json.JSONObject(response);
                                        final java.util.HashMap<String, String> map = new java.util.HashMap<>();
                                        java.util.Iterator<String> keys = obj.keys();
                                        while (keys.hasNext()) {
                                            String key = keys.next();
                                            map.put(key, obj.optString(key));
                                        }
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                adapterForStores.setStoreMap(map);
                                            }
                                        });
                                    } catch (Exception e) {
                                        io.github.onaiaku.artmoon.LimeLog.info(
                                                "APPSTORES parse failed: " + e.getMessage());
                                    }
                                }
                            });
                        }
                        }.start();
                    }

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Re-assert the no-opinion orientation on every screen change, so a
        // lock left by an older build (or a cover screen) is released and the
        // app keeps following the device.
        OrientationHelper.applyOrientation(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, getResources().getText(R.string.lost_connection), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, getResources().getText(R.string.scut_not_paired), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OrientationHelper.applyOrientation(this);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        // Desktop header treatment: host name in caps with the online badge beside it.
        label.setText(computerName.toUpperCase());

// ArtMoon picker spec chips (mirror of hero-card chips, PcGridAdapter source).
TextView pickRes = findViewById(R.id.am_pick_spec_res);
TextView pickFps = findViewById(R.id.am_pick_spec_fps);
TextView pickBitrate = findViewById(R.id.am_pick_spec_bitrate);
TextView pickCodec = findViewById(R.id.am_pick_spec_codec);
TextView pickAudio = findViewById(R.id.am_pick_spec_audio);
if (pickRes != null && pickFps != null && pickBitrate != null) {
    PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
    pickRes.setText(prefs.width + "\u00d7" + prefs.height);
    pickFps.setText(prefs.fps + " FPS");
    pickBitrate.setText((prefs.bitrate + 999) / 1000 + " Mbps");
    if (pickCodec != null) pickCodec.setText(codecLabel(prefs.videoFormat));
    if (pickAudio != null) pickAudio.setText(audioLabel(prefs.audioConfiguration));
}

        // ArtMoon header back button (desktop header affordance).
        ImageButton backButton = findViewById(R.id.am_back);
        if (backButton != null) {
            backButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
        rebuildPage();
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = objectForPosition(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        // Pin and move, the two things the page lets a user do to the library itself. They are for
        // the entries the user owns: a host control (Remote Input, Resume, the running-game copy)
        // is not a game to pin and cannot be moved between tabs.
        final AppLibrary.Row row = appGridAdapter.rowAt(info.position);
        if (row != null && row.movable) {
            MenuItem pinItem = menu.add(Menu.NONE, PIN_APP_ID, 6,
                    getResources().getString(row.pinned ? R.string.applist_menu_unpin : R.string.applist_menu_pin));
            pinItem.setCheckable(true);
            pinItem.setChecked(row.pinned);

            menu.add(Menu.NONE, MOVE_APP_ID, 7,
                    getResources().getString(row.isApp ? R.string.applist_menu_move_games : R.string.applist_menu_move_apps));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = objectForPosition(info.position);
        switch (item.getItemId()) {
            case PIN_APP_ID: {
                AppLibrary.Row row = appGridAdapter.rowAt(info.position);
                if (row != null) {
                    PlaytimeManager.setPinned(this, uuidString, row.name, !row.pinned);
                    rebuildPageKeeping(row.appId);
                }
                return true;
            }

            case MOVE_APP_ID: {
                AppLibrary.Row row = appGridAdapter.rowAt(info.position);
                if (row != null) {
                    // The desktop sets _tabChosenByUser BEFORE the call. Moving an entry is the
                    // strongest statement of which tab is wanted, and it must survive the rebuild.
                    tabChosenByUser = true;
                    PlaytimeManager.setCategoryOverride(this, uuidString, row.name, !row.isApp, false);
                    rebuildPageKeeping(row.appId);
                }
                return true;
            }

            case START_WITH_QUIT:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doStartWithCurtain(AppView.this, app.app, computer, managerBinder);
                    }
                }, null);
                return true;

            case START_OR_RESUME_ID:
                // Resume is the same as start for us
                ServerHelper.doStartWithCurtain(AppView.this, app.app, computer, managerBinder);
                return true;

            case QUIT_ID:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                            @Override
                            public void run() {
                                // Trigger a poll immediately
                                suspendGridUpdates = false;
                                if (poller != null) {
                                    poller.pollNow();
                                }
                            }
                        });
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;

            case HIDE_APP_ID:
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                }
                else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;

            case CREATE_SHORTCUT_ID:
                ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable)appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // The running game belongs to the page, not to a flag painted onto rows. The page
                // is handed the host's running id and knows which entry that is, so this rebuilds
                // rather than walking the list — walking it is also how a row kept a stale
                // "running" after a session ended on another device.
                if (lastRunningAppId != details.runningGameId) {
                    lastRunningAppId = details.runningGameId;
                    rebuildPage();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // The list that arrived is now the truth about what this host has. Two things are
                // owed to it that the page cannot do: the launcher shortcuts, and remembering which
                // entries are new.
                for (NvApp app : appList) {
                    if (!knownAppIds.contains(app.getAppId())) {
                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);
                    }
                }
                for (NvApp gone : lastAppList) {
                    if (!containsAppId(appList, gone.getAppId())) {
                        shortcutHelper.disableAppShortcut(computer, gone, "App removed from PC");
                    }
                }

                knownAppIds.clear();
                for (NvApp app : appList) {
                    knownAppIds.add(app.getAppId());
                }

                lastAppList = new ArrayList<NvApp>(appList);
                rebuildPage();
            }
        });
    }

    private static boolean containsAppId(List<NvApp> list, int appId) {
        for (NvApp app : list) {
            if (app.getAppId() == appId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build the library page and hand it to the adapter.
     *
     * <p>This is the whole of the list's ordering and grouping: which entries each tab shows, where
     * Continue and PINNED sit, the section headings, the counts on the tabs. It is rebuilt rather
     * than patched whenever anything it depends on changes — the app list, the playtime record, a
     * pin, an entry moved between tabs, the running session — because a list assembled
     * incrementally is how a pinned game ends up filed under two headings.</p>
     */
    private void rebuildPage() {
        if (appGridAdapter == null) {
            return;
        }

        AppLibrary.Page page = AppLibrary.page(this, uuidString, lastAppList, currentCategory,
                showHiddenApps, hiddenAppIds, visibleAppIds(), lastRunningAppId);

        appGridAdapter.setPage(page, currentCategory);
        updateTabs(page);
        selectRowForPanel();
    }

    /**
     * Rebuild, then put the selection back on the same game.
     *
     * <p>Pinning and moving both re-sort the list, so the position the panel was showing means a
     * different entry afterwards. The desktop re-finds the cursor by id for exactly this reason.
     * Left by position, the panel would be showing whatever slid into that slot.</p>
     */
    private void rebuildPageKeeping(int appId) {
        rebuildPage();

        int pos = appGridAdapter.positionOfAppId(appId);
        AppObject obj = objectForPosition(pos);
        if (obj == null) {
            return;
        }
        selectedApp = obj;
        appGridAdapter.setSelectedPosition(pos);
        appGridAdapter.setSelectedApp(obj);

        AbsListView list = findViewById(R.id.fragmentView);
        if (list != null) {
            // Scrolls without taking focus, as the D-pad path does.
            list.setSelection(pos);
        }
        updateDetailPanel();
    }

    /**
     * What the list is showing right now, so an entry hidden a moment ago does not vanish out from
     * under the checkbox that just hid it.
     */
    private Set<Integer> visibleAppIds() {
        Set<Integer> ids = new HashSet<Integer>();
        AppLibrary.Page page = appGridAdapter.getPage();
        if (page != null) {
            for (AppLibrary.Row row : page.rows) {
                ids.add(row.appId);
            }
        }
        return ids;
    }

    /** The tabs, with the counts in their labels and the open one lit. */
    private void updateTabs(final AppLibrary.Page page) {
        // Two exceptions to the saved tab, both the desktop's own:
        //   · a host with Remote Monitor up is really being asked about its apps, not its games;
        //   · ALL with nothing in it is not a place to open a library.
        // Applying one rebuilds once, and then the condition is false and it settles — a list that
        // could not settle would flicker between tabs.
        if (!tabChosenByUser && AppLibrary.CAT_ALL.equals(currentCategory) && page.remoteMonitorActive) {
            currentCategory = AppLibrary.CAT_APPS;
            rebuildPage();
            return;
        }
        if (!tabChosenByUser && AppLibrary.CAT_ALL.equals(currentCategory) && page.allCount == 0
                && (page.gamesCount > 0 || page.appsCount > 0)) {
            currentCategory = (page.gamesCount > 0) ? AppLibrary.CAT_GAMES : AppLibrary.CAT_APPS;
            rebuildPage();
            return;
        }

        final TextView tabAll = findViewById(R.id.am_tab_all);
        if (tabAll == null) {
            return;     // the landscape layout is the only one with a list to label
        }

        tabAll.setText(getString(R.string.am_tab_all) + "  " + page.allCount);
        ((TextView) findViewById(R.id.am_tab_games)).setText(getString(R.string.am_tab_games) + "  " + page.gamesCount);
        ((TextView) findViewById(R.id.am_tab_apps)).setText(getString(R.string.am_tab_apps) + "  " + page.appsCount);

        lightTab(tabAll, AppLibrary.CAT_ALL.equals(currentCategory));
        lightTab(findViewById(R.id.am_tab_games), AppLibrary.CAT_GAMES.equals(currentCategory));
        lightTab(findViewById(R.id.am_tab_apps), AppLibrary.CAT_APPS.equals(currentCategory));

        tabAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCategory(AppLibrary.CAT_ALL);
            }
        });
        findViewById(R.id.am_tab_games).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCategory(AppLibrary.CAT_GAMES);
            }
        });
        findViewById(R.id.am_tab_apps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCategory(AppLibrary.CAT_APPS);
            }
        });
    }

    private void setCategory(String category) {
        if (category.equals(currentCategory)) {
            return;
        }
        currentCategory = category;
        tabChosenByUser = true;
        // The host remembers the tab, so coming back to this machine opens where it was left.
        PlaytimeManager.setLastTab(this, uuidString, category);
        rebuildPage();
        selectRowForPanel();
    }

    private void lightTab(TextView tab, boolean on) {
        tab.setBackgroundResource(on ? R.drawable.am_picker_pill_sel : R.drawable.am_picker_pill);
        tab.setTextColor(getResources().getColor(on ? R.color.am_on_accent : R.color.am_text2));
    }

    /** The entry at a list position, or null when that position is a section heading. */
    private AppObject objectForPosition(int position) {
        AppLibrary.Row row = appGridAdapter.rowAt(position);
        if (row == null) {
            return null;
        }
        return new AppObject(row);
    }

    /**
     * Keep the detail panel fed: the running game if there is one, otherwise the first entry.
     *
     * <p>Desktop parity — the preview is NEVER empty. And it is done here rather than left to the
     * click path because a page that has just been rebuilt (a tab changed, a pin toggled, a
     * session ended) has to re-find its selection: the position that showed the running game on
     * the old page does not mean the same entry on the new one.</p>
     */
    private void selectRowForPanel() {
        if (appGridAdapter == null || appGridAdapter.getPage() == null) {
            return;
        }

        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            AppObject candidate = objectForPosition(i);
            if (candidate != null && candidate.isRunning) {
                selectedApp = candidate;
                appGridAdapter.setSelectedPosition(i);
                appGridAdapter.setSelectedApp(candidate);
                updateDetailPanel();
                return;
            }
        }

        int first = appGridAdapter.positionOfFirstRow();
        AppObject obj = objectForPosition(first);
        if (obj != null) {
            selectedApp = obj;
            appGridAdapter.setSelectedPosition(first);
            appGridAdapter.setSelectedApp(obj);
        }
        updateDetailPanel();
    }

    /**
     * Landscape master-detail: refresh the detail panel from selectedApp.
     * No-op in portrait (the panel isn't inflated) and before the adapter
     * exists. The cover comes from the same cached asset loader the rows use.
     */
    /**
     * Gamepad button mappings for the picker (matches PromptBar glyphs):
     *   Y = Settings, B = back to hosts, A = launch the selected app.
     * Intercepted before the framework so the buttons can't fall through to
     * the grid and mis-launch.
     *
     * D-pad discipline (Nik's rule): Right must go from the app list
     * straight to Play/Resume/Stop in ONE press. The GridView normally eats
     * the first right-presses as internal column moves (single column =
     * invisible dead presses), so in gamepad mode we route the d-pad
     * ourselves: Up/Down walk the app rows, Right hops to the action
     * buttons, Left hops back to the list.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Classify FIRST (see PcView): without this, press 1 after entering
        // the picker is read one mode stale and leaks to the framework.
        if (event.getAction() == KeyEvent.ACTION_DOWN
                || event.getAction() == KeyEvent.ACTION_UP) {
            InputModeManager.get().notifyKeyEvent(event);
        }
        boolean gamepad = InputModeManager.get().getMode() == InputModeManager.Mode.GAMEPAD;
        // Swallow the ACTION_DOWN half of every owned key: if it reaches the
        // framework it moves Android's hidden geometric focus, painting extra
        // rings and drifting away from the owned model (two compasses).
        if (gamepad && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_Y:
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Fully owned: the list is driven via setSelection, the
                    // framework never sees a single half of these.
                    return true;
                default:
                    break;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP && gamepad) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_Y:
                    startActivity(new Intent(this, StreamSettings.class));
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                    finish();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    // A activates exactly what carries the ring: on an action
                    // button it clicks THAT button (A on Stop stops); on the
                    // list it launches the selected app.
                    activatePickerAction();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    movePickerFocus(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    movePickerFocus(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (padListOrButton >= 0) {
                        // From a button: back to the list.
                        padListOrButton = -1;
                        restoreListRing();
                        paintPickerFocus();
                    } else {
                        // List zone: scroll + select, fully owned.
                        movePadList(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    }
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Owned gamepad focus model (desktop FocusFrame parity) -------------
    // The picker has exactly two focus zones: the app list (-1) and the
    // visible action buttons (0..n-1 = Play/Resume/Stop in tab order). One
    // owned integer per zone state — we NEVER ask the framework where focus
    // is and NEVER call requestFocus in gamepad mode. Right/Left walk the
    // visible buttons strictly by index, so Play -> Resume -> Stop exactly,
    // clamped at both ends. Views are dumb paint targets via setActivated.

    /** -1 = app list, otherwise index into the visible action buttons. */
    private int padListOrButton = -1;
    /** Owned list index — the list is driven via setSelection, never focus. */
    private int padListIndex = 0;

    /** The Play / Resume / Stop row of the detail panel, visible ones only. */
    private View[] pickerActionTargets() {
        View play = findViewById(R.id.am_pick_play);
        View resume = findViewById(R.id.am_pick_resume);
        View stop = findViewById(R.id.am_pick_stop);
        java.util.List<View> targets = new java.util.ArrayList<>();
        if (play != null && play.getVisibility() == View.VISIBLE) targets.add(play);
        if (resume != null && resume.getVisibility() == View.VISIBLE) targets.add(resume);
        if (stop != null && stop.getVisibility() == View.VISIBLE) targets.add(stop);
        return targets.toArray(new View[0]);
    }

    private final java.util.List<View> padPaintedButtons = new java.util.ArrayList<>();

    /**
     * Paint the ring: on the focused app row (zone -1) or on the focused
     * action button (zone >= 0). Only one thing carries the ring at a time.
     */
    private void paintPickerFocus() {
        for (View v : padPaintedButtons) v.setActivated(false);
        padPaintedButtons.clear();
        if (padListOrButton < 0) {
            // List zone: adapter paints the row ring from focusedPosition.
            if (appGridAdapter != null) appGridAdapter.notifyDataSetChanged();
            // Evict any stray framework focus (stale ring painter).
            View f = getCurrentFocus();
            if (f != null) f.clearFocus();
            return;
        }
        // Button zone: exactly ONE ring on screen — strip the list's row ring
        // so the two channels can never light two things at once.
        if (appGridAdapter != null) {
            appGridAdapter.setFocusedPosition(AdapterView.INVALID_POSITION);
            appGridAdapter.notifyDataSetChanged();
        }
        // Evict stray framework focus in the button zone too.
        View f2 = getCurrentFocus();
        if (f2 != null) f2.clearFocus();
        View[] targets = pickerActionTargets();
        if (targets.length == 0) {
            padListOrButton = -1;
            return;
        }
        if (padListOrButton >= targets.length) padListOrButton = targets.length - 1;
        View t = targets[padListOrButton];
        t.setActivated(true);
        padPaintedButtons.add(t);
    }

    /**
     * Right/Left: from the list, Right enters the button row at the FIRST
     * button; on the buttons, index +/- 1 strictly through visible buttons,
     * clamped; Left at the first button drops back to the list; Left on the
     * list does nothing.
     */
    private void movePickerFocus(int delta) {
        View[] targets = pickerActionTargets();
        if (padListOrButton < 0) {
            if (delta > 0 && targets.length > 0) {
                padListOrButton = 0;
                paintPickerFocus();
            }
            return;
        }
        if (targets.length == 0) {
            padListOrButton = -1;
            return;
        }
        int next = padListOrButton + delta;
        if (next < 0) {
            padListOrButton = -1;          // Left off the first button: back to the list
            restoreListRing();
            paintPickerFocus();
            return;
        }
        if (next > targets.length - 1) next = targets.length - 1;  // clamp right end
        if (next != padListOrButton) {
            padListOrButton = next;
            paintPickerFocus();
        }
    }

    /** A activates exactly what carries the ring. */
    private void activatePickerAction() {
        if (padListOrButton < 0) {
            startSelectedApp();
            return;
        }
        View[] targets = pickerActionTargets();
        if (targets.length == 0) {
            startSelectedApp();
            return;
        }
        if (padListOrButton >= targets.length) padListOrButton = targets.length - 1;
        targets[padListOrButton].performClick();
    }

    /** Re-arm the list's row ring on the owned list index. */
    private void restoreListRing() {
        if (appGridAdapter != null) {
            if (padListIndex < 0 || padListIndex >= appGridAdapter.getCount()) {
                padListIndex = 0;
            }
            appGridAdapter.setFocusedPosition(padListIndex);
        }
    }

    /** Owned list scroll: move, clamp, repaint. The framework is not involved. */
    private void movePadList(int delta) {
        if (appGridAdapter == null || appGridAdapter.getCount() == 0) return;
        int next = padListIndex + delta;
        if (next < 0) next = 0;
        if (next > appGridAdapter.getCount() - 1) next = appGridAdapter.getCount() - 1;
        if (next == padListIndex) return;
        padListIndex = next;
        AbsListView list = findViewById(R.id.fragmentView);
        if (list != null) list.setSelection(next);   // scrolls, no focus
        selectedApp = objectForPosition(next);
        appGridAdapter.setSelectedApp(selectedApp);
        appGridAdapter.setFocusedPosition(next);
        appGridAdapter.notifyDataSetChanged();
        updateDetailPanel();
    }

    /** Seed the ring on the list when entering the picker in gamepad mode. */
    private void seedPickerFocus() {
        padListOrButton = -1;
        AbsListView list = findViewById(R.id.fragmentView);
        if (list != null) {
            int pos = list.getSelectedItemPosition();
            if (pos != AdapterView.INVALID_POSITION) padListIndex = pos;
        }
        restoreListRing();
        paintPickerFocus();
    }

        /** Paint the focused app's cover art, blurred, behind the picker (desktop
     *  CoverAmbient parity). The same selection the detail panel uses drives it,
     * and the same host /appasset art the rows use is the source — so the first
     *  fetch is the only one per app.
     */
    private void updateAmbient() {

if (ambientView == null) {
            ambientView = findViewById(R.id.am_pick_ambient);
        }
        if (ambientView == null || computer == null) return;
        if (appGridAdapter == null || selectedApp == null || managerBinder == null) {
            ambientView.setVisibility(View.GONE);
            return;
        }
        if (ambientArtwork == null) {
            ambientArtwork = new AppAmbientArtwork(
                    AppView.this, computer, managerBinder.getUniqueId());
        }
        ambientArtwork.show(selectedApp.app, ambientView);
    }

        private void updateDetailPanel() {
        // Ambient backdrop follows the same selection (desktop CoverAmbient parity).
        updateAmbient();
        View detail = findViewById(R.id.am_pick_detail);
        if (detail == null || appGridAdapter == null) {
            return;
        }
        // If the selected app vanished from the list (host-side removal),
        // drop the selection instead of showing a stale panel.
        if (selectedApp != null
                && appGridAdapter.positionOfAppId(selectedApp.app.getAppId()) == AdapterView.INVALID_POSITION) {
            // Gone from this page — hidden on another device, moved to the other tab, or dropped by
            // the host. Drop the selection rather than leave a stale panel on screen.
            selectedApp = null;
            appGridAdapter.setSelectedApp(null);
            appGridAdapter.setSelectedPosition(AdapterView.INVALID_POSITION);
            appGridAdapter.notifyDataSetChanged();
        }
        if (selectedApp == null) {
            detail.setVisibility(View.GONE);
            return;
        }
        ImageView cover = findViewById(R.id.am_pick_cover);
        TextView title = findViewById(R.id.am_pick_title);
        TextView running = findViewById(R.id.am_pick_running);
        View resumeBtn = findViewById(R.id.am_pick_resume);
        View stopBtn = findViewById(R.id.am_pick_stop);
        if (cover != null) {
            appGridAdapter.populateCover(selectedApp.app, cover);
        }
        if (title != null) {
            title.setText(selectedApp.app.getAppName());
        }
        boolean isRunning = lastRunningAppId != 0
                && lastRunningAppId == selectedApp.app.getAppId();
        if (running != null) {
            running.setVisibility(isRunning ? View.VISIBLE : View.GONE);
        }
        if (resumeBtn != null) {
            resumeBtn.setVisibility(isRunning ? View.VISIBLE : View.GONE);
        }
        if (stopBtn != null) {
            stopBtn.setVisibility(isRunning ? View.VISIBLE : View.GONE);
        }
        detail.setVisibility(View.VISIBLE);
    }

    private void startSelectedApp() {
        AppObject target = selectedApp;
        if (target == null) {
            // Portrait / fresh entry: fall back to the grid's focused row.
            AbsListView grid = findViewById(R.id.fragmentView);
            if (grid != null) {
                int pos = grid.getSelectedItemPosition();
                if (pos != AdapterView.INVALID_POSITION && pos < appGridAdapter.getCount()) {
                    target = objectForPosition(pos);
                }
            }
        }
        if (target != null && computer != null) {
            ServerHelper.doStartWithCurtain(AppView.this, target.app, computer, managerBinder);
        }
    }

    private void stopSelectedApp() {
        if (selectedApp == null) {
            return;
        }
        final AppObject app = selectedApp;
        UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
            @Override
            public void run() {
                suspendGridUpdates = true;
                ServerHelper.doQuit(AppView.this, computer,
                        app.app, managerBinder, new Runnable() {
                    @Override
                    public void run() {
                        // Trigger a poll immediately
                        suspendGridUpdates = false;
                        if (poller != null) {
                            poller.pollNow();
                        }
                    }
                });
            }
        }, null);
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(appGridAdapter);

        // Hide the d-pad scroll bar in gamepad mode (controller-driven screen).
        if (InputModeManager.get().getMode() == InputModeManager.Mode.GAMEPAD) {
            listView.setVerticalScrollBarEnabled(false);
        }

        // Gamepad parity with desktop: when a controller drives the picker,
        // FOCUS is selection — the detail panel and blue row band follow the
        // D-pad focus live, so what you see highlighted is what A launches.
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int pos, long id) {
                // Ring follows focus in every mode (desktop FocusFrame parity).
                appGridAdapter.setFocusedPosition(pos);
                if (InputModeManager.get().getMode() != InputModeManager.Mode.GAMEPAD) {
                    return;
                }
                if (findViewById(R.id.appFragmentContainer) == null) {
                    return; // portrait: no master-detail pane
                }
                AppObject app = objectForPosition(pos);
                if (app == null || app == selectedApp) {
                    return;
                }
                selectedApp = app;
                appGridAdapter.setSelectedApp(app);
                appGridAdapter.notifyDataSetChanged();
                updateDetailPanel();
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
                appGridAdapter.setFocusedPosition(AdapterView.INVALID_POSITION);
            }
        });

        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                AppObject app = objectForPosition(pos);

                // Landscape master-detail (desktop parity): a tap selects,
                // updates the detail panel AND launches immediately — exactly
                // one interaction from list to stream. Tapping the app that is
                // already running opens the context menu instead (Quit /
                // Resume) so a stray tap can't re-launch it. Portrait keeps
                // the classic tap-to-start behaviour.
                if (findViewById(R.id.am_pick_detail) != null) {
                    selectedApp = app;
                    appGridAdapter.setSelectedApp(app);
                    appGridAdapter.notifyDataSetChanged();
                    updateDetailPanel();

                    if (lastRunningAppId == app.app.getAppId()) {
                        // Already running: menu offers Resume / Quit
                        openContextMenu(arg1);
                    } else {
                        // Desktop AppsScreen.onClicked(): select + launch
                        startSelectedApp();
                    }
                    return;
                }

                // Only open the context menu if something is running, otherwise start it
                if (lastRunningAppId != 0) {
                    openContextMenu(arg1);
                } else {
                    ServerHelper.doStartWithCurtain(AppView.this, app.app, computer, managerBinder);
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
        listView.requestFocus();

        // Detail panel actions (landscape only — null-safe everywhere else).
        // Reselect whatever is running so the panel starts populated.
        View playBtn = findViewById(R.id.am_pick_play);
        if (playBtn != null) {
            playBtn.setOnFocusChangeListener((v, hasFocus) -> v.setActivated(hasFocus));
            playBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startSelectedApp();
                }
            });
        }
        View resumeBtn = findViewById(R.id.am_pick_resume);
        if (resumeBtn != null) {
            resumeBtn.setOnFocusChangeListener((v, hasFocus) -> v.setActivated(hasFocus));
            resumeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Resume is the same as start for us (desktop parity)
                    startSelectedApp();
                }
            });
        }
        View stopBtn = findViewById(R.id.am_pick_stop);
        if (stopBtn != null) {
            stopBtn.setOnFocusChangeListener((v, hasFocus) -> v.setActivated(hasFocus));
            stopBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopSelectedApp();
                }
            });
        }
        // Footer keycaps (landscape): S opens settings, Esc returns to hosts —
        // same flows the hosts screen wires, never dead labels.
        View keySettings = findViewById(R.id.am_pick_key_settings);
        if (keySettings != null) {
            keySettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(AppView.this, StreamSettings.class));
                }
            });
        }
        View keyHosts = findViewById(R.id.am_pick_key_hosts);
        if (keyHosts != null) {
            keyHosts.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        // Input-aware prompt bar (picker footer): pills re-render for
        // touch / gamepad / keyboard — desktop parity.
        promptBar = new io.github.onaiaku.artmoon.artlight.PromptBar(this);
        promptBar.registerById(R.id.am_pick_kb_settings, R.id.am_pick_key_settings, "settings");
        promptBar.registerById(R.id.am_pick_kb_hosts, R.id.am_pick_key_hosts, "hosts");
        promptBar.attach();
        // Desktop parity: the preview is NEVER empty — the running game if there is one,
        // otherwise the first entry, so the panel is populated from the moment the screen opens.
        selectRowForPanel();
        updateDetailPanel();
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        /**
         * From a row of the library page. The panel speaks AppObject and the list speaks
         * AppLibrary.Row; this is the one place they meet, and it is derived — never a second
         * copy of the truth that could drift.
         */
        public AppObject(AppLibrary.Row row) {
            if (row == null || row.app == null) {
                throw new IllegalArgumentException("row must carry an app");
            }
            this.app = row.app;
            this.isRunning = row.running;
            this.isHidden = row.hidden;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }

    private static String codecLabel(PreferenceConfiguration.FormatOption fmt) {
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_AV1) return "AV1";
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_HEVC) return "HEVC";
        if (fmt == PreferenceConfiguration.FormatOption.FORCE_H264) return "H.264";
        return "Auto";
    }

    private static String audioLabel(io.github.onaiaku.artmoon.nvstream.jni.MoonBridge.AudioConfiguration audio) {
        if (audio == io.github.onaiaku.artmoon.nvstream.jni.MoonBridge.AUDIO_CONFIGURATION_51_SURROUND) return "5.1";
        if (audio == io.github.onaiaku.artmoon.nvstream.jni.MoonBridge.AUDIO_CONFIGURATION_71_SURROUND) return "7.1";
        return "Stereo";
    }
}
