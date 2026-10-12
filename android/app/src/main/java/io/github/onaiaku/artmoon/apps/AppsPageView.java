package io.github.onaiaku.artmoon.apps;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.onaiaku.artmoon.ArtMoonActivity;
import io.github.onaiaku.artmoon.OrientationHelper;
import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.computers.ComputerManagerListener;
import io.github.onaiaku.artmoon.computers.ComputerManagerService;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.nvstream.http.NvHTTP;
import io.github.onaiaku.artmoon.preferences.PreferenceConfiguration;
import io.github.onaiaku.artmoon.settings.PlaytimeManager;
import io.github.onaiaku.artmoon.utils.ServerHelper;
import io.github.onaiaku.artmoon.utils.UiHelper;

/**
 * The app picker — the desktop's page (AppsScreen.qml), on the phone.
 *
 * <p>This is its own screen. It is not the old app list with new code threaded through it: the
 * layout is {@code apps_page.xml}, the rows are {@link AppsRowAdapter}, and the library underneath is
 * {@link AppLibrary}, which is AppModel ported. Nothing here was written by editing the old picker.
 *
 * <p>What it borrows is the spine every screen in this app needs and none of them own — binding to
 * the computer manager, polling for the host's state, launching a session. That is not the old app
 * picker; it is how an activity in this app talks to a host at all.
 *
 * <p>The desktop's grammar, kept: one thing in the spotlight, everything else in a strip, and the
 * action row saying what A will do.
 */
public class AppsPageView extends ArtMoonActivity {

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";
    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ComputerDetails computer;
    private String uuidString;
    private String hostName;

    private boolean inForeground;
    private boolean showHiddenApps;

    private String category = AppLibrary.CAT_ALL;
    private int runningAppId = 0;
    private int lastRunningAppId = 0;
    private String lastRawApplist = "";

    private final List<NvApp> appList = new ArrayList<NvApp>();
    private final Set<Integer> hiddenAppIds = new HashSet<Integer>();
    private final Set<Integer> keepVisible = new HashSet<Integer>();

    private AppsRowAdapter adapter;
    private ListView grid;
    private AppLibrary.Page page;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder) binder);

            new Thread() {
                @Override
                public void run() {
                    localBinder.waitForReady();

                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    adapter = new AppsRowAdapter(AppsPageView.this, computer, localBinder.getUniqueId());

                    managerBinder = localBinder;

                    // The page is filled from the cache first so it is never empty on the way in,
                    // then from the host's own answer.
                    if (computer.rawAppList != null && !computer.rawAppList.isEmpty()) {
                        lastRawApplist = computer.rawAppList;
                        try {
                            appList.clear();
                            appList.addAll(NvHTTP.getAppListByReader(new StringReader(lastRawApplist)));
                        } catch (XmlPullParserException | IOException e) {
                            e.printStackTrace();
                        }
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }
                            fillPage();
                        }
                    });

                    startComputerUpdates();
                    requestStores(localBinder.getUniqueId());
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OrientationHelper.applyOrientation(this);
        inForeground = true;

        UiHelper.setLocale(this);
        setContentView(R.layout.apps_page);
        UiHelper.notifyNewRootView(this);

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);
        hostName = getIntent().getStringExtra(NAME_EXTRA);
        if (hostName == null) {
            hostName = "";
        }

        SharedPreferences hiddenPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String id : hiddenPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(id));
        }

        // The tab it opens on is the host's own remembered one, not always ALL.
        String savedTab = PlaytimeManager.lastTabOn(this, uuidString);
        if (savedTab != null && !savedTab.isEmpty()) {
            category = savedTab;
        }

        TextView name = (TextView) findViewById(R.id.am_host_name);
        name.setText(hostName.toUpperCase());

        findViewById(R.id.am_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        fillConfigLine();

        wireTabs();

        grid = (ListView) findViewById(R.id.appGrid);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (adapter.getItemViewType(position) == 1) {
                    return;
                }
                selectRow(position);
                launchSelected();
            }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (adapter != null) {
                    adapter.setListHasFocus(hasFocus);
                }
            }
        });

        wireHero();

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                android.content.Context.BIND_AUTO_CREATE);
    }

    /** The host's address and the settings a session would go out with, in two named groups. */
    private void fillConfigLine() {
        String address = "";
        if (computer != null) {
            address = computer.activeAddress != null ? computer.activeAddress.address
                    : computer.remoteAddress != null ? computer.remoteAddress.address
                    : computer.localAddress != null ? computer.localAddress.address : "";
        }
        TextView host = (TextView) findViewById(R.id.am_cfg_host);
        host.setText(address.isEmpty() ? hostName : address);

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        StringBuilder sb = new StringBuilder();
        sb.append(prefs.width).append("×").append(prefs.height);
        sb.append("  ·  ").append(prefs.fps).append(" FPS");
        sb.append("  ·  ").append(prefs.bitrate).append(" Mbps");
        if (prefs.enableHdr) {
            sb.append("  ·  HDR");
        }
        ((TextView) findViewById(R.id.am_cfg_stream)).setText(sb.toString());
    }

    private void wireTabs() {
        final TextView all = (TextView) findViewById(R.id.am_tab_all);
        final TextView games = (TextView) findViewById(R.id.am_tab_games);
        final TextView apps = (TextView) findViewById(R.id.am_tab_apps);

        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String tab = v == games ? AppLibrary.CAT_GAMES
                        : v == apps ? AppLibrary.CAT_APPS : AppLibrary.CAT_ALL;
                setTab(tab, true);
            }
        };
        all.setOnClickListener(click);
        games.setOnClickListener(click);
        apps.setOnClickListener(click);
    }

    /**
     * Moves to a tab. The counts were never printed beside the labels — the desktop's tabs are three
     * words and an underline — so there is nothing here to renumber.
     */
    private void setTab(String tab, boolean byUser) {
        category = tab;
        if (byUser) {
            PlaytimeManager.setLastTab(this, uuidString, tab);
        }
        if (grid != null) {
            grid.setSelection(0);
        }
        fillPage();
    }

    private void wireHero() {
        findViewById(R.id.am_hero_launch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSelected();
            }
        });
    }

    private void selectRow(int position) {
        if (adapter == null || adapter.getItemViewType(position) == 1) {
            return;
        }
        adapter.setFocused(position);
        updateHero();
    }

    /** The row under the cursor, which is what the spotlight is about. */
    private AppLibrary.Row selectedRow() {
        if (adapter == null || grid == null) {
            return null;
        }
        return adapter.rowAt(grid.getSelectedItemPosition() >= 0
                ? grid.getSelectedItemPosition() : adapterPositionOrZero());
    }

    private int adapterPositionOrZero() {
        return 0;
    }

    private void fillPage() {
        if (isFinishing()) {
            return;
        }
        page = AppLibrary.page(this, uuidString, appList, category, showHiddenApps,
                hiddenAppIds, keepVisible, runningAppId);
        if (adapter == null) {
            adapter = new AppsRowAdapter(this, computer, "");
        }
        adapter.setPage(page, category);
        grid.setAdapter(adapter);
        grid.setVisibility(page.rows.isEmpty() ? View.GONE : View.VISIBLE);

        TextView empty = (TextView) findViewById(R.id.am_empty);
        empty.setVisibility(page.rows.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(emptyText());

        paintTabs();
        updateHero();

        // The running pill belongs to whichever row holds the session, wherever it sits in the list.
        AppLibrary.Row running = null;
        for (AppLibrary.Row row : page.rows) {
            if (row.running) {
                running = row;
                break;
            }
        }
        if (running != null) {
            ((TextView) findViewById(R.id.am_key_stop)).setText("X  Stop");
        }
    }

    /** Three different strings by tab, as the desktop has them. */
    private String emptyText() {
        if (AppLibrary.CAT_GAMES.equals(category)) {
            return "No games to show — some may be hidden on the host";
        }
        if (AppLibrary.CAT_APPS.equals(category)) {
            return "No apps to show — some may be hidden on the host";
        }
        return "Nothing to show — some entries may be hidden on the host";
    }

    private void paintTabs() {
        final String[] tabs = {AppLibrary.CAT_ALL, AppLibrary.CAT_GAMES, AppLibrary.CAT_APPS};
        final int[] ids = {R.id.am_tab_all, R.id.am_tab_games, R.id.am_tab_apps};
        for (int i = 0; i < ids.length; i++) {
            TextView tab = (TextView) findViewById(ids[i]);
            boolean on = tabs[i].equals(category);
            tab.setSelected(on);
            tab.setTextColor(getResources().getColor(
                    on ? R.color.am_text_primary : R.color.am_text2));
            tab.setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
    }

    /**
     * The spotlight: the entry the cursor is on, as one centred stack — cover, name, what state it
     * is in, what has been played on it, and the verbs.
     */
    private void updateHero() {
        AppLibrary.Row row = selectedRow();
        ImageView cover = (ImageView) findViewById(R.id.am_hero_cover);
        TextView title = (TextView) findViewById(R.id.am_hero_title);
        TextView meta = (TextView) findViewById(R.id.am_hero_meta);
        TextView figures = (TextView) findViewById(R.id.am_hero_figures);
        TextView launch = (TextView) findViewById(R.id.am_hero_launch);
        TextView stop = (TextView) findViewById(R.id.am_hero_stop);

        if (row == null) {
            cover.setImageDrawable(null);
            title.setText("");
            meta.setVisibility(View.GONE);
            figures.setVisibility(View.GONE);
            stop.setVisibility(View.GONE);
            launch.setText("Play");
            return;
        }

        title.setText(row.name);

        boolean running = row.running;

        StringBuilder parts = new StringBuilder();
        if (running) {
            parts.append(getString(R.string.am_pick_running));
        }
        if (row.overridden) {
            if (parts.length() > 0) {
                parts.append("  ·  ");
            }
            parts.append(getString(R.string.am_meta_custom_settings));
        }
        meta.setText(parts.toString());
        meta.setVisibility(parts.length() == 0 ? View.GONE : View.VISIBLE);
        meta.setTextColor(getResources().getColor(running ? R.color.am_online : R.color.am_text2));

        // The APPS tab carries no hours: the figures belong to the game, and an entry that is not
        // the game must not wear them.
        if (!row.isApp && row.playtime != null && row.playtime.valid) {
            String played = PlaytimeManager.formatDuration(row.playtime.totalSeconds);
            String sessions = row.playtime.sessionCount == 1
                    ? "1 session" : row.playtime.sessionCount + " sessions";
            figures.setText(played + "  ·  " + sessions);
            figures.setVisibility(View.VISIBLE);
        }
        else {
            figures.setVisibility(View.GONE);
        }

        // The verb: Resume for a session already up, Open for an app, Play for a game.
        launch.setText(running ? "Resume" : row.isApp ? "Open" : "Play");
        // Stop is not "Quit game": the entry holding the session is sometimes the Desktop.
        stop.setVisibility(running ? View.VISIBLE : View.GONE);

        if (adapter != null) {
            final ImageView heroCover = cover;
            final NvApp heroApp = row.app;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    heroCover.setContentDescription(heroApp.getAppName());
                }
            });
        }
    }

    private void launchSelected() {
        AppLibrary.Row row = selectedRow();
        if (row == null || computer == null || managerBinder == null) {
            return;
        }
        ServerHelper.doStartWithCurtain(AppsPageView.this, row.app, computer, managerBinder);
    }

    private void startComputerUpdates() {
        if (managerBinder == null || !inForeground) {
            return;
        }
        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }
                if (details.state == ComputerDetails.State.OFFLINE) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(AppsPageView.this, getResources().getText(
                                    R.string.lost_connection), Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });
                    return;
                }

                final boolean sameList = details.rawAppList == null
                        || details.rawAppList.equals(lastRawApplist);
                final int running = details.runningGameId;

                if (sameList) {
                    if (running != runningAppId) {
                        runningAppId = running;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                fillPage();
                            }
                        });
                    }
                    return;
                }

                lastRawApplist = details.rawAppList;
                final List<NvApp> fetched;
                try {
                    fetched = NvHTTP.getAppListByReader(new StringReader(details.rawAppList));
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        appList.clear();
                        appList.addAll(fetched);
                        runningAppId = running;
                        lastRunningAppId = running;
                        fillPage();
                    }
                });
            }
        });
    }

    /**
     * The store each entry came from, in words, from the host itself. Best effort: an older host, or
     * one that is not ArtLight, simply has nothing to say and the meta line goes without it.
     */
    private void requestStores(final String uniqueId) {
        if (computer == null) {
            return;
        }
        final String address = computer.activeAddress != null ? computer.activeAddress.address
                : computer.remoteAddress != null ? computer.remoteAddress.address
                : computer.localAddress != null ? computer.localAddress.address : null;
        if (TextUtils.isEmpty(address)) {
            return;
        }
        new Thread() {
            @Override
            public void run() {
                io.github.onaiaku.artmoon.artlight.ArtLightBridge bridge =
                        new io.github.onaiaku.artmoon.artlight.ArtLightBridge(AppsPageView.this);
                bridge.requestAppStores(address,
                        new io.github.onaiaku.artmoon.artlight.ArtLightBridge.ResponseCallback() {
                            @Override
                            public void onResult(final String response) {
                                if (response == null || response.isEmpty()) {
                                    return;
                                }
                                try {
                                    org.json.JSONObject obj = new org.json.JSONObject(response);
                                    final java.util.HashMap<String, String> map =
                                            new java.util.HashMap<String, String>();
                                    java.util.Iterator<String> keys = obj.keys();
                                    while (keys.hasNext()) {
                                        String key = keys.next();
                                        map.put(key, obj.optString(key));
                                    }
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (adapter != null) {
                                                adapter.setStores(map);
                                            }
                                        }
                                    });
                                } catch (Exception e) {
                                    io.github.onaiaku.artmoon.LimeLog.info(
                                            "APPS page: store map parse failed: " + e.getMessage());
                                }
                            }
                        });
            }
        }.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // PgUp/PgDn move between the tabs, as they do on the desktop.
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            setTab(AppLibrary.CAT_ALL.equals(category) ? AppLibrary.CAT_APPS
                    : AppLibrary.CAT_GAMES.equals(category) ? AppLibrary.CAT_ALL : AppLibrary.CAT_GAMES,
                    true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            setTab(AppLibrary.CAT_ALL.equals(category) ? AppLibrary.CAT_GAMES
                    : AppLibrary.CAT_GAMES.equals(category) ? AppLibrary.CAT_APPS : AppLibrary.CAT_ALL,
                    true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        if (managerBinder != null) {
            startComputerUpdates();
        }
    }

    @Override
    protected void onPause() {
        inForeground = false;
        if (managerBinder != null) {
            managerBinder.stopPolling();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (adapter != null) {
            adapter.cancelPulses();
        }
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
        super.onDestroy();
    }
}
