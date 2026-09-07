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
        android.widget.TextView addPicker = findViewById(R.id.manuallyAddPcText);
        if (addPicker != null) {
            // Owned-model discipline: PcView.paintPadFocus() is the ONLY writer
            // of this pill's activated state. It is clickable, which auto-grants
            // focusability — undo that so framework focus can never park here and
            // its state_focused ring can never light (ghost second ring).
            addPicker.setFocusable(false);
            addPicker.setFocusableInTouchMode(false);
            addPicker.setClickable(true);
            addPicker.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(PcView.this, AddComputerManually.class);
                    startActivity(i);
                }
            });
        }

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
                    pageHostList(-1);
                }
            });
        }
        View pgDn = findViewById(R.id.am_pg_dn);
        if (pgDn != null) {
            pgDn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    pageHostList(1);
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
        OrientationHelper.lockPortraitOnPhones(this);

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

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

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

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

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
        updatePickerHostPill();

        // Desktop parity (HomeScreen.qml hostProbes): silently re-check the
        // ArtLight auth state of hosts we've paired with before, so the
        // Authorised chip repaints itself on app open. Never touches hosts
        // we haven't paired — no unprompted allow-requests.
        maybeAutoReprobeArtLight(details);
    }

    /**
     * v10: populate the picker row's center pill with the selected host's
     * name (first host in the sorted grid — there is one host today).
     */
    private void updatePickerHostPill() {
        android.widget.TextView pill = findViewById(R.id.am_picker_host_pill);
        if (pill == null) {
            return;
        }
        if (pcGridAdapter.getCount() > 0) {
            ComputerObject first = (ComputerObject) pcGridAdapter.getItem(0);
            pill.setText(first.details.name);
            pill.setVisibility(View.VISIBLE);
        }
        else {
            pill.setVisibility(View.INVISIBLE);
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
        if (pcGridAdapter.getCount() == 0) {
            return null;
        }
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject c = (ComputerObject) pcGridAdapter.getItem(i);
            if (c != null && c.details.state == ComputerDetails.State.ONLINE) {
                return c;
            }
        }
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
            for (int i = 0; i < pcGridAdapter.getCount(); i++) {
                ComputerObject co = (ComputerObject) pcGridAdapter.getItem(i);
                if (co != null && uuid.equals(co.details.uuid)) {
                    return co.details.name;
                }
            }
        }
        return getString(R.string.am_hero_bg_set);
    }

    /** Page the host GridView by one screenful; negative = up. */
    private void pageHostList(int dir) {
        if (pcGridView == null) return;
        int page = Math.max(1, pcGridView.getHeight() - pcGridView.getPaddingTop()
                - pcGridView.getPaddingBottom());
        pcGridView.smoothScrollBy(dir * page, 300);
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
                    // T-model: Up jumps to + Add a Host from Open/Options.
                    movePadTo(2);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Down only exits Add (back to Open); walls elsewhere.
                    if (padActionIndex == 2) movePadTo(0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    movePadAction(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    movePadAction(-1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_L1:
                    pageHostList(-1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    pageHostList(1);
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
        View add = findViewById(R.id.manuallyAddPcText);
        java.util.List<View> targets = new java.util.ArrayList<>();
        if (open != null && open.getVisibility() == View.VISIBLE) targets.add(open);
        if (options != null && options.getVisibility() == View.VISIBLE) targets.add(options);
        if (add != null && add.getVisibility() == View.VISIBLE) targets.add(add);
        return targets.toArray(new View[0]);
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

    /** Right/Left across Open / Options only (0..1), clamped, no wrap. */
    private void movePadAction(int delta) {
        int next = padActionIndex + delta;
        if (next > 1) next = 1;
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
            }
        });

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
