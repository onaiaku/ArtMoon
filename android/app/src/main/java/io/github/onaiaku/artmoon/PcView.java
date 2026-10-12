package io.github.onaiaku.artmoon;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import io.github.onaiaku.artmoon.binding.PlatformBinding;
import io.github.onaiaku.artmoon.binding.crypto.AndroidCryptoProvider;
import io.github.onaiaku.artmoon.computers.ComputerManagerListener;
import io.github.onaiaku.artmoon.computers.ComputerManagerService;
import io.github.onaiaku.artmoon.grid.PcGridAdapter;
import io.github.onaiaku.artmoon.grid.assets.DiskAssetLoader;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.nvstream.http.NvHTTP;
import io.github.onaiaku.artmoon.settings.PlaytimeManager;
import io.github.onaiaku.artmoon.nvstream.http.PairingManager;
import io.github.onaiaku.artmoon.nvstream.http.PairingManager.PairState;
import io.github.onaiaku.artmoon.nvstream.wol.WakeOnLanSender;
import io.github.onaiaku.artmoon.preferences.AddComputerManually;
import io.github.onaiaku.artmoon.preferences.GlPreferences;
import io.github.onaiaku.artmoon.preferences.PreferenceConfiguration;
import io.github.onaiaku.artmoon.preferences.StreamSettings;
import io.github.onaiaku.artmoon.artlight.InputModeManager;
import io.github.onaiaku.artmoon.ui.AdapterFragment;
import io.github.onaiaku.artmoon.ui.AdapterFragmentCallbacks;
import io.github.onaiaku.artmoon.utils.Dialog;
import io.github.onaiaku.artmoon.utils.HelpLauncher;
import io.github.onaiaku.artmoon.utils.ServerHelper;
import io.github.onaiaku.artmoon.utils.ShortcutHelper;
import io.github.onaiaku.artmoon.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import io.github.onaiaku.artmoon.artlight.HostBackgroundManager;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends io.github.onaiaku.artmoon.ArtMoonActivity implements AdapterFragmentCallbacks {
    private static final int REQ_HERO_BG = 9101;
    private static final int BACKGROUND_ID = 14;
    private HostBackgroundManager heroBgManager;
    private String heroBgPendingUuid;
    private io.github.onaiaku.artmoon.artlight.PromptBar promptBar;
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    /** The hosts grid view, captured in receiveAbsListView for d-pad routing. */
    private AbsListView pcGridView;
    /** Owned gamepad focus: 0 = Open, 1 = Options, 2 = + Add a Host / Pair. */
    private int padActionIndex = 0;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int POWER_ID = 12;
    private final static int ARTLIGHT_PAIR_ID = 13;

    private io.github.onaiaku.artmoon.artlight.HostMetricsPoller metricsPoller;
    private io.github.onaiaku.artmoon.artlight.HostAuthManager authManager;
    private final java.util.HashMap<String, Long> lastAutoProbeAtMs = new java.util.HashMap<>();
    private static final long AUTO_REPROBE_COOLDOWN_MS = 60000L;
    private android.app.Dialog authPinDialog;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Ported scale (AppShell.qml:32 — Theme.uiScale = width / 1330). Every base number in
        // activity_pc_view.xml is the QML's own; this multiplies them all by uiScale once, which
        // is the desktop's _px() applied to the whole screen at once.
        io.github.onaiaku.artmoon.ui.AmScale.applyTo(
                findViewById(R.id.am_home_root),
                io.github.onaiaku.artmoon.ui.AmScale.ofScreen(this));

        // The footer chips' letters, AFTER the tree has been scaled — ActionHint puts a floor of
        // 11 under them that the scale must not touch (applyCapLetter). AppShell puts them at
        // size _px(26) (AppShell.qml:601).
        applyCapLetter((android.widget.TextView) findViewById(R.id.am_footer_kb_shutdown), 26f);
        applyCapLetter((android.widget.TextView) findViewById(R.id.am_footer_kb_settings), 26f);
        applyCapLetter((android.widget.TextView) findViewById(R.id.am_footer_kb_exit), 26f);

        // Edge-to-edge is not optional at targetSdk 36 on Android 15+ — the window runs under
        // the status bar and the navigation bar whether the app asks to or not, so the app owes
        // those insets back to its own content. See applySystemBarInsets.
        applySystemBarInsets();

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Toolbar/rail buttons (settings, help, add) were removed from the layouts:
        // their real actions live in the footer keycaps (S Settings, P Shutdown) and
        // the picker row. "Add a host" stays reachable from the picker-row text.
        // The strip (one tab per host, "+  Add a host" last) is built from the host list and
        // rebuilt whenever that list changes. See rebuildHostTabs().
        rebuildHostTabs();

        // Footer keycaps (render parity): P Shutdown / S Settings / Esc Exit are
        // real controls, wired to the same flows the keycaps name. Null-safe per
        // layout — whichever orientation carries them gets them functional.
        View footerShutdown = findViewById(R.id.am_footer_shutdown);
        if (footerShutdown != null) {
            footerShutdown.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    ComputerObject hero = getHeroComputer();
                    if (hero != null) {
                        doArtLightPowerMenu(hero.details);
                    }
                }
            });
        }
        View footerSettings = findViewById(R.id.am_footer_settings);
        if (footerSettings != null) {
            footerSettings.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(PcView.this, StreamSettings.class));
                }
            });
        }
        View footerExit = findViewById(R.id.am_footer_exit);
        if (footerExit != null) {
            footerExit.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    finishAffinity();
                }
            });
        }
        // PgUp / PgDn pills: page the host list (previously decorative).
        View pgUp = findViewById(R.id.am_pg_up);
        if (pgUp != null) {
            pgUp.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleHost(-1);
                }
            });
        }
        View pgDn = findViewById(R.id.am_pg_dn);
        if (pgDn != null) {
            pgDn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleHost(1);
                }
            });
        }

        // Input-aware prompt bar: keycap pills re-render for touch / gamepad /
        // keyboard (desktop parity — prompts always tell the truth).
        promptBar = new io.github.onaiaku.artmoon.artlight.PromptBar(this);
        promptBar.registerById(R.id.am_footer_kb_shutdown, R.id.am_footer_shutdown, "shutdown");
        promptBar.registerById(R.id.am_footer_kb_settings, R.id.am_footer_settings, "settings");
        promptBar.registerById(R.id.am_footer_kb_exit, R.id.am_footer_exit, "exit");
        promptBar.attach();

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OrientationHelper.applyOrientation(this);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));
        pcGridAdapter.setPcView(this);

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            if (metricsPoller == null) {
                metricsPoller = new io.github.onaiaku.artmoon.artlight.HostMetricsPoller(this, pcGridAdapter);
            }
            metricsPoller.start();
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (metricsPoller != null) {
            metricsPoller.stop();
        }
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (authPinDialog != null) {
            try {
                authPinDialog.dismiss();
            } catch (Exception ignored) {
            }
            authPinDialog = null;
        }

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

        // Gamepad: seed the ring on Open (or + Add a Host with no hosts) as
        // soon as layout is ready — never launch with nothing highlighted.
        if (InputModeManager.get().getMode() == InputModeManager.Mode.GAMEPAD) {
            seedPadFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));

            // ArtLight: signed power actions (SHUTDOWN / SHUTDOWN_UPDATE) via
            // ArtLightBridge. Destructive — confirm dialog before firing.
            menu.add(Menu.NONE, POWER_ID, 5, getResources().getString(R.string.am_power_menu));

            // ArtLight Control enrollment - opt-in only (long-press the host to pair)
            menu.add(Menu.NONE, ARTLIGHT_PAIR_ID, 6, getResources().getString(R.string.pcview_menu_artlight_pair));
        }

        // Per-host hero background picker (same menu the long-press uses).
        menu.add(Menu.NONE, BACKGROUND_ID, 7, getResources().getString(R.string.pcview_menu_background));

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        final String pinStr = PairingManager.generatePinString();

                        // Spin the dialog off in a thread because it blocks
                        Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                getResources().getString(R.string.pair_pairing_help), false);

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case BACKGROUND_ID:
                showHeroBackgroundMenu(computer.details.uuid);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            case ARTLIGHT_PAIR_ID:
                probeArtLightAuth(computer.details);
                return true;

            case POWER_ID:
                AdapterContextMenuInfo powerMenuInfo =
                        (AdapterContextMenuInfo) item.getMenuInfo();
                if (powerMenuInfo != null) {
                    ComputerObject pc = (ComputerObject) pcGridAdapter.getItem(powerMenuInfo.position);
                    doArtLightPowerMenu(pc.details);
                }
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }
    
    // ── ArtLight Control enrollment (approved-client flow) ──────────────────

    private void probeArtLightAuth(final io.github.onaiaku.artmoon.nvstream.http.ComputerDetails details) {
        if (authManager == null) {
            authManager = new io.github.onaiaku.artmoon.artlight.HostAuthManager(this);
        }
        final String address = details.activeAddress != null ? details.activeAddress.address :
                details.remoteAddress != null ? details.remoteAddress.address :
                details.localAddress != null ? details.localAddress.address : null;
        if (address == null) {
            return;
        }
        authManager.setListener(details.uuid, new io.github.onaiaku.artmoon.artlight.HostAuthManager.Listener() {
            @Override
            public void onAuthState(String uuid, String state, String pin) {
                if ("pending".equals(state) && pin != null && !pin.isEmpty()) {
                    showAuthPinDialog(details, pin);
                } else if ("authorized".equals(state) || "denied".equals(state) || "none".equals(state)) {
                    dismissAuthPinDialog();
                }
                // Paint the card's ArtLight Authorised chip from the real state.
                pcGridAdapter.updateAuthStateByUuid(uuid, state);
                // "open" needs no UI — the integration simply works.
            }
        });
        authManager.probe(details.uuid, address);
    }

    /**
     * Silent background re-probe of a previously-paired host's ArtLight auth
     * state (desktop parity: the access probe runs without user action).
     * Runs at most once per host per app session; retried on the next host
     * update if the host wasn't answering. The listener here never shows the
     * PIN popup — if the host no longer knows us ("pending"), the chip hides
     * and the user re-pairs manually via long-press.
     */
    private void maybeAutoReprobeArtLight(final io.github.onaiaku.artmoon.nvstream.http.ComputerDetails details) {
        if (authManager == null) {
            authManager = new io.github.onaiaku.artmoon.artlight.HostAuthManager(this);
        }
        final String uuid = details.uuid;
        // Only ever-authorized hosts: probing an unpaired host would make it
        // pop its "allow this device?" prompt uninvited. Cooldown keeps an
        // unreachable paired host from being probed every poll cycle.
        long now = android.os.SystemClock.elapsedRealtime();
        Long lastProbe = lastAutoProbeAtMs.get(uuid);
        if (!authManager.isEverAuthorized(uuid) ||
            (lastProbe != null && now - lastProbe < AUTO_REPROBE_COOLDOWN_MS)) {
            return;
        }
        if (details.state == io.github.onaiaku.artmoon.nvstream.http.ComputerDetails.State.OFFLINE ||
            details.state == io.github.onaiaku.artmoon.nvstream.http.ComputerDetails.State.UNKNOWN) {
            return;
        }
        final String address = details.activeAddress != null ? details.activeAddress.address :
                details.localAddress != null ? details.localAddress.address :
                details.remoteAddress != null ? details.remoteAddress.address : null;
        if (address == null) {
            return;
        }
        lastAutoProbeAtMs.put(uuid, now);
        authManager.setListener(uuid, new io.github.onaiaku.artmoon.artlight.HostAuthManager.Listener() {
            @Override
            public void onAuthState(String u, String state, String pin) {
                // Silent: chip repaint only, never showAuthPinDialog. A "pending"
                // here means the host no longer knows us — the chip hides and
                // the user re-pairs manually via long-press.
                pcGridAdapter.updateAuthStateByUuid(u, state);
            }
        });
        authManager.probe(uuid, address);
    }

    /**
     * The Control pairing popup: "ArtLight on <host> is asking to allow this
     * device" with the 4-digit PIN in large monospace, matching the desktop's
     * stPinDialog. Dismissable; auto-closes when the host approves us.
     */
    private void showAuthPinDialog(final io.github.onaiaku.artmoon.nvstream.http.ComputerDetails details,
                                   final String pin) {
        if (authPinDialog != null && authPinDialog.isShowing()) {
            return; // already up; host re-polls keep the same PIN while pending
        }
        final String hostAddr = details.activeAddress != null ? details.activeAddress.address : "";
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle(getResources().getString(R.string.am_auth_title));
        b.setMessage(getResources().getString(R.string.am_auth_message,
                details.name, hostAddr));
        b.setView(buildAuthPinView(pin));
        b.setNegativeButton(android.R.string.cancel, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                authPinDialog = null;
            }
        });
        authPinDialog = b.show();
    }

    private android.view.View buildAuthPinView(String pin) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(pin);
        tv.setTextSize(52);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tv.setLetterSpacing(0.25f);
        tv.setTextAlignment(android.view.View.TEXT_ALIGNMENT_CENTER);
        tv.setPadding(0, 24, 0, 24);
        tv.setTextColor(getResources().getColor(R.color.am_accent_hi));
        return tv;
    }

    private void dismissAuthPinDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (authPinDialog != null) {
                    try {
                        authPinDialog.dismiss();
                    } catch (Exception ignored) {
                    }
                    authPinDialog = null;
                }
            }
        });
    }

    /**
     * ArtLight power actions — mirrors the desktop's PowerDialog: Shut down,
     * or Update and shut down (installs pending updates first). Destructive:
     * confirm dialog, then AUTH1-signed SHUTDOWN(_UPDATE) via ArtLightBridge.
     */
    private void doArtLightPowerMenu(ComputerDetails details) {
        if (details == null) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.am_power_menu)
                .setMessage(R.string.am_power_confirm)
                .setPositiveButton(R.string.am_power_shutdown, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        new io.github.onaiaku.artmoon.artlight.ArtLightBridge(PcView.this)
                                .sendShutdown(details.activeAddress != null ? details.activeAddress.address
                                              : details.remoteAddress != null ? details.remoteAddress.address
                                              : details.localAddress.address);
                        Toast.makeText(PcView.this, getResources().getString(R.string.am_power_sent), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.am_power_update_shutdown, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        new io.github.onaiaku.artmoon.artlight.ArtLightBridge(PcView.this)
                                .sendShutdownUpdate(details.activeAddress != null ? details.activeAddress.address
                                              : details.remoteAddress != null ? details.remoteAddress.address
                                              : details.localAddress.address);
                        Toast.makeText(PcView.this, getResources().getString(R.string.am_power_sent), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.hostCount(); i++) {
            ComputerObject computer = pcGridAdapter.hostAt(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();
                rebuildHostTabs();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        // ⚠️ hostCount()/hostAt(), NOT getCount()/getItem(): the stage renders one host, and
        // iterating that one would miss every other host and add it to the list a second time.
        for (int i = 0; i < pcGridAdapter.hostCount(); i++) {
            ComputerObject computer = pcGridAdapter.hostAt(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();

        // v10: keep the picker row's selected-host pill in sync with the
        // first (sorted) host — the render's center pill carries the
        // selected host's name.
        rebuildHostTabs();

        // Desktop parity (HomeScreen.qml hostProbes): silently re-check the
        // ArtLight auth state of hosts we've paired with before, so the
        // Authorised chip repaints itself on app open. Never touches hosts
        // we haven't paired — no unprompted allow-requests.
        maybeAutoReprobeArtLight(details);
    }

    // ── the host strip — HomeScreen.qml:1821-1970, ported ─────────────────────
    // One tab per host, "+  Add a host" as the LAST tab (the desktop's model is hostCount + 1),
    // and a status dot on each coloured by the SAME semantics the desktop uses — online+paired
    // green, online amber, offline grey, unknown dimmer — never the accent, because a user who
    // picks a red accent must still read "online" as green.
    //
    // Rebuilt from the list rather than kept in sync, so a host appearing or leaving cannot
    // leave a stale tab behind.

    private void rebuildHostTabs() {
        android.widget.LinearLayout row = findViewById(R.id.am_tab_row);
        if (row == null) {
            return;
        }

        io.github.onaiaku.artmoon.ui.AmScale s =
                io.github.onaiaku.artmoon.ui.AmScale.ofScreen(this);
        final int hosts = (pcGridAdapter != null) ? pcGridAdapter.hostCount() : 0;

        // The desktop draws both triggers only when there is at least one host
        // (HomeScreen.qml:1852 — `model: homeScreen.hostCount >= 1 ? [...] : []`). With nothing to
        // page, a pair of triggers pointing at nothing is noise.
        View ltHint = findViewById(R.id.am_pg_up);
        if (ltHint != null) {
            ltHint.setVisibility(hosts >= 1 ? View.VISIBLE : View.GONE);
        }
        View rtHint = findViewById(R.id.am_pg_dn);
        if (rtHint != null) {
            rtHint.setVisibility(hosts >= 1 ? View.VISIBLE : View.GONE);
        }

        // The shoulders draw an ActionHint, not the letters "LT"/"RT": HomeScreen.qml:1861 and
        // 1957 put one at size _px(28) inside the 44x30 box. With a pad it is the vendor glyph,
        // without one it is the key chip reading the keyboard equivalent — which is what these
        // two carry (PgUp/PgDn, HomeScreen.qml:1853 and 1949).
        applyCapLetter((android.widget.TextView) findViewById(R.id.am_pg_up_cap), 28f);
        applyCapLetter((android.widget.TextView) findViewById(R.id.am_pg_dn_cap), 28f);

        // ⚠️ REBUILD ONLY WHEN THE SET OF HOSTS CHANGES.
        //
        // This method used to removeAllViews() and re-inflate every tab on EVERY host update, and
        // updateComputer() runs on the discovery cadence — it does not wait for anything to actually
        // differ. So the whole strip was destroyed and re-made every few seconds, and the selected
        // tab went without its pill for the frame in between: a flash on the selected host's title
        // with nothing behind it on the screen to explain it. The same hosts in the same order are
        // the same tabs; the label and the state dot are the only things under them that can move,
        // and those are repainted in place below.
        String signature = hostTabsSignature(hosts);
        if (signature.equals(row.getTag(R.id.am_tab_label)) && row.getChildCount() == hosts + 1) {
            for (int i = 0; i < hosts; i++) {
                View tab = row.getChildAt(i);
                ComputerObject co = pcGridAdapter.hostAt(i);
                if (tab == null || co == null) {
                    continue;
                }
                android.widget.TextView label = tab.findViewById(R.id.am_tab_label);
                if (label != null) {
                    label.setText(co.details.name.toUpperCase());
                }
                View dot = tab.findViewById(R.id.am_tab_dot);
                if (dot != null) {
                    dot.setBackgroundResource(stateDotFor(co));
                }
            }
            updateTabSelection();
            return;
        }
        row.setTag(R.id.am_tab_label, signature);
        row.removeAllViews();

        for (int i = 0; i < hosts; i++) {
            final int index = i;
            ComputerObject co = pcGridAdapter.hostAt(i);
            if (co == null) {
                continue;
            }

            View tab = getLayoutInflater().inflate(R.layout.am_host_tab, row, false);
            io.github.onaiaku.artmoon.ui.AmScale.applyTo(tab, s);

            android.widget.TextView label = tab.findViewById(R.id.am_tab_label);
            if (label != null) {
                label.setText(co.details.name.toUpperCase());
            }

            View dot = tab.findViewById(R.id.am_tab_dot);
            if (dot != null) {
                dot.setBackgroundResource(stateDotFor(co));
            }

            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (pcGridAdapter != null) {
                        pcGridAdapter.setStageIndex(index);
                        updateTabSelection();
                    }
                }
            });
            row.addView(tab);
        }

        // "+  Add a host" — carries its own id so padActionTargets() still finds it and so the
        // owned d-pad model keeps its third slot.
        android.widget.TextView add = new android.widget.TextView(this);
        add.setId(R.id.manuallyAddPcText);
        add.setText("+  " + getString(R.string.am_picker_add));
        add.setGravity(android.view.Gravity.CENTER);
        // ⚠️ UNITS — this block is why "+ Add a host" survived two rounds of fixes. Every box
        // dimension below feeds a PIXEL API (setPadding, LayoutParams, margins), so it goes
        // through AmScale.lengthPx(). It used to go through AmScale.dp(), which returns a *dp
        // number*: on the Fold that made the tab 28 PIXELS tall where the QML's _px(40) is 62px
        // — less than half the height of every tab beside it and shorter than its own 19dp
        // label, so the label overflowed its box and the pill read as a shape cutting through
        // the text. THE TEXT SIZE IS THE ONE EXCEPTION: COMPLEX_UNIT_DIP converts dp to px
        // itself, so that call is correct on dp() and must stay on it.
        add.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, s.dp(19));
        add.setTextColor(getResources().getColor(R.color.am_text3));
        // Base look: no background. tabIndex never lands on "Add a host" — clicking it opens the
        // add screen — so it is never the selected tab, and HomeScreen.qml:1896 gives an
        // unselected tab `color: "transparent"`. applyTabBackground() keeps it that way.
        applyTabBackground(add, false);
        add.setFocusable(false);
        add.setFocusableInTouchMode(false);
        add.setClickable(true);
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, AddComputerManually.class));
            }
        });
        android.widget.LinearLayout.LayoutParams alp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        s.lengthPx(40f, this));
        alp.leftMargin = s.lengthPx(8f, this);
        add.setLayoutParams(alp);
        add.setPadding(s.lengthPx(20f, this), 0, s.lengthPx(20f, this), 0);
        row.addView(add);

        updateTabSelection();
    }

    /**
     * What the strip is showing right now: every host's uuid, in order.
     *
     * Held as a tag on the row rather than in a field so it cannot disagree with the views it
     * describes. If the row carries no tag — a fresh activity, a rotation, a process restart — the
     * signature never matches, the tabs are built once, and then they stay built for as long as the
     * set of hosts holds.
     */
    private String hostTabsSignature(int hosts) {
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < hosts; i++) {
            ComputerObject co = pcGridAdapter != null ? pcGridAdapter.hostAt(i) : null;
            sig.append(co == null ? "?" : co.details.uuid).append('|');
        }
        return sig.toString();
    }

    /** A host's state dot: unknown / online+paired / online / offline (the desktop's tab strip). */
    private static int stateDotFor(ComputerObject co) {
        if (co.details.state == ComputerDetails.State.UNKNOWN) {
            return R.drawable.am_dot_auth;
        }
        if (co.details.state == ComputerDetails.State.ONLINE
                && co.details.pairState == PairState.PAIRED) {
            return R.drawable.am_dot_online;
        }
        if (co.details.state == ComputerDetails.State.ONLINE) {
            return R.drawable.am_dot_pairing;
        }
        return R.drawable.am_dot_offline;
    }

    /** Selected tab = the host the stage is currently showing. */
    private void updateTabSelection() {
        android.widget.LinearLayout row = findViewById(R.id.am_tab_row);
        if (row == null) {
            return;
        }
        int current = (pcGridAdapter != null) ? pcGridAdapter.getStageIndex() : 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            boolean isTab = child.getId() != R.id.manuallyAddPcText;
            boolean selected = isTab && i == current;
            applyTabBackground(child, selected);
            if (isTab) {
                android.widget.TextView label = child.findViewById(R.id.am_tab_label);
                if (label != null) {
                    label.setTextColor(getResources().getColor(
                            selected ? R.color.am_text : R.color.am_text3));
                }
            }
        }
    }

    /**
     * A tab's body — HomeScreen.qml:1893-1900.
     *
     * <pre>
     *   height: _px(40)   radius: _px(9)
     *   color:        _selected || _hovered ? Theme.cardHigh : "transparent"
     *   border.width: _focused ? 2 : 1
     *   border.color: _focused ? Theme.accent : _selected ? Theme.lineHigh : "transparent"
     * </pre>
     *
     * <p>Three states, and the "+  Add a host" tab carries all three of them. On the desktop the
     * add tab is a TAB LIKE ANY OTHER: pick it and it is drawn cardHigh with a border, exactly
     * as a host tab is. It must not be special-cased out of having a body — a bare word is a
     * different screen from every other platform.
     *
     * <p>This is the fix for what Nik has been pointing at for three rounds. The port drew a
     * real pill here (`am_picker_pill`, radius <em>20dp</em>) on a tab whose height had been
     * computed with the wrong unit and came out 28 <em>pixels</em> tall. A 44px radius on a 28px
     * box is a radius past half the height, which is precisely the shape that draws its two end
     * caps through each other — a pill sliced across the middle with the label hanging out of
     * it. Two commits ago the tablet was removed instead of resized, so the label has been
     * sitting there bare since, and nothing about it changed when it was "fixed".
     *
     * <p>Now the box is the QML's _px(40) — see the units note in rebuildHostTabs — so the body
     * goes back on, and the pad's target lights it through {@code setActivated}, the same
     * mechanism every other control on this screen uses.
     */
    private void applyTabBackground(View tab, boolean selected) {
        if (tab == null) {
            return;
        }
        io.github.onaiaku.artmoon.ui.AmScale sc =
                io.github.onaiaku.artmoon.ui.AmScale.ofScreen(this);
        float radius = sc.radiusPx(9f, this);
        int cardHigh = getResources().getColor(R.color.am_card_high);
        int lineHigh = getResources().getColor(R.color.am_line_high);
        int accent = getResources().getColor(R.color.am_accent);
        // _px(1) / _px(2), in px, never below a hairline.
        int hair = Math.max(1, sc.lengthPx(1f, this));
        int lit = Math.max(2, sc.lengthPx(2f, this));

        android.graphics.drawable.GradientDrawable rest = tabShape(
                android.graphics.Color.TRANSPARENT, radius, 0,
                android.graphics.Color.TRANSPARENT);
        android.graphics.drawable.GradientDrawable on = selected
                ? tabShape(cardHigh, radius, hair, lineHigh) : rest;

        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_activated},
                tabShape(cardHigh, radius, lit, accent));
        states.addState(new int[]{}, on);
        tab.setBackground(states);
    }

    private android.graphics.drawable.GradientDrawable tabShape(
            int fill, float radius, int strokeWidth, int strokeColor) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radius);
        gd.setColor(fill);
        gd.setStroke(strokeWidth, strokeColor);
        return gd;
    }

    /**
     * Hand the system bars their own strip of the screen back.
     *
     * <p>targetSdk 36 with Android 15+ means edge-to-edge is enforced: the window is laid out
     * under the status bar and under the navigation/gesture bar, and the app is the only one who
     * can keep its content out from under them. Nothing in this app asked for edge-to-edge, and
     * nothing handled it — so the footer's band sat with its bottom edge ON the window's bottom
     * edge, which on the Fold is behind the gesture bar: "almost falling off the screen". The
     * brand row had the mirror of it at the top, under the status bar.
     *
     * <p>The desktop has no equivalent and the port should not invent one: AppShell draws its
     * footer prompts 44px from the bottom of a window the app owns outright. So this is the one
     * place where Android's answer is right and the QML has nothing to say — the port's job is
     * to put the desktop's 44dp band at the bottom of the area the app actually has.
     *
     * <p>Two rules, and both matter:
     *
     * <ul>
     *   <li>The footer GROWS by the bottom inset and then pads it back out. Padding alone would
     *       squash the prompts into whatever sliver was left of a 31dp band.</li>
     *   <li>The brand row's own top margin stays the desktop's {@code _px(22)} and the status
     *       bar's height is added to it — scaling is not re-run, this is a one-off offset.</li>
     * </ul>
     */
    private void applySystemBarInsets() {
        final View root = findViewById(R.id.am_home_root);
        final View brand = findViewById(R.id.am_brand_row);
        final View footer = findViewById(R.id.am_footer);
        if (root == null || brand == null || footer == null) {
            return;
        }

        final int brandTopBase = (brand.getLayoutParams()
                instanceof android.view.ViewGroup.MarginLayoutParams)
                ? ((android.view.ViewGroup.MarginLayoutParams) brand.getLayoutParams()).topMargin
                : brand.getPaddingTop();
        final int footerHeightBase = footer.getLayoutParams().height;

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars =
                            insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }

                // ⚠️ THE STATUS BAR HAS ALREADY BEEN TAKEN OFF US BY THE TIME THIS RUNS.
                // UiHelper.notifyNewRootView pads android.R.id.content by the TAPPABLE insets on
                // Q+ ("Draw under the status bar on Android Q devices"), and insets dispatch
                // parent-first, so that padding is in place before this listener is reached. The
                // view's own top offset inside the window IS that take.
                //
                // Adding `top` to the brand row on top of it counted the status bar twice, and on
                // the Fold's cover screen that was 79px of the 191px between the top of the screen
                // and the top of the brand row — 72.8dp of dead space above the logo, measured on
                // the device, on a window only 475.4dp tall. The desktop has no second inset to
                // add: AppShell.qml:1741 starts the brand row at its own _px(22) from the top of
                // a window the app owns outright.
                //
                // So give back only what we actually lost. On a screen where nothing else has
                // inset us (`already` = 0, e.g. the TV path that sets no padding) this is exactly
                // the old behaviour.
                int already = 0;
                android.view.ViewParent parent = root.getParent();
                if (parent instanceof View) {
                    already = ((View) parent).getPaddingTop();
                }
                if (brand.getLayoutParams()
                        instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams lp =
                            (android.view.ViewGroup.MarginLayoutParams) brand.getLayoutParams();
                    int want = brandTopBase + Math.max(0, top - already);
                    if (lp.topMargin != want) {
                        lp.topMargin = want;
                        brand.setLayoutParams(lp);
                    }
                }

                android.view.ViewGroup.LayoutParams flp = footer.getLayoutParams();
                int h = footerHeightBase + bottom;
                if (flp.height != h) {
                    flp.height = h;
                    footer.setLayoutParams(flp);
                }
                if (footer.getPaddingBottom() != bottom) {
                    footer.setPadding(footer.getPaddingLeft(), footer.getPaddingTop(),
                            footer.getPaddingRight(), bottom);
                }
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /**
     * ActionHint's cap letter — ActionHint.qml:79:
     *
     *     font.pixelSize: Math.max(11, Math.round(hint.size * 0.46))   // bold
     *
     * where {@code hint.size} is the button size ALREADY through {@code _px()}. The floor of 11
     * is the whole reason this cannot be a number in the layout: AmScale would scale the floor
     * down with everything else, and the desktop's floor does not move.
     */
    private void applyCapLetter(android.widget.TextView cap, float baseSize) {
        if (cap == null) {
            return;
        }
        io.github.onaiaku.artmoon.ui.AmScale sc = io.github.onaiaku.artmoon.ui.AmScale.ofScreen(this);
        int size = Math.round(baseSize * sc.uiScale());
        cap.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,
                Math.max(11, Math.round(size * 0.46f)));
    }

    /** The LT/RT shoulders (and PgUp/PgDn) page the stage one host at a time. */
    private void cycleHost(int dir) {
        pageHostList(dir);
        if (pcGridView != null) {
            pcGridView.post(new Runnable() {
                @Override
                public void run() {
                    updateTabSelection();
                }
            });
        }
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_grid_view;
    }

    /**
     * The hero card's host: first ONLINE entry, else the first entry. Used by
     * the footer Shutdown keycap (P) — desktop's power dialog targets the
     * selected host; the phone's selected host is the hero card.
     */
    private ComputerObject getHeroComputer() {
        if (pcGridAdapter == null || pcGridAdapter.getCount() == 0) {
            return null;
        }
        // The stage's host — the one on screen. It used to hunt for the first ONLINE host, which
        // named a host the user was not looking at once the stage showed a different one.
        return (ComputerObject) pcGridAdapter.getItem(0);
    }

    /**
     * v10: wire the hero card's Open / Options buttons to the same flows as
     * the grid tap. Called from the adapter during every populateView, so
     * the click listeners always carry the freshest computer object.
     */
    public void bindHeroCardActions(View cardView, ComputerObject computer) {
        if (cardView == null || computer == null) {
            return;
        }
        View open = cardView.findViewById(R.id.am_action_open);
        if (open != null) {
            // 2026-09-07 Nik UX fix: the action button must read the truth.
            // Unpaired -> "Pair", paired -> "Open". Label rides the same
            // state the click handler already routes on.
            if (open instanceof android.widget.TextView) {
                ((android.widget.TextView) open).setText(
                    computer.details.pairState == PairState.PAIRED
                        ? R.string.am_action_open : R.string.am_action_pair);
            }
            // Desktop FocusFrame parity: paint the ring on keyboard/pad focus.
            open.setOnFocusChangeListener((v, hasFocus) -> v.setActivated(hasFocus));
            open.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                        computer.details.state == ComputerDetails.State.OFFLINE) {
                        if (v != null && v.isAttachedToWindow()) {
                            openContextMenu(v);
                        }
                    } else if (computer.details.pairState != PairState.PAIRED) {
                        doPair(computer.details);
                    } else {
                        doAppList(computer.details, false, false);
                    }
                }
            });
        }
        View options = cardView.findViewById(R.id.am_action_options);
        if (options != null) {
            options.setOnFocusChangeListener((v, hasFocus) -> v.setActivated(hasFocus));
            options.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Anchor to the actually-clicked view: the captured cardView
                    // can be detached by host polling before the press lands
                    // (NPE in ViewParent.showContextMenuForChild).
                    if (v != null && v.isAttachedToWindow()) {
                        openContextMenu(v);
                    }
                }
            });
        }
        // The panel pair — Resume/Play again under the cover, with Stop under IT while something
        // is actually streaming. One control set, not two buttons: HostStage.qml:250-260 (what
        // running means), :426-440 (the label and what Stop rides on), :1699-1741 (the pair), and
        // HomeScreen.qml:2114-2122 (the three actions).
        //
        //   "is the host streaming?"  On the desktop that is `runningApp`, and on Android it is
        //                             the same field: NvHTTP.getCurrentGame() deliberately
        //                             returns 0 unless the host's own state ends in
        //                             _SERVER_BUSY, so `runningGameId != 0` IS _hasRunning here
        //                             and not "the last game it played".
        //   the label                 "Resume" while something is running, "Play again" when
        //                             nothing is (the desktop's _panelActionKind).
        //   Stop                      drawn ONLY while something is running, and the only red one
        //                             (_stopReachable). Nothing running -> the pair is one button.
        //   Play again  ("continue")  resumeLastPlayed: the game the block names, found BY NAME in
        //                             the host's own app list and started — not the library.
        //   Resume      ("resume")    resumeRunning: the host's current game, straight from its id.
        //   Stop        ("stop")      quit the running session, behind a confirmation.
        //
        // This used to fire the Open flow, and that is exactly why pressing Play again opened the
        // app list: the button had a listener, but not this one. Every route below ends somewhere
        // real — resolution failure lands on the app list, which is the desktop's own fallback.
        final boolean running = computer.details.runningGameId != 0;

        // The block's identity comes from the record THIS client keeps (PlaytimeManager), so it is
        // pushed before the button is resolved against it: the button launches the name the block
        // shows, and a block still holding last bind's game would launch that instead.
        pushLastPlayed(computer.details);

        final String lastGame = pcGridAdapter != null
                ? pcGridAdapter.lastGameNameFor(computer.details.uuid) : null;

        // The block picks between the running session and the last game for the badge, the title
        // and the cover (HostStage.qml:1616-1681), so it needs the running one as well as the gate.
        // The id is immediate; the name and the artwork come from the host's own list, and a session
        // Nik can already see running on the PC should not wait for the metrics poll to be named.
        final int runningId = running ? computer.details.runningGameId : 0;
        if (pcGridAdapter != null) {
            if (pcGridAdapter.runningGameIdFor(computer.details.uuid) != runningId) {
                LimeLog.info("running gate: " + computer.details.uuid + " runningId=" + runningId
                        + " (was " + pcGridAdapter.runningGameIdFor(computer.details.uuid) + ")");
                // Session changed, or ended: drop the old name and cover with it. Ending is the
                // one that matters — the block must go back to the last game, not keep the
                // running one's picture over the words "Last played".
                pcGridAdapter.setRunningGameByUuid(computer.details.uuid, runningId, null, null);
            }
            if (runningId != 0
                    && !pcGridAdapter.hasRunningGameInfo(computer.details.uuid, runningId)) {
                fetchRunningGame(computer.details, runningId);
            }
        }

        View resume = cardView.findViewById(R.id.am_panel_resume);
        if (resume instanceof android.widget.TextView) {
            ((android.widget.TextView) resume).setText(
                    running ? R.string.am_panel_resume : R.string.am_panel_play_again);
            resume.setOnFocusChangeListener((v, hasFocus) -> v.setActivated(hasFocus));
            resume.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    panelContinueOrResume(computer, running, lastGame);
                }
            });
        }

        View stop = cardView.findViewById(R.id.am_panel_stop);
        if (stop != null) {
            stop.setVisibility(running ? View.VISIBLE : View.GONE);
            stop.setOnFocusChangeListener((v, hasFocus) -> v.setActivated(hasFocus));
            stop.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    panelStop(computer, running);
                }
            });
        }
    }

    /**
     * The desktop's Play again / Resume, from HomeScreen.qml:2114-2122.
     *
     * running -> resumeRunning(): the host has already said which game it is, so there is nothing
     *            to look up — the running id is the whole answer.
     * else    -> resumeLastPlayed(): find the game the block names in the host's list and start it.
     *            The lookup is a network call, so it goes off the UI thread; anything that stops us
     *            resolving the name lands on the app list, which is the desktop's own fallback
     *            ("the gate said it was there when the card was drawn; if it has gone since, the
     *            library is the honest place to land").
     */
    private void panelContinueOrResume(final ComputerObject computer, final boolean running,
                                       final String lastGame) {
        if (managerBinder == null) {
            Toast.makeText(this, getResources().getString(R.string.error_manager_not_running),
                           Toast.LENGTH_LONG).show();
            return;
        }
        if (running) {
            ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId, false),
                                 computer.details, managerBinder);
            return;
        }
        if (lastGame == null || lastGame.isEmpty()) {
            doAppList(computer.details, false, false);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final NvApp found = ServerHelper.findAppNamed(PcView.this, computer.details,
                                                              managerBinder, lastGame);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (found == null) {
                            doAppList(computer.details, false, false);
                        } else {
                            ServerHelper.doStart(PcView.this, found, computer.details, managerBinder);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * The desktop's Stop (HomeScreen.qml stopRunning). Nothing running means nothing to stop and
     * nothing to ask — the card redraws from the host's next status and the Stop goes with it.
     * Otherwise: confirm first, then quit — the same confirmation the overflow menu's Quit uses,
     * naming the game when the host's cached list can name it.
     */
    private void panelStop(final ComputerObject computer, final boolean running) {
        if (!running || managerBinder == null) {
            return;
        }
        UiHelper.displayQuitConfirmationDialog(this, runningAppName(computer.details),
                new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                            new NvApp("app", 0, false), managerBinder, null);
                    }
                }, null);
    }

    /**
     * Tells the card what THIS client last played on this host, from the record kept on this
     * machine ({@code settings/PlaytimeManager}) — the port of the desktop's
     * {@code ComputerModel::lastPlayedFor()} and the reason the desktop fixed this in 5.6.1.
     *
     * <p>Until now the block was named by the host's own last session, which only our ArtLight stack
     * can answer: on a plain Sunshine, Apollo or Vibepollo host the block never appeared at all, and
     * on ours it named whatever the host last ran, possibly from another device. The record is the
     * client's, so it works against every host this app can stream from.</p>
     *
     * <p>&#9888; One deliberate difference from the desktop, and it is about a state the desktop does
     * not have. {@code lastPlayedFor()} returns an empty map when the name is not in the host's app
     * list, and the block is then not drawn at all — "rather than offering a button that would
     * fail". That assumes a populated list, which the desktop model always has. This screen does not
     * fetch one; it is filled in as a side effect of launching. So when the list is absent the block
     * is still drawn from the record, with no cover: the record is itself proof the game was
     * playable here, and hiding it because this screen has not fetched a list yet would lose the
     * block on every fresh visit. The launch falls back to the app list on resolution failure, which
     * is the desktop's own fallback.</p>
     */
    private void pushLastPlayed(ComputerDetails details) {
        if (pcGridAdapter == null || details == null || details.uuid == null) {
            return;
        }

        final PlaytimeManager.PlaytimeRecord rec =
                PlaytimeManager.lastPlayedOn(this, details.uuid);
        if (!rec.valid || rec.name == null || rec.name.isEmpty()) {
            // No name, no block — the placeholder takes its place (HostStage.qml:215-216, 1611-1613,
            // and the hint at 1898-1957). It needs the host's name, which is why it is passed here.
            pcGridAdapter.setLastPlayedByUuid(details.uuid, details.name, null, null, null, null);
            return;
        }

        final int appId = appIdForName(details, rec.name);

        // The artwork this client already pulled from the host — cache only, never a fetch
        // (computermodel.cpp:1411-1417). No cached art means no art: HeroCover with no source
        // draws nothing, and a picture-shaped hole is not a picture.
        final android.graphics.Bitmap art = appId == 0 ? null : cachedBoxArt(details.uuid, appId);

        pcGridAdapter.setLastPlayedByUuid(details.uuid, details.name, rec.name, art,
                PlaytimeManager.formatDuration(rec.totalSeconds),
                formatAgo(rec.lastPlayedUtc));
    }

    /**
     * The host's own id for a game named in our record, from the app list this screen already has
     * cached; 0 when it is not there. By NAME and not by a stored id, because ids move when the
     * host rebuilds its app list — the name is what survives, which is why the record is keyed on
     * it (playtime.h:10-17).
     */
    private int appIdForName(ComputerDetails details, String name) {
        if (details.rawAppList == null || details.rawAppList.isEmpty()) {
            return 0;
        }
        try {
            for (NvApp a : NvHTTP.getAppListByReader(new java.io.StringReader(details.rawAppList))) {
                if (a.getAppName() != null && a.getAppName().equalsIgnoreCase(name)) {
                    return a.getAppId();
                }
            }
        } catch (Exception e) {
            LimeLog.info("lastPlayed: could not read the host's app list: " + e.getMessage());
        }
        return 0;
    }

    /** The box art already on disk for this host and app, or null. Never fetches. */
    private android.graphics.Bitmap cachedBoxArt(String uuid, int appId) {
        try {
            java.io.File f = new DiskAssetLoader(this).getFile(uuid, appId);
            if (f != null && f.exists()) {
                return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            }
        } catch (Exception e) {
            LimeLog.info("lastPlayed: no cached cover: " + e.getMessage());
        }
        return null;
    }

    /**
     * The badge's age, in the vocabulary the host's own LASTSESSION reply used, so the card reads
     * the way it always has even though nothing about it comes from the host any more
     * (computermodel.cpp:1507-1530).
     */
    private String formatAgo(long utcMillis) {
        if (utcMillis <= 0) {
            return "";
        }
        final long mins = (System.currentTimeMillis() - utcMillis) / 60000L;
        if (mins < 1) {
            return "just now";
        }
        if (mins < 60) {
            return mins + " min ago";
        }
        final long hours = mins / 60;
        if (hours < 24) {
            return hours + " h ago";
        }
        final long days = hours / 24;
        if (days == 1) {
            return "yesterday";
        }
        if (days < 30) {
            return days + " days ago";
        }
        // Past a month the exact figure stops meaning anything — what matters is that it has been
        // a while.
        final long months = days / 30;
        return months <= 1 ? "a month ago" : months + " months ago";
    }

    /**
     * The running game's name, if the host's app list is already cached here; null if not. Delegates to
     * NvHTTP so the card and the poller's playtime record name a session by one implementation.
     */
    private String runningAppName(ComputerDetails details) {
        if (details == null) {
            return null;
        }
        return NvHTTP.appNameForId(details.rawAppList, details.runningGameId);
    }

    /** Sessions whose name and artwork are already being looked up, so a rebind does not re-ask. */
    private final java.util.Set<String> runningFetchInFlight = new java.util.HashSet<>();

    /**
     * Name and box art for the session the host has running, off the UI thread, once per session.
     * A rebind does not re-fetch: the adapter remembers the pair against the id, and this set
     * catches the shorter race — a bind landing again before the first answer has come back.
     */
    private void fetchRunningGame(final ComputerDetails details, final int gameId) {
        final String key = details.uuid + "#" + gameId;
        if (!runningFetchInFlight.add(key)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ServerHelper.RunningAppInfo info =
                        ServerHelper.runningAppInfo(PcView.this, details, managerBinder, gameId);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        runningFetchInFlight.remove(key);
                        // Only paint it if that is still the session: the host can end one and
                        // start another while the lookup is in the air, and the adapter's own
                        // guard is the id it was asked for.
                        if (pcGridAdapter != null && info != null
                                && details.runningGameId == gameId) {
                            pcGridAdapter.setRunningGameByUuid(details.uuid, gameId,
                                                               info.name, info.cover);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * Long-press menu on the hero card: set / change / remove the per-PC
     * background image. Called from PcGridAdapter.populateView.
     */
    public void showHeroBackgroundMenu(String uuid) {
        if (heroBgManager == null) {
            heroBgManager = new HostBackgroundManager(this);
        }
        final String uid = uuid;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle(computerNameForUuid(uid));
        java.util.List<String> options = new java.util.ArrayList<>();
        if (heroBgManager.has(uid)) {
            options.add(getString(R.string.am_hero_bg_change));
            options.add(getString(R.string.am_hero_bg_remove));
        } else {
            options.add(getString(R.string.am_hero_bg_set));
        }
        final String[] items = options.toArray(new String[0]);
        b.setItems(items, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                if (heroBgManager.has(uid) && which == 1) {
                    // Remove
                    heroBgManager.remove(uid);
                    Toast.makeText(PcView.this, R.string.am_hero_bg_removed, Toast.LENGTH_SHORT).show();
                    refreshGridAdapter();
                } else {
                    // Set / change — launch the system document picker
                    heroBgPendingUuid = uid;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    try {
                        startActivityForResult(
                                Intent.createChooser(intent, getString(R.string.am_hero_bg_set)),
                                REQ_HERO_BG);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(PcView.this, R.string.am_hero_bg_set, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        b.show();
    }

    private String computerNameForUuid(String uuid) {
        if (pcGridAdapter != null) {
            for (int i = 0; i < pcGridAdapter.hostCount(); i++) {
                ComputerObject co = pcGridAdapter.hostAt(i);
                if (co != null && uuid.equals(co.details.uuid)) {
                    return co.details.name;
                }
            }
        }
        return getString(R.string.am_hero_bg_set);
    }

    /**
     * Move the stage one host, either way (HomeScreen.qml:1871 `homeScreen.cycleHost(dir)`, which
     * the strip's triggers and the tabs both call).
     *
     * It used to smooth-scroll the GridView by a screenful, which is what let a swipe walk from
     * one host to the next — a thing no other platform does, because the tabs are how you change
     * host. The stage now follows the strip.
     */
    private void pageHostList(int dir) {
        if (pcGridAdapter == null || pcGridAdapter.hostCount() == 0) {
            return;
        }
        pcGridAdapter.setStageIndex(pcGridAdapter.getStageIndex() + dir);
    }

    private void refreshGridAdapter() {
        // Force the bound rows to re-render with the new background state
        if (pcGridAdapter != null) {
            pcGridAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HERO_BG && resultCode == RESULT_OK
                && data != null && data.getData() != null
                && heroBgPendingUuid != null) {
            if (heroBgManager == null) {
                heroBgManager = new HostBackgroundManager(this);
            }
            if (heroBgManager.set(heroBgPendingUuid, data.getData())) {
                Toast.makeText(this, R.string.am_hero_bg_applied, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.am_hero_bg_failed, Toast.LENGTH_SHORT).show();
            }
            heroBgPendingUuid = null;
            refreshGridAdapter();
        }
    }

    /**
     * Gamepad mappings for the hosts screen (matches PromptBar glyphs):
     *   Y = Settings, B = exit ArtMoon. A is left to the grid's normal
     *   focused-item click so pairing/context flows stay intact.
     *
     * D-pad discipline (Nik's rule): the ONLY selectable things on the main
     * page are Open, Options and + Add a Host. In gamepad mode we route every
     * d-pad press ourselves — Up/Down move the host row, Right/Left hop to
     * Open/Options/plus-add — so focus can never silently land on the bare
     * GridView or any other view that has no visible highlight.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Classify FIRST. The base class normally does this in its own
        // dispatchKeyEvent, but we override it — so without this call the
        // mode is read one press stale: press 1 on a clean start is treated
        // as TOUCH and leaks to the framework's focus search (the ring
        // loss Nik saw). Classify before we ever read getMode().
        if (event.getAction() == KeyEvent.ACTION_DOWN
                || event.getAction() == KeyEvent.ACTION_UP) {
            InputModeManager.get().notifyKeyEvent(event);
        }
        boolean gamepad = InputModeManager.get().getMode() == InputModeManager.Mode.GAMEPAD;
        // Swallow the ACTION_DOWN half of every owned key — INCLUDING its repeats.
        //
        // ⚠️ The repeats used to fall through: `getRepeatCount() == 0` guarded this whole block, so
        // a HELD direction sent a stream of unowned events to the framework. Its geometric focus
        // search then landed on the tab strip — clickable, and therefore focusable — lighting that
        // tab for a frame until the next pad paint cleared it again, and the tab it lands on first
        // is the selected one. That is the flash Nik saw on the selected host's title every time he
        // moved the ring: not the tab being rebuilt, a ring the framework put on it that we took off
        // again. Repeats buy nothing here — the ring moves on ACTION_UP — so none of them need to
        // leave this method.
        if (gamepad && event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_Y:
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
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
                    finishAffinity();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    // A activates exactly what carries the ring (owned model).
                    paintPadFocus();
                    activatePadAction();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    // Nik's rule, kept: Up jumps to + Add a Host. By SLOT, not by a hard-coded
                    // index — the ring grew the panel's Resume/Stop (padActionTargets), so "2" is
                    // no longer Add a Host.
                    movePadToAdd();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Down only exits Add (back to Open); walls everywhere else on the card.
                    if (isOnAddSlot()) movePadTo(0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    movePadAction(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    movePadAction(-1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_L1:
                    cycleHost(-1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    cycleHost(1);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Owned gamepad focus model (desktop FocusFrame parity) -------------
    // padActionIndex is the SINGLE source of truth for which control carries
    // the ring: 0 = Open, 1 = Options, 2 = + Add a Host / Pair. We never ask
    // the framework where focus is and never call requestFocus in gamepad
    // mode — Android's focus search is geometric and unreliable on a
    // touch-first screen with no seeded focus. Views are dumb paint targets
    // driven by setActivated.

    /** The focused host row's card view (per-row buttons resolve inside it). */
    private View padRowView() {
        if (pcGridAdapter == null || pcGridAdapter.getCount() == 0) return null;
        return pcGridAdapter.getFocusedRowView();
    }

    /** Visible action targets in tab order: Open, Options, + Add a Host. */
    private View[] padActionTargets() {
        // The row view may be unbound mid-rebuild (host polling) — fall back
        // to any bound row carrying the buttons so the 3-slot invariant never
        // collapses to fewer slots.
        View row = null;
        // Prefer a LIVE row from the GridView's attached children. The
        // adapter's boundViews map can hold a stale view that never attaches
        // (measure-pass bind), which made paints spin in SKIP-retry loops.
        // GridView children are always attached when visible.
        if (pcGridView != null) {
            for (int i = 0; i < pcGridView.getChildCount(); i++) {
                View c = pcGridView.getChildAt(i);
                if (c != null && c.isAttachedToWindow()
                        && c.findViewById(R.id.am_action_open) != null) { row = c; break; }
            }
        }
        if (row == null) row = padRowView();
        if (row != null && !row.isAttachedToWindow()) row = null; // ghost from the map
        if (row == null || row.findViewById(R.id.am_action_open) == null) {
            row = pcGridAdapter != null ? pcGridAdapter.getFirstBoundRowWithActions() : null;
        }
        View open = row != null ? row.findViewById(R.id.am_action_open)
                                : findViewById(R.id.am_action_open);
        View options = row != null ? row.findViewById(R.id.am_action_options)
                                   : findViewById(R.id.am_action_options);
        // The panel pair — Resume / Play again, with Stop under it (HostStage.qml:1876-1895).
        // The desktop walks into them FROM the action row: Resume is one stop past its last button
        // and Stop is the stop after that, because "geometrically the pair is to the right of them,
        // which is what Right already means here" (HostStage.qml:1711-1716). Resolved off the same
        // row the other two come from, so a rebind can never pair them with the wrong card.
        View resume = row != null ? row.findViewById(R.id.am_panel_resume)
                                  : findViewById(R.id.am_panel_resume);
        View stop = row != null ? row.findViewById(R.id.am_panel_stop)
                                : findViewById(R.id.am_panel_stop);
        View add = findViewById(R.id.manuallyAddPcText);

        java.util.List<View> targets = new java.util.ArrayList<>();
        if (open != null && open.getVisibility() == View.VISIBLE) targets.add(open);
        if (options != null && options.getVisibility() == View.VISIBLE) targets.add(options);
        if (resume != null && shownInTree(resume)) targets.add(resume);
        if (stop != null && shownInTree(stop)) targets.add(stop);
        // Android's own extra stop, and it stays LAST. The strip's "+ Add a host" tab is the only
        // way to add a host on a phone-sized screen, but it is not on the card's line, so Left and
        // Right never reach it — Up does, which is Nik's rule.
        if (add != null && add.getVisibility() == View.VISIBLE) targets.add(add);
        return targets.toArray(new View[0]);
    }

    /**
     * VISIBLE, and so is every view above it.
     *
     * ⚠️ The panel column is GONE until the block has something to show, and a GONE parent leaves
     * its children's own visibility at VISIBLE — so a plain getVisibility() check would put an
     * invisible Resume button in the ring and the pad would stop on a control nobody can see.
     * getWindowVisibility() is the one-call version of this but answers GONE for a view that is
     * simply not attached yet, which is exactly the moment the ring is seeded and retried.
     */
    private static boolean shownInTree(View v) {
        for (View p = v; p != null; p = (p.getParent() instanceof View) ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /** The slot carrying the strip's "+ Add a host" tab, or -1 when there is none. */
    private int padAddSlot() {
        View[] t = padActionTargets();
        if (t.length == 0) return -1;
        return t[t.length - 1].getId() == R.id.manuallyAddPcText ? t.length - 1 : -1;
    }

    private boolean isOnAddSlot() {
        int add = padAddSlot();
        return add >= 0 && padActionIndex == add;
    }

    /** Up: jump to + Add a host wherever it currently sits in the ring. */
    private void movePadToAdd() {
        int add = padAddSlot();
        if (add >= 0) movePadTo(add);
    }

    /**
     * The last slot Left/Right may stand on: the card's own controls, never the strip's tab.
     *
     * Without it the walk runs off the card and onto "+ Add a host", which sits in a different row
     * above — that is an Up move, and it is the only way in.
     */
    private int padLastCardSlot() {
        int add = padAddSlot();
        return (add < 0) ? padActionTargets().length - 1 : add - 1;
    }

    private final java.util.List<View> padPainted = new java.util.ArrayList<>();
    private boolean padRetryQueued = false;

    /**
     * Bind-time repaint hook: called by PcGridAdapter.getView() the moment a
     * fresh row exists. Synchronous (no post) so the ring is painted into the
     * same frame that binds the row — poll rebinds can never show a dark frame
     * (the "flashing" Nik reported) and keypresses never paint a row the poll
     * is about to detach. PcView remains the SINGLE writer; this is just an
     * earlier trigger for the same paint.
     */
    public void syncPadFocusNow() {
        paintPadFocus();
    }

    /** Paint the ring on padActionTargets()[padActionIndex], nowhere else. */
    private void paintPadFocus() {
        // Resolve the target FIRST. Host polling leaves stale (detached) rows
        // in the adapter's bound-view map; painting one lights a ghost off the
        // live hierarchy while the visible pill sits dark (Nik's flash).
        // If the resolved target is not attached to the window, bail WITHOUT
        // clearing anything so the live ring is never wiped by a stale paint.
        View[] targets = padActionTargets();
        if (targets.length == 0) {
            android.util.Log.d("PadFocus", "paint: NO TARGETS idx=" + padActionIndex);
            return;
        }
        if (padActionIndex >= targets.length) padActionIndex = targets.length - 1;
        View t = targets[padActionIndex];
        if (!t.isAttachedToWindow()) {
            // A just-bound row attaches at the END of the layout pass, so a
            // bind-time paint always sees detached here. Bail WITHOUT clearing
            // the live ring (ghost protection), then retry next frame — the
            // view will be attached by then. Guarded so one retry is queued
            // at a time; the poll observer repaints anyway as a backstop.
            android.util.Log.d("PadFocus", "paint: SKIP detached idx=" + padActionIndex + " (retry queued)");
            if (!padRetryQueued) {
                padRetryQueued = true;
                if (pcGridView != null) {
                    pcGridView.post(() -> { padRetryQueued = false; paintPadFocus(); });
                } else {
                    padRetryQueued = false;
                }
            }
            return;
        }
        for (View v : padPainted) v.setActivated(false);
        padPainted.clear();
        // Evict stray framework focus FIRST: a stale focused view keeps its
        // state_focused ring alive and paints a second ring behind our back.
        View f = getCurrentFocus();
        if (f != null && f.isFocused()) f.clearFocus();
        t.setActivated(true);
        padPainted.add(t);
        String id = t.getId() != View.NO_ID
                ? t.getResources().getResourceEntryName(t.getId()) : "no-id";
        android.util.Log.d("PadFocus", "paint idx=" + padActionIndex
                + " target=" + id
                + " act=" + t.isActivated()
                + " vis=" + t.getVisibility()
                + " bg=" + (t.getBackground() != null));
    }

    /**
     * Seed the ring at launch: Open when a host exists, + Add a Host / Pair
     * when none does (Nik's rule). Runs after layout via post().
     */
    private void seedPadFocus() {
        padActionIndex = 0;
        if (pcGridAdapter != null && pcGridAdapter.getCount() > 0
                && pcGridAdapter.getFocusedPosition() == AdapterView.INVALID_POSITION) {
            pcGridAdapter.setFocusedPosition(0);
            if (pcGridView != null) pcGridView.setSelection(0);
        }
        if (pcGridView != null) {
            pcGridView.post(this::paintPadFocus);
        } else {
            paintPadFocus();
        }
    }

    /**
     * Right/Left along the CARD's own line: Open, Options, then the panel's Resume and Stop.
     *
     * The end is read from the live ring rather than hard-coded, because Resume only exists once
     * the block has something to show and Stop only exists while something is streaming — with
     * neither, Right from Options simply walls, exactly as it does on the desktop, which clamps on
     * _maxActionIndex (HostStage.qml:420-423) and never wraps.
     */
    private void movePadAction(int delta) {
        int last = padLastCardSlot();
        if (last < 0) return;
        int next = padActionIndex + delta;
        if (next > last) next = last;
        if (next < 0) next = 0;
        movePadTo(next);
    }

    /** Jump to a specific slot of the trio and repaint (no-op if unchanged). */
    private void movePadTo(int slot) {
        android.util.Log.d("PadFocus", "moveTo " + slot + " (was " + padActionIndex + ")");
        if (slot == padActionIndex) return;
        View[] targets = padActionTargets();
        if (targets.length == 0) return;
        if (slot >= targets.length) slot = targets.length - 1;
        padActionIndex = slot;
        paintPadFocus();
    }

    /** A activates exactly the control that carries the ring. */
    private void activatePadAction() {
        View[] targets = padActionTargets();
        if (targets.length == 0) return;
        if (padActionIndex >= targets.length) padActionIndex = targets.length - 1;
        targets[padActionIndex].performClick();
    }

    /** Up/Down on a host card row: flip hosts through the adapter (visible). */
    private void moveHostRow(int delta) {
        if (pcGridAdapter == null || pcGridAdapter.getCount() == 0) return;
        int pos = pcGridAdapter.getFocusedPosition();
        if (pos == AdapterView.INVALID_POSITION) pos = 0;
        int next = pos + delta;
        if (next < 0) next = 0;
        if (next > pcGridAdapter.getCount() - 1) next = pcGridAdapter.getCount() - 1;
        if (next == pos) return;              // clamped at either end
        pcGridAdapter.setFocusedPosition(next);
        if (pcGridView != null) {
            pcGridView.setSelection(next);
            // Buttons live inside the row view, which binds during the next
            // layout pass — repaint after it lands.
            pcGridView.post(this::paintPadFocus);
        }
    }

    /**
     * The floor travels twice as fast while a host is streaming — AmbientBackground.streaming, 6.0.0
     * (AppShell.qml hands it "any host streaming", HomeScreen.qml:669). The port already answers
     * that per card for the badge's live dot, so this asks it of every host, not just the one on
     * screen. Cheap: the host poll already rebuilds the rows every few seconds, and this rides the
     * same data-set change.
     */
    private void updateFloorStreaming() {
        View floor = findViewById(R.id.am_floor);
        if (!(floor instanceof io.github.onaiaku.artmoon.ui.AmbientFloor) || pcGridAdapter == null) {
            return;
        }
        boolean anyStreaming = false;
        for (int i = 0; i < pcGridAdapter.getCount(); ++i) {
            ComputerObject co = pcGridAdapter.hostAt(i);
            if (co != null && co.details != null && pcGridAdapter.isRunning(co.details.uuid)) {
                anyStreaming = true;
                break;
            }
        }
        ((io.github.onaiaku.artmoon.ui.AmbientFloor) floor).setStreaming(anyStreaming);
    }

    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        pcGridView = listView;

        // Host polling rebuilds rows every few seconds; the painted ring can
        // land on stale view objects and evaporate. Repaint after every data
        // change so exactly one of Open/Options/+Add is ALWAYS lit.
        pcGridAdapter.registerDataSetObserver(new android.database.DataSetObserver() {
            @Override
            public void onChanged() {
                if (pcGridView != null) {
                    pcGridView.post(PcView.this::paintPadFocus);
                }
                updateFloorStreaming();
            }
        });
        updateFloorStreaming();

        // Desktop FocusFrame parity: D-pad focus moves the accent ring, driven
        // through the adapter (same deterministic path as the picker's band).
        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int pos, long id) {
                pcGridAdapter.setFocusedPosition(pos);
                // Hosts arrive asynchronously — repaint the action ring so
                // Open exists on the freshly bound row before any keypress.
                if (InputModeManager.get().getMode() == InputModeManager.Mode.GAMEPAD
                        && arg0 != null) {
                    arg0.post(PcView.this::paintPadFocus);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
                pcGridAdapter.setFocusedPosition(AdapterView.INVALID_POSITION);
            }
        });

        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                    computer.details.state == ComputerDetails.State.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    doAppList(computer.details, false, false);
                }
            }
        });
        UiHelper.applyStatusBarPadding(listView);
        registerForContextMenu(listView);
        // The d-pad scroll bar flickering up during navigation is noise on a
        // controller-driven screen; keep it for touch, hide it in gamepad mode.
        if (InputModeManager.get().getMode() == InputModeManager.Mode.GAMEPAD) {
            listView.setVerticalScrollBarEnabled(false);
        }
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}
