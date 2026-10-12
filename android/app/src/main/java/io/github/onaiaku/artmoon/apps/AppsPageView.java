package io.github.onaiaku.artmoon.apps;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
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
import io.github.onaiaku.artmoon.grid.AppAmbientArtwork;
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

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int HIDE_APP_ID = 7;
    private final static int PIN_APP_ID = 8;
    private final static int MOVE_APP_ID = 9;

    /** True once the user has picked a tab themselves; a rebuild must not put it back. */
    private boolean tabChosenByUser = false;

    private String category = AppLibrary.CAT_ALL;
    private int runningAppId = 0;
    private int lastRunningAppId = 0;
    private String lastRawApplist = "";

    private final List<NvApp> appList = new ArrayList<NvApp>();
    private final Set<Integer> hiddenAppIds = new HashSet<Integer>();
    private final Set<Integer> keepVisible = new HashSet<Integer>();

    private AppAmbientArtwork ambientArtwork;
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

        paintChips();

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

        registerForContextMenu(grid);

        wireHero();

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                android.content.Context.BIND_AUTO_CREATE);
    }

    /**
     * The chip row beside the host name. The desktop has four chips and this app can honestly
     * report one of them:
     *
     * <ul>
     *   <li>Online — always. The page is only open on a host that answered.</li>
     *   <li>The profile — the Android client has no per-host stream profiles, so there is nothing
     *       to name. The desktop does the same when a host has none: naming the only possible state
     *       tells the user nothing.</li>
     *   <li>Tailscale — this client does not know whether the host is reached over a Tailscale
     *       clone address.</li>
     *   <li>The link — the link-speed match is not ported, so there is no amber state to report and
     *       no honest number to put in it.</li>
     * </ul>
     *
     * <p>Three chips that would be guesses are hidden rather than filled with something plausible.
     */
    private void paintChips() {
        findViewById(R.id.am_chip_online).setVisibility(View.VISIBLE);
        findViewById(R.id.am_chip_profile).setVisibility(View.GONE);
        findViewById(R.id.am_chip_tailscale).setVisibility(View.GONE);
        findViewById(R.id.am_chip_link).setVisibility(View.GONE);
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
            tabChosenByUser = true;
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

        // Stop, on the hero and on the foot — both quit the session the spotlight is showing, and
        // both ask first, as every quit in this app does.
        View.OnClickListener stop = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLibrary.Row row = selectedRow();
                if (row == null || !row.running) {
                    return;
                }
                quit(row);
            }
        };
        findViewById(R.id.am_hero_stop).setOnClickListener(stop);
        findViewById(R.id.am_key_stop).setOnClickListener(stop);
    }

    /**
     * Quit the session {@code row} is holding. It is "Stop", not "Quit game": the entry holding the
     * session is sometimes the Desktop.
     */
    private void quit(final AppLibrary.Row row) {
        UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
            @Override
            public void run() {
                // The listener is already streaming this host's state, so the pill goes when the
                // host says the session is over — no second poller, no second source of truth.
                ServerHelper.doQuit(AppsPageView.this, computer, row.app, managerBinder, null);
            }
        }, null);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        final AppLibrary.Row row = adapter != null ? adapter.rowAt(info.position) : null;
        if (row == null) {
            return;
        }

        menu.setHeaderTitle(row.name);

        if (runningAppId != 0) {
            if (runningAppId == row.appId) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
            }
        }

        // Only offer hiding when it is not the running entry, or when it is already hidden.
        if (runningAppId != row.appId || row.hidden) {
            MenuItem hide = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hide.setCheckable(true);
            hide.setChecked(row.hidden);
        }

        // Pin and move are for the entries the user owns: a host control (Remote Input, Resume, the
        // running-game copy) is not a game to pin and cannot be moved between tabs.
        if (row.movable) {
            MenuItem pin = menu.add(Menu.NONE, PIN_APP_ID, 6,
                    getResources().getString(row.pinned ? R.string.applist_menu_unpin : R.string.applist_menu_pin));
            pin.setCheckable(true);
            pin.setChecked(row.pinned);

            menu.add(Menu.NONE, MOVE_APP_ID, 7,
                    getResources().getString(row.isApp ? R.string.applist_menu_move_games : R.string.applist_menu_move_apps));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        if (info == null || adapter == null) {
            return super.onContextItemSelected(item);
        }
        final AppLibrary.Row row = adapter.rowAt(info.position);
        if (row == null) {
            return super.onContextItemSelected(item);
        }

        switch (item.getItemId()) {
            case PIN_APP_ID:
                PlaytimeManager.setPinned(this, uuidString, row.name, !row.pinned);
                rebuildPageKeeping(row.appId);
                return true;

            case MOVE_APP_ID:
                // The desktop sets its "chosen by the user" flag BEFORE the call. Moving an entry is
                // the strongest statement of which tab is wanted, and it has to survive the rebuild.
                tabChosenByUser = true;
                PlaytimeManager.setCategoryOverride(this, uuidString, row.name, !row.isApp, false);
                rebuildPageKeeping(row.appId);
                return true;

            case START_OR_RESUME_ID:
                // Resume is the same as start for us.
                ServerHelper.doStartWithCurtain(this, row.app, computer, managerBinder);
                return true;

            case START_WITH_QUIT:
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doStartWithCurtain(AppsPageView.this, row.app, computer, managerBinder);
                    }
                }, null);
                return true;

            case QUIT_ID:
                quit(row);
                return true;

            case HIDE_APP_ID:
                if (item.isChecked()) {
                    hiddenAppIds.remove(row.appId);
                }
                else {
                    hiddenAppIds.add(row.appId);
                }
                updateHiddenApps();
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    /** Keeps the entry the user just acted on under the cursor when the list is rebuilt. */
    private void rebuildPageKeeping(int appId) {
        fillPage();
        int position = adapter == null ? -1 : adapter.positionOfAppId(appId);
        if (position >= 0) {
            grid.setSelection(position);
            adapter.setFocused(position);
            updateHero();
        }
    }

    private void updateHiddenApps() {
        SharedPreferences.Editor editor = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).edit();
        Set<String> ids = new HashSet<String>();
        for (Integer id : hiddenAppIds) {
            ids.add(Integer.toString(id));
        }
        editor.putStringSet(uuidString, ids);
        editor.apply();
        fillPage();
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

        cover.setContentDescription(row.name);

        // The page paints its own floor when it has artwork — the blurred, desaturated cover of
        // whatever is in the spotlight (AppsScreen.qml:748). It follows the spotlight, not the
        // moment the page opened.
        showAmbient(row);
    }

    private void showAmbient(AppLibrary.Row row) {
        ImageView ambient = (ImageView) findViewById(R.id.am_page_ambient);
        if (ambient == null || computer == null || managerBinder == null || row == null) {
            if (ambient != null) {
                ambient.setVisibility(View.GONE);
            }
            return;
        }
        if (ambientArtwork == null) {
            ambientArtwork = new AppAmbientArtwork(this, computer, managerBinder.getUniqueId());
        }
        ambientArtwork.show(row.app, ambient);
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
