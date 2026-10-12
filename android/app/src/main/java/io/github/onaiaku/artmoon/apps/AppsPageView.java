package io.github.onaiaku.artmoon.apps;

import android.content.ComponentName;
import android.content.Intent;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.onaiaku.artmoon.ArtMoonActivity;
import io.github.onaiaku.artmoon.OrientationHelper;
import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.computers.ComputerManagerListener;
import io.github.onaiaku.artmoon.artlight.PromptBar;
import io.github.onaiaku.artmoon.artlight.ArtLightBridge;
import io.github.onaiaku.artmoon.artlight.HostMetricsPoller;
import io.github.onaiaku.artmoon.grid.AppAmbientArtwork;
import io.github.onaiaku.artmoon.computers.ComputerManagerService;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.nvstream.http.NvHTTP;
import io.github.onaiaku.artmoon.preferences.PreferenceConfiguration;
import io.github.onaiaku.artmoon.preferences.StreamSettings;
import io.github.onaiaku.artmoon.settings.PlaytimeManager;
import io.github.onaiaku.artmoon.utils.ShortcutHelper;
import io.github.onaiaku.artmoon.utils.CacheHelper;
import io.github.onaiaku.artmoon.utils.ServerHelper;
import io.github.onaiaku.artmoon.utils.StoreMarks;
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

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ComputerDetails computer;
    private String uuidString;
    private String hostName;

    private boolean inForeground;

    /** The host's last polled state. Never assumed: the chip tells the truth or says nothing. */
    private boolean hostStateKnown = false;
    private boolean hostOnline = false;

    /**
     * Which hand last moved. The shoulders at the ends of the tabs read LB/RB with a pad in it and
     * PgUp/PgDn with a keyboard, exactly as the desktop draws them (AppsScreen.qml:1226-1232) —
     * each chip is drawn beside the thing it moves.
     */

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int PIN_APP_ID = 8;
    private final static int MOVE_APP_ID = 9;

    /** True once the user has picked a tab themselves; a rebuild must not put it back. */
    private boolean tabChosenByUser = false;

    private String category = AppLibrary.CAT_ALL;
    /** The shared prompt bar: it draws every keycap on this page. */
    private PromptBar promptBar;
    private int runningAppId = 0;
    private int lastRunningAppId = 0;
    private String lastRawApplist = "";

    private final List<NvApp> appList = new ArrayList<NvApp>();

    private AppAmbientArtwork ambientArtwork;
    private ComputerManagerService.ApplistPoller poller;
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

                    // This PC earns its home-screen shortcut here — the old screen made it at
                    // exactly this point (`AppView:124-134`), and this is the only picker now, so
                    // this is the only place it can happen. Same thread, same two calls.
                    ShortcutHelper shortcutHelper = new ShortcutHelper(AppsPageView.this);
                    shortcutHelper.createAppViewShortcut(computer, true,
                            getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    adapter = new AppsRowAdapter(AppsPageView.this, computer, localBinder.getUniqueId());

                    managerBinder = localBinder;

                    // ⚠️ The last list this host gave us is held on this machine, and it is worth
                    // more than a live fetch when the live fetch cannot happen. AppView reads the
                    // same cache file on the way in (populateAppGridWithCache); without it the page
                    // is blank on any network the host is not reachable from — which is exactly the
                    // case the cache exists for.
                    if (computer.rawAppList == null || computer.rawAppList.isEmpty()) {
                        try {
                            String cached = CacheHelper.readInputStreamToString(CacheHelper
                                    .openCacheFileForInput(getCacheDir(), "applist", uuidString));
                            if (cached != null && !cached.isEmpty()) {
                                computer.rawAppList = cached;
                            }
                        } catch (IOException e) {
                            // Nothing cached for this host yet: the live fetch is the only way.
                        }
                    }

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

        uuidString = getIntent().getStringExtra(UUID_EXTRA);
        hostName = getIntent().getStringExtra(NAME_EXTRA);
        if (hostName == null) {
            hostName = "";
        }

        // The tab it opens on is the host's own remembered one, not always ALL.
        String savedTab = PlaytimeManager.lastTabOn(this, uuidString);
        if (savedTab != null && !savedTab.isEmpty()) {
            category = savedTab;
            // A saved tab counts as chosen by hand (AppsScreen.qml's _openingTabFor), so the
            // empty-tab move below leaves it alone — exactly as the desktop does.
            tabChosenByUser = true;
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

        // ⚠️ The cursor has to move when the pad moves it.
        //
        // onItemClick is a finger. A stick or a dpad changes the ListView's *selection*, and
        // nothing was listening to that — so the lit row, the right-hand panel and the figures all
        // stood still while the list scrolled under them, which is exactly what Nik found (12 Oct
        // 2026: no selection indicator while scrolling, the right panel not changing with the
        // cursor, the played/session figures never appearing).
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectRow(position);
                if (view != null) {
                    view.setSelected(true);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // A finger scrolls the column rather than moving a selection, so when the list settles the
        // cursor lands on the row nearest the middle of it — the one the eye is actually on.
        grid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                if (scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    settleCursorOnVisible();
                }
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
            }
        });

        registerForContextMenu(grid);

        wireHero();
        // The ring belongs to the list and starts there. Everything else on this page is drawn to
        // be read and touched, not walked to.
        grid.setItemsCanFocus(false);
        grid.setFocusableInTouchMode(true);
        grid.requestFocus();

        wireFoot();

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                android.content.Context.BIND_AUTO_CREATE);
    }

    /**
     * The keys along the foot: this client's stream settings, and the way out to the host list.
     *
     * <p>The pills carry what the desktop's Apps bar carries and no more — Settings on Y/S and
     * Hosts on B/Esc (AppShell.qml {@code _hintsApps}). The launch and Stop prompts that used to sit
     * here are gone, for the desktop's own reason: A and X are already drawn on the hero's buttons
     * next to the words for what they do, and the bar does not say it a second time
     * (AppShell.qml:519-527). The launch prompt was invisible in any case — nothing ever set its
     * text — while still holding a click target.
     */
    private void wireFoot() {
        findViewById(R.id.am_pill_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AppsPageView.this, StreamSettings.class));
            }
        });

        findViewById(R.id.am_pill_hosts).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
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
        TextView online = (TextView) findViewById(R.id.am_chip_online);
        // ⚠️ It says Online only once the host has said so. The pill was painted green before a
        // single poll had landed, so it claimed a connection nobody had checked — on the one screen
        // that goes blank when the host cannot be reached. Offline is the same pill without the dot.
        if (!hostStateKnown) {
            // No verdict yet: a missing chip beats a green one that means nothing.
            online.setVisibility(View.GONE);
        } else {
            online.setText(hostOnline ? "Online" : "Offline");
            online.setCompoundDrawablesWithIntrinsicBounds(
                    hostOnline ? R.drawable.am_dot_online : 0, 0, 0, 0);
            online.setCompoundDrawablePadding((int) (6 * getResources().getDisplayMetrics().density));
            online.setVisibility(View.VISIBLE);
        }
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
        host.setText(address.isEmpty() ? "N/A" : address);

        // The wire the host is on, from the host itself (ArtLight's STATUS reply). Hidden until it
        // answers, and left hidden if it never does: a link speed this client invented would be
        // worse than no speed at all.
        final TextView speed = (TextView) findViewById(R.id.am_cfg_host_speed);
        speed.setVisibility(View.GONE);
        if (computer != null && !address.isEmpty()) {
            new ArtLightBridge(this).requestStatus(address, new ArtLightBridge.ResponseCallback() {
                @Override
                public void onResult(String response) {
                    final String label = HostMetricsPoller.formatMbpsShort(response);
                    if (label == null) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            speed.setText(label);
                            speed.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
        }

        // One pill per setting, the way the desktop draws them with a Repeater
        // (AppsScreen.qml:962-969): the size by its own name where it has one, the rate, the
        // bitrate in Mbps, HDR only when it is on, and the codec.
        //
        // ⚠️ The bitrate preference is in kbps. Divided by 1000 here because the desktop divides
        // there (`(_effBitrate / 1000).toFixed(0)`) — without it the line claims 67000 Mbps.
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        LinearLayout row = (LinearLayout) findViewById(R.id.am_cfg_stream_row);
        row.removeAllViews();
        addCfgChip(row, resLabel(prefs.width, prefs.height));
        addCfgChip(row, prefs.fps + " FPS");
        addCfgChip(row, (prefs.bitrate / 1000) + " Mbps");
        if (prefs.enableHdr) {
            addCfgChip(row, "HDR");
        }
        addCfgChip(row, codecLabel(prefs.videoFormat));
    }

    private void addCfgChip(LinearLayout row, String text) {
        TextView chip = (TextView) android.view.LayoutInflater.from(this)
                .inflate(R.layout.am_cfg_chip, row, false);
        chip.setText(text);
        row.addView(chip);
    }

    /** The desktop's own names for the sizes (AppsScreen.qml:79-86). */
    private static String resLabel(int w, int h) {
        if (w == 3840 && h == 2160) {
            return "4K";
        }
        if (w == 2560 && h == 1440) {
            return "1440p";
        }
        if (w == 1920 && h == 1080) {
            return "1080p";
        }
        if (w == 1280 && h == 720) {
            return "720p";
        }
        return w + "×" + h;
    }

    /** The desktop's codec words (AppsScreen.qml:76). */
    private static String codecLabel(PreferenceConfiguration.FormatOption f) {
        if (f == PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return "AV1";
        }
        if (f == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
            return "HEVC";
        }
        if (f == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return "H.264";
        }
        return "Auto codec";
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

        // The shoulders at the two ends of the strip: LB back, RB forward, the ring wrapping
        // (AppsScreen.qml:1226 and 1233, switchLibraryTab). A finger uses them the same way.
        android.view.View left = findViewById(R.id.am_tab_sh_left);
        if (left != null) {
            left.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(-1);
                }
            });
        }
        android.view.View right = findViewById(R.id.am_tab_sh_right);
        if (right != null) {
            right.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(1);
                }
            });
        }

        wirePrompts();
    }

    /**
     * The page's prompts, registered with the shared PromptBar — the same component Home's footer
     * uses, so the two screens cannot disagree about what a pad button looks like.
     *
     * <p>⚠️ This page had its own version, and it is why the picker's prompts were the only ones in
     * the app still printing a letter. It held a {@code lastInputWasPad} boolean and re-wrote the
     * keycap's text between "LB" and "PgUp" on every event. The app already resolves that
     * app-wide — {@code ArtMoonActivity.dispatchKeyEvent} feeds {@code InputModeManager}, which
     * PromptBar listens to — so a second copy could only ever drift from it, and it had: the pad
     * half of "Settings" and "Hosts" was never drawn at all, because a letter was the whole prompt.
     *
     * <p>What each prompt resolves to is the desktop's own table, and none of it is decided here:
     * the shoulders are LB/RB or PgUp/PgDn (AppsScreen.qml:1226), the hero's verb is A or Enter and
     * Stop is X (AppsScreen.qml:1116,1124), Settings is Y or S and Hosts is B or Esc
     * (AppShell.qml {@code _hintsApps}).
     */
    private void wirePrompts() {
        promptBar = new PromptBar(this);
        promptBar.registerById(R.id.am_tab_sh_left_cap, "prevTab");
        promptBar.registerById(R.id.am_tab_sh_right_cap, "nextTab");
        promptBar.registerById(R.id.am_hero_launch_cap, "launch");
        promptBar.registerById(R.id.am_hero_stop_cap, "stop");
        promptBar.registerById(R.id.am_key_settings_cap, R.id.am_key_settings, "settings");
        promptBar.registerById(R.id.am_key_hosts_cap, R.id.am_key_hosts, "hosts");
        promptBar.registerById(R.id.am_key_pin_cap, R.id.am_key_pin, "pin");
        promptBar.registerById(R.id.am_key_move_cap, R.id.am_key_move, "move");

        // Tappable, like the two beside it. A pad-first page must not hide an action behind a
        // button only a controller has — the pill is the prompt and the door at the same time.
        View pinPill = findViewById(R.id.am_pill_pin);
        if (pinPill != null) {
            pinPill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePinFocused();
                }
            });
        }
        promptBar.attach();
    }

    /**
     * One step along ALL / GAMES / APPS, wrapping at the ends — the desktop's own ring
     * (switchLibraryTab). A switch by hand is saved, which is what the pad and the keys are.
     */
    private void switchTab(int dir) {
        final String[] tabs = { AppLibrary.CAT_ALL, AppLibrary.CAT_GAMES, AppLibrary.CAT_APPS };
        int at = 0;
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i].equals(category)) {
                at = i;
            }
        }
        setTab(tabs[(at + (dir < 0 ? -1 : 1) + tabs.length) % tabs.length], true);
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
                // One door, one implementation: the pad's right stick and M land on this same
                // method, so the two can never drift apart (the desktop has one moveFocused() for
                // both, AppsScreen.qml:388).
                moveFocused();
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

    private void selectRow(int position) {
        if (adapter == null || position < 0 || position >= adapter.getCount()
                || adapter.getItemViewType(position) == 1) {
            return;
        }
        adapter.setFocused(position);
        updateHero();
    }

    /**
     * Move the cursor one row, clamped at both ends.
     *
     * <p>Left to the list, a d-pad press is not a pure move: the edges have behaviour of their own,
     * and the list also scrolls, which is what wakes {@link #settleCursorOnVisible()} underneath the
     * press. Driving the selection here means one press is one row.
     */
    private void moveCursor(int dir) {
        if (grid == null || adapter == null) {
            return;
        }
        int count = adapter.getCount();
        if (count == 0) {
            return;
        }
        // ⚠️ The cursor is the adapter's, not the list's: in touch mode getSelectedItemPosition()
        // reports INVALID_POSITION while the list has plainly moved (see selectedRow above).
        int current = adapter.focusedPosition();
        if (current < 0 || current >= count) {
            current = grid.getSelectedItemPosition();
        }
        if (current < 0 || current >= count) {
            // Nothing is selected yet: the entry itself should already be under the cursor (see
            // landCursorOnFirstRow), and if the very first press arrives before that has happened,
            // it lands on the first entry rather than moving off wherever the list happens to be.
            current = firstSelectableRow();
            selectRow(current);
            grid.setSelection(current);
            return;
        }
        // ⚠️ Walk past the section headings rather than stopping on one. A heading is a list item on
        // this page; the desktop's sections are the ListView's own, so its cursor can never land on
        // one. Moving onto one here stopped the cursor dead — selectRow() rightly refuses it, while
        // the list's own selection had already gone there — and the next press tried the same refuse.
        int next = current;
        while (true) {
            final int candidate = next + dir;
            if (candidate < 0 || candidate >= count) {
                break;              // clamped at both ends: an edge is just an edge
            }
            next = candidate;
            if (adapter.getItemViewType(next) != 1) {
                break;              // a real entry
            }
        }
        if (next != current && adapter.getItemViewType(next) != 1) {
            grid.setSelection(next);
            selectRow(next);
        }
    }

    /** The first row that is an app rather than a section heading. */
    private int firstSelectableRow() {
        for (int i = 0; adapter != null && i < adapter.getCount(); i++) {
            if (adapter.getItemViewType(i) != 1) {
                return i;
            }
        }
        return 0;
    }

    /**
     * The cursor is on the first app the moment the page opens.
     *
     * <p>Nik, 12 Oct 2026: "it doesnt have the first item on the list already selected, i have to
     * press down on the depad before anything is selected". The desktop's grid opens with the first
     * card in the spotlight, and everything on this page acts on "the spotlight" — an empty
     * spotlight is a page where A would have nothing to launch.
     *
     * <p>The first row of a section is its heading rather than an app, so the landing is on the
     * first row that is a real entry. It waits for the list if the list has not arrived, and it
     * leaves an existing selection alone: coming back from Settings must not send him to the top.
     */
    private void landCursorOnFirstRow() {
        landCursorOnFirstRow(24);
    }

    private void landCursorOnFirstRow(final int triesLeft) {
        if (grid == null || adapter == null) {
            return;
        }
        if (adapter.getCount() == 0) {
            if (triesLeft > 0) {
                grid.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        landCursorOnFirstRow(triesLeft - 1);
                    }
                }, 120);
            }
            return;
        }
        // The list has to hold the focus or no row draws as the cursor, and in touch mode a plain
        // ListView cannot take it — appGrid is focusableInTouchMode for exactly this. This used to
        // be a silent failure: requestFocus() returned false, the highlight never appeared, and the
        // page looked like nothing was selected.
        grid.requestFocus();
        if (adapter.focusedPosition() >= 0) {
            return;
        }
        int first = firstSelectableRow();
        selectRow(first);
        grid.setSelection(first);
    }

    private void settleCursorOnVisible() {
        // ⚠️ Not while a pad is in the hand. A pad press scrolls the list, which wakes this on the
        // tail of every press, and this then moves the cursor to whatever row is nearest the middle
        // of the viewport — somewhere the press never put it. This exists for a finger: a scroll
        // with no cursor in it, landing on the row the eye is on.
        if (io.github.onaiaku.artmoon.artlight.InputModeManager.get().getMode()
                == io.github.onaiaku.artmoon.artlight.InputModeManager.Mode.GAMEPAD) {
            return;
        }
        if (grid == null || adapter == null || grid.getChildCount() == 0) {
            return;
        }
        int middle = grid.getHeight() / 2;
        int bestPosition = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int centre = child.getTop() + child.getHeight() / 2;
            int distance = Math.abs(centre - middle);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPosition = grid.getFirstVisiblePosition() + i;
            }
        }
        if (bestPosition >= 0) {
            selectRow(bestPosition);
        }
    }

    /**
     * The row under the cursor, which is what the spotlight is about.
     *
     * ⚠️ Not grid.getSelectedItemPosition() on its own: in touch mode that is INVALID_POSITION, so
     * the right-hand panel sat on row 0 while the list had plainly moved. The cursor the highlight
     * is drawn from is the only honest answer about what is selected.
     */
    private AppLibrary.Row selectedRow() {
        if (adapter == null || grid == null) {
            return null;
        }
        int position = adapter.focusedPosition();
        if (position < 0) {
            position = grid.getSelectedItemPosition() >= 0 ? grid.getSelectedItemPosition() : 0;
        }
        // ⚠️ Answering null for a heading is what left the spotlight empty and its cover blank the
        // moment the page opened — and the row the eye is on is the first real entry under it. The
        // cursor only ever addresses ROWS on the desktop, because its sections belong to the list
        // rather than sitting in it; this is that rule, one item type check wide.
        if (position >= 0 && position < adapter.getCount()
                && adapter.getItemViewType(position) == 1) {
            position = firstSelectableRow();
        }
        return adapter.rowAt(position);
    }

    /**
     * Pins or unpins the selected game and keeps the cursor on it — the desktop's own
     * {@code togglePinFocused} (AppsScreen.qml:541-557), copied rather than re-derived.
     *
     * <p>⚠️ By app id, after the call. Pinning moves the row — up under PINNED, or back down into
     * ALL GAMES — and the page is rebuilt, so the position the list held now points at whatever
     * slid into that place. The desktop carries the same warning and the same cure.
     */
    private void togglePinFocused() {
        final AppLibrary.Row row = selectedRow();
        // Only games can be pinned: on the APPS tab there is nothing to pin, and the desktop's
        // toggle returns early for an entry that is an app (AppsScreen.qml:544).
        if (row == null || row.isApp) {
            return;
        }
        PlaytimeManager.setPinned(this, uuidString, row.name, !row.pinned);
        rebuildPageKeeping(row.appId);
    }

    /**
     * The pin prompt, which follows the row rather than the action: Pin, or Unpin on a game that is
     * already pinned — and not drawn at all unless a game is under the cursor (AppShell.qml:533-545,
     * "the APPS tab has nothing to pin, so there the prompt is not drawn").
     *
     * <p>Hidden is not enough on its own: a prompt that is invisible and still carries a click
     * target is a trap this page has already paid for once, so the pill stops being clickable with it.
     */
    private void updatePinPrompt() {
        final AppLibrary.Row row = selectedRow();
        final boolean pinnable = row != null && !row.isApp;

        final View pill = findViewById(R.id.am_pill_pin);
        if (pill != null) {
            pill.setVisibility(pinnable ? View.VISIBLE : View.GONE);
            pill.setClickable(pinnable);
            // It was drawn pressable with nothing behind it (see updateMovePrompt). A game under
            // the cursor is what makes it a button; otherwise it is not one.
            pill.setOnClickListener(pinnable ? new android.view.View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePinFocused();
                }
            } : null);
        }
        if (promptBar != null) {
            promptBar.setLabel("pin", pinnable && row.pinned
                    ? getString(R.string.am_label_unpin) : null);
        }
    }

    /**
     * Moves the focused entry to the other tab — the desktop's own {@code moveFocused()}
     * (AppsScreen.qml:381-402), which until now this page reached only through the long-press
     * menu. That was half a port: the desktop's everyday door is the pad's right stick or M, and
     * the prompt beside it says so.
     *
     * <p><b>The tab is marked as chosen by hand BEFORE the move.</b> Moving an entry changes the
     * counts, and this page picks a tab from the counts when the tab was never chosen by hand —
     * which would put ALL on top before this method got to say where to go. The desktop carries the
     * same warning in the same words.
     *
     * <p><b>Only on GAMES or APPS, and only for an entry the user owns.</b> "Not on ALL, where both
     * halves are shown together and 'the other tab' means nothing, and never for a host control —
     * those are not applications and stay on APPS" (AppsScreen.qml:369-379).
     */
    private void moveFocused() {
        final AppLibrary.Row row = selectedRow();
        if (row == null || !row.movable || category == null
                || AppLibrary.CAT_ALL.equals(category)) {
            return;
        }

        tabChosenByUser = true;
        PlaytimeManager.setCategoryOverride(this, uuidString, row.name, !row.isApp, false);
        rebuildPageKeeping(row.appId);
    }

    /**
     * The move prompt, following the row exactly as the pin prompt does — the desktop pushes it
     * only when the page's own label is non-empty (AppShell.qml:547-548), and the label is the
     * gate read out loud: "Move to GAMES" on an app, "Move to APPS" on a game, nothing on ALL or on
     * an entry that cannot move (AppsScreen.qml:374-379).
     *
     * <p>The tap is real. The pin prompt was drawn pressable and had no listener, so a tap on it
     * did nothing at all — a prompt that looks like a button and is not one is the trap this page
     * has already paid for once. Both pills now do what they say, by hand as well as by pad.
     */
    private void updateMovePrompt() {
        final AppLibrary.Row row = selectedRow();
        final boolean movable = row != null && row.movable
                && category != null && !AppLibrary.CAT_ALL.equals(category);

        final View pill = findViewById(R.id.am_pill_move);
        if (pill != null) {
            pill.setVisibility(movable ? View.VISIBLE : View.GONE);
            pill.setClickable(movable);
            pill.setOnClickListener(movable ? new android.view.View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    moveFocused();
                }
            } : null);
        }
        if (promptBar != null) {
            promptBar.setLabel("move", movable
                    ? getString(row.isApp ? R.string.am_label_move_games
                                          : R.string.am_label_move_apps)
                    : null);
        }
    }

    private void fillPage() {
        if (isFinishing()) {
            return;
        }
        page = AppLibrary.page(this, uuidString, appList, category, runningAppId);

        // The two corrections the desktop makes the moment the counts are known
        // (AppsScreen.qml:437-440, the onCountsChanged handler). A host holding a Remote Monitor
        // lists only Resume and Disconnect Monitor, so that page is APPS; and a tab with nothing
        // in it is not where the page should sit when APPS has something. Both move the page,
        // never the memory — and both are unconditional on the desktop, so they are here.
        if (page.remoteMonitorActive && !AppLibrary.CAT_APPS.equals(category)) {
            category = AppLibrary.CAT_APPS;
            fillPage();
            return;
        }
        if (!AppLibrary.CAT_APPS.equals(category) && page.rows.isEmpty() && page.appsCount > 0) {
            category = AppLibrary.CAT_APPS;
            fillPage();
            return;
        }
        if (adapter == null) {
            adapter = new AppsRowAdapter(this, computer, "");
        }
        adapter.setPage(page, category);
        grid.setAdapter(adapter);
        // The library's art, fetched once for the page rather than raced for by every recycled row.
        adapter.prefetchCovers();
        grid.setVisibility(page.rows.isEmpty() ? View.GONE : View.VISIBLE);

        // The rows are here, so the cursor can be put on the first real entry now. The delayed
        // retries in landCursorOnFirstRow are for the wait before the host answers; a list that
        // arrives after they have run out used to leave the page with nothing selected at all,
        // which is the inconsistency Nik kept hitting.
        landCursorOnFirstRow();

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
        // Nothing to write: Stop's prompt follows the running row's own button, which carries the
        // cap for whoever is holding the controls. The foot used to be stamped with "X  Stop" here
        // and never cleared, so it outlived the session it named.
    }

    /** The host's own state, for the chip and for the words the empty state uses. */
    private void setHostOnline(final boolean online) {
        if (hostStateKnown && hostOnline == online) {
            return;
        }
        hostStateKnown = true;
        hostOnline = online;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                paintChips();
            }
        });
    }

    /** Three different strings by tab, as the desktop has them. */
    private String emptyText() {
        if (hostStateKnown && !hostOnline) {
            // ⚠️ Do not blame the network. I measured exactly this on 12 Oct 2026 and got it wrong:
            // Nik's phone routed the host straight into the VPN and its packets plainly reached the
            // host's ports from there. "Can't reach it from this network" was a claim I had not
            // checked. Say only what is true — the host has not answered.
            return (computer != null ? computer.name : "The host") + " isn't answering";
        }
        if (AppLibrary.CAT_GAMES.equals(category)) {
            return "No games to show";
        }
        if (AppLibrary.CAT_APPS.equals(category)) {
            return "No apps to show";
        }
        return "Nothing to show";
    }

    private void paintTabs() {
        final String[] tabs = {AppLibrary.CAT_ALL, AppLibrary.CAT_GAMES, AppLibrary.CAT_APPS};
        final int[] ids = {R.id.am_tab_all, R.id.am_tab_games, R.id.am_tab_apps};
        final int[] counts = {page != null ? page.allCount : -1,
                              page != null ? page.gamesCount : -1,
                              page != null ? page.appsCount : -1};
        final int[] labels = {R.string.am_tab_all, R.string.am_tab_games, R.string.am_tab_apps};
        for (int i = 0; i < ids.length; i++) {
            TextView tab = (TextView) findViewById(ids[i]);
            boolean on = tabs[i].equals(category);
            // The count belongs in the label, printed exactly as the old screen printed it
            // (`AppView:806-808`). It was never a second screen's feature — it is this page's.
            tab.setText(counts[i] >= 0
                    ? getString(labels[i]) + "  " + counts[i]
                    : getString(labels[i]));
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
        // The pin prompt follows the row, so it is refreshed with the spotlight — the same moment
        // the cursor is read for the hero.
        updatePinPrompt();
        updateMovePrompt();

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
            android.view.View noStopPill = findViewById(R.id.am_hero_stop_pill);
            if (noStopPill != null) {
                noStopPill.setVisibility(View.GONE);
            }
            launch.setText("Play");
            return;
        }

        title.setText(row.name);

        boolean running = row.running;

        SpannableStringBuilder parts = new SpannableStringBuilder();
        if (running) {
            parts.append(getString(R.string.am_pick_running));
        }
        // The store the host named, in words — the desktop's spotlight carries it too
        // (AppsScreen.qml focusedStore), and it is the "platform" the entry belongs to.
        String store = adapter != null ? adapter.storeFor(row.name) : "";
        if (!store.isEmpty()) {
            if (parts.length() > 0) {
                parts.append("  ·  ");
            }
            appendStoreMark(parts, store,
                    getResources().getColor(running ? R.color.am_online : R.color.am_text2));
            parts.append(store);
        }
        if (row.overridden) {
            if (parts.length() > 0) {
                parts.append("  ·  ");
            }
            parts.append(getString(R.string.am_meta_custom_settings));
        }
        meta.setText(parts);
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
        // The pill around it goes with it — the label lives inside the pill now.
        android.view.View stopPill = findViewById(R.id.am_hero_stop_pill);
        if (stopPill != null) {
            stopPill.setVisibility(running ? View.VISIBLE : View.GONE);
        }

        cover.setContentDescription(row.name);
        // The artwork, from the same loader the rows use. It was never asked for here: the old
        // picker's adapter owned this call and went with the old screen (see AppsRowAdapter).
        if (adapter != null && row.app != null) {
            adapter.populateCover(row.app, cover);
        }

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
        // ⚠️ The host's poll reply carries no app list. It is fetched by its own poller, which the
        // old screen started here too, and the new one has to do it for itself. Without it the page shows no
        // entries at all: the only list it would ever see is whatever the computer record happened
        // to be holding when the screen opened.
        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }
                // ⚠️ A host that cannot be reached from here is not a reason to close the page —
                // it is the everyday state of a phone. The old screen finished itself on OFFLINE,
                // which would make the picker the one screen that dies whenever Nik is away from
                // home. The desktop keeps the page and says so: the chip goes Offline, the cached
                // library is still there, and nothing is thrown away.
                setHostOnline(details.state == ComputerDetails.State.ONLINE);
                if (details.state != ComputerDetails.State.ONLINE) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }
                            fillPage();
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
                        /*
                         * Ask for the artwork again, now that the host has just answered.
                         *
                         * ⚠️ The asset loader keeps no memory of a load that came back empty: if the
                         * host was not reachable when a row was first bound, the artwork task ends
                         * with nothing and no one asks a second time. The row then sits on its
                         * placeholder until something re-binds it — which is why a scroll brought
                         * the art back (Nik, 12 Oct 2026: "the background artwork of what is
                         * selected doent show until i scroll"). The app list arriving IS the proof
                         * that the host is answering, so this is the honest moment to ask again.
                         */
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                        updateHero();
                    }
                });
            }
        });
    }

    /**
     * The store each entry came from, in words, from the host itself. Best effort: an older host, or
     * one that is not ArtLight, simply has nothing to say and the meta line goes without it.
     */
    /*
     * The store's mark, inline just before its name — the desktop draws it at 19 design px with
     * 8 px of air either side (GameFacts.qml:317-335), for the reason it gives itself: the mark
     * "earns its place by being recognisable before the word beside it is read".
     *
     * An ImageSpan and not a compound drawable, because on the phone's line the store sits in the
     * middle (after the running state) and a compound drawable can only sit at a line's edge.
     * Nothing is drawn for a store with no mark — the desktop's own answer to a name it does not
     * know — so the line simply reads as it did before.
     */
    private void appendStoreMark(SpannableStringBuilder out, String store, int color) {
        int res = StoreMarks.drawableFor(store);
        if (res == 0) {
            return;
        }

        Drawable mark = getResources().getDrawable(res, null);
        if (mark == null) {
            return;
        }

        int size = (int) (19 * getResources().getDisplayMetrics().density);
        mark.setBounds(0, 0, size, size);
        mark.setTint(color);

        // The span takes the place of the space it is set on.
        int at = out.length();
        out.append(" ");
        out.setSpan(new ImageSpan(mark, ImageSpan.ALIGN_BOTTOM), at, at + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append(" ");
    }

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

    /**
     * The pad, on the app picker.
     *
     * <p>Every line is the desktop's (AppsScreen.qml:467-500): "Y → Settings; X (Menu) → stop the
     * running app; Select → per-game settings. All three live on the root rather than on the list,
     * because they are the page's shortcuts and not the list's: they have to work wherever the
     * focus happens to be" — plus the hero's own verbs off its hint table (AppsScreen.qml:1114-1126,
     * A or Enter to launch, X to stop).
     *
     * <p>⚠️ Until now nothing here owned a single one of them, so a pad press fell through to the
     * framework: X answered nothing at all — 36 presses in one sitting on Nik's Fold, with the
     * prompt two inches away saying X would stop the session — and A worked only by accident, as
     * Android's own activation key, on whatever happened to hold focus.
     *
     * <p>The d-pad is deliberately NOT here: the list already walks it, and the spotlight follows.
     */
    @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
        // The mode is only for drawing (which glyph, which letter). It does NOT decide whether a
        // press is handled — that gate is what let every button through to the framework below.
        io.github.onaiaku.artmoon.artlight.InputModeManager.get().notifyKeyEvent(event);

        final int kc = event.getKeyCode();
        final int act = event.getAction();
        if (act != KeyEvent.ACTION_DOWN && act != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }
        final boolean down = act == KeyEvent.ACTION_DOWN;

        switch (kc) {
            /*
             * ── The pad, owned outright ───────────────────────────────────────────────
             *
             * ⚠️ Own means OWN: the DOWN half is swallowed here, including every repeat, so not one
             * of these can reach a view. Without this an unhandled button falls through to the
             * framework, and the framework's answer to a press is to activate whatever holds focus
             * — on this page a row, whose click listener launches the session. That is Nik's
             * "press y to open settings... launches stream": the prompt was right, the button was
             * owned by nobody.
             */
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DEL:          // this pad's stray companion to every X press
                if (down) {
                    return true;
                }
                break;

            /*
             * ── Move to the other tab ─────────────────────────────────────────────────
             *
             * The right stick, or M on a keyboard — the desktop's pair, both halves
             * (AppsScreen.qml:517-521: "right stick click (Key_F19), M on the keyboard") and
             * the pair its own hint row names (AppShell.qml:548, btn "RS", key "M").
             *
             * The right stick used to sit in the swallow list below with nothing to do, which
             * was the right call while nothing owned it: a press that does nothing beats a press
             * that leaks to the framework and launches a stream. Now it does the thing.
             */
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_M:
                if (down) {
                    moveFocused();
                }
                return true;

            /*
             * ── The ring stays in the list ────────────────────────────────────────────
             *
             * Up and down are the list's, and go through to it. Left and right are the page's —
             * and they are swallowed, because the framework's geometric focus search is what walked
             * the cursor sideways into the action pills and upward into the section tabs. Nothing
             * on this page reaches the hero or the tabs by walking: LB/RB change tabs and the
             * spotlight follows the list.
             */
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                /*
                 * ⚠️ Not handed to the list. At the top row an UP is "unhandled" there, and what
                 * Android does with a d-pad key at the edge of a list is not nothing: press UP on
                 * the first entry, then DOWN, and the selection lands on the last one (Nik, 12 Oct
                 * 2026). The cursor is moved here, clamped, and the key is swallowed — an edge is
                 * then just an edge.
                 */
                if (down) {
                    moveCursor(kc == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                }
                return true;

            case KeyEvent.KEYCODE_PAGE_UP:       // the keyboard's pair for the tabs
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return super.dispatchKeyEvent(event);

            default:
                return super.dispatchKeyEvent(event);
        }

        if (down) {
            return super.dispatchKeyEvent(event);
        }
        switch (kc) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // The hero's verb, on the button the hero's prompt names.
                launchSelected();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                // Stop the session on the spotlight, and only when there is one — the desktop's
                // `if (focusedAppIsRunning) stopFocusedApp()`. quit() asks first, as every quit
                // in this app does. Nothing running: nothing happens.
                AppLibrary.Row runningRow = selectedRow();
                if (runningRow != null && runningRow.running) {
                    quit(runningRow);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                finish();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                startActivity(new Intent(AppsPageView.this, StreamSettings.class));
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (grid != null) {
                    grid.showContextMenu();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                // 6.0.0, the desktop's own row (AppShell.qml:533-545): Start pins the selected
                // game. Until now it fell through to the silent default — owned, and doing
                // nothing at all, which is exactly what a pad in the hand found.
                togglePinFocused();
                return true;
            default:
                // BUTTON_START, the triggers, the sticks, the pads' extra buttons and the stray
                // DEL: owned and deliberately silent, which is the whole point.
                return true;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        // The tabs, the desktop's own pair: LB/RB on the pad (Key_F16/F17) and PgUp/PgDn on the
        // keyboard, one step each way with the ring wrapping (AppsScreen.qml:503-511).
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            switchTab(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            switchTab(1);
            return true;
        }

        // The desktop's own key for the pin (AppShell.qml:536: key "P"), the keyboard half of the
        // same prompt the pad reaches with Start.
        if (keyCode == KeyEvent.KEYCODE_P) {
            togglePinFocused();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        // A stick moving is the pad in the hand — InputModeManager reads the device from the event
        // itself (ArtMoonActivity.dispatchKeyEvent), and the ListView takes the motion, which is
        // what moves its selection and fires the cursor above.
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        if (managerBinder != null) {
            startComputerUpdates();
        }
        if (grid != null) {
            // The page opens with the first app under the cursor, not with nothing until the first
            // press. Posted rather than called: the list is laid out after this returns.
            grid.post(new Runnable() {
                @Override
                public void run() {
                    landCursorOnFirstRow();
                }
            });
        }
        // Settings can change while this page is behind them, and the config line is a promise
        // about what a session would go out with. Re-read rather than left showing the numbers the
        // page was opened with.
        if (adapter != null) {
            fillConfigLine();
        }
    }

    @Override
    protected void onPause() {
        inForeground = false;
        if (poller != null) {
            poller.stop();
        }
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
