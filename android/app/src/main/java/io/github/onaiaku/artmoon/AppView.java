package io.github.onaiaku.artmoon;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;

import io.github.onaiaku.artmoon.computers.ComputerManagerListener;
import io.github.onaiaku.artmoon.computers.ComputerManagerService;
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
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
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
        OrientationHelper.lockPortraitOnPhones(this);

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
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

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
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
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
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                    updateDetailPanel();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, "App removed from PC");
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
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
        selectedApp = (AppObject) appGridAdapter.getItem(next);
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

        private void updateDetailPanel() {
        View detail = findViewById(R.id.am_pick_detail);
        if (detail == null || appGridAdapter == null) {
            return;
        }
        // If the selected app vanished from the list (host-side removal),
        // drop the selection instead of showing a stale panel.
        if (selectedApp != null) {
            boolean stillThere = false;
            for (int i = 0; i < appGridAdapter.getCount(); i++) {
                if (appGridAdapter.getItem(i) == selectedApp) {
                    stillThere = true;
                    break;
                }
            }
            if (!stillThere) {
                selectedApp = null;
                appGridAdapter.setSelectedApp(null);
                appGridAdapter.notifyDataSetChanged();
            }
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
                    target = (AppObject) appGridAdapter.getItem(pos);
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
                AppObject app = (AppObject) appGridAdapter.getItem(pos);
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
                AppObject app = (AppObject) appGridAdapter.getItem(pos);

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
        if (selectedApp == null) {
            // Desktop parity: the preview is NEVER empty. Prefer the running
            // app, otherwise default to the first app in the list so the
            // detail panel is populated from the moment the screen opens.
            for (int i = 0; i < appGridAdapter.getCount(); i++) {
                AppObject candidate = (AppObject) appGridAdapter.getItem(i);
                if (candidate.isRunning) {
                    selectedApp = candidate;
                    appGridAdapter.setSelectedApp(candidate);
                    break;
                }
            }
            if (selectedApp == null && appGridAdapter.getCount() > 0) {
                AppObject first = (AppObject) appGridAdapter.getItem(0);
                selectedApp = first;
                appGridAdapter.setSelectedApp(first);
            }
        }
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
