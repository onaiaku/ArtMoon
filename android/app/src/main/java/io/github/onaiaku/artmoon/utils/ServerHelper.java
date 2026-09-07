package io.github.onaiaku.artmoon.utils;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import io.github.onaiaku.artmoon.AppView;
import io.github.onaiaku.artmoon.Game;
import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.ShortcutTrampoline;
import io.github.onaiaku.artmoon.binding.PlatformBinding;
import io.github.onaiaku.artmoon.computers.ComputerManagerService;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;
import io.github.onaiaku.artmoon.nvstream.http.HostHttpResponseException;
import io.github.onaiaku.artmoon.nvstream.http.NvApp;
import io.github.onaiaku.artmoon.nvstream.http.NvHTTP;
import io.github.onaiaku.artmoon.nvstream.jni.MoonBridge;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;

public class ServerHelper {
    public static final String CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org";

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createPcShortcutIntent(Activity parent, ComputerDetails computer) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createAppShortcutIntent(Activity parent, ComputerDetails computer, NvApp app) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(Game.EXTRA_APP_ID, ""+app.getAppId());
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder) {
        Intent intent = new Intent(parent, Game.class);
        intent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address);
        intent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port);
        intent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        intent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        intent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        intent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        intent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        intent.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        intent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        try {
            if (computer.serverCert != null) {
                intent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }
        return intent;
    }

    public static void doStart(Activity parent, NvApp app, ComputerDetails computer,
                               ComputerManagerService.ComputerManagerBinder managerBinder) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        parent.startActivity(createStartIntent(parent, app, computer, managerBinder));
    }

    /**
     * ArtLight launch curtain — after firing the stream intent, poll the host's
     * GAMESTATE via ArtLightBridge and surface real launch phases instead of a
     * bare spinner. Fire-and-forget: the stream's own UI takes over on connect;
     * on unreachable/older hosts this stays silent (never blocks the stream).
     */
    public static void doStartWithCurtain(final Activity parent, NvApp app, ComputerDetails computer,
                                          ComputerManagerService.ComputerManagerBinder managerBinder) {
        doStart(parent, app, computer, managerBinder);
        if (computer.activeAddress == null) {
            return;
        }
        final String address = computer.activeAddress.address;
        final io.github.onaiaku.artmoon.artlight.ArtLightBridge bridge =
                new io.github.onaiaku.artmoon.artlight.ArtLightBridge(parent);
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

        // Up to ~20s of phase reports, 1s apart, then stop quietly.
        final int[] pollsLeft = {20};
        final Runnable[] pollRef = new Runnable[1];
        final Runnable poll = new Runnable() {
            @Override
            public void run() {
                pollsLeft[0]--;
                if (pollsLeft[0] < 0) {
                    return;
                }
                bridge.requestGameState(address, new io.github.onaiaku.artmoon.artlight.ArtLightBridge.ResponseCallback() {
                    @Override
                    public void onResult(final String response) {
                        final String phase = formatGameState(response);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (phase != null && !phase.isEmpty()) {
                                    android.widget.Toast.makeText(parent, phase,
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                                handler.postDelayed(pollRef[0], 1000);
                            }
                        });
                    }
                });
            }
        };
        pollRef[0] = poll;
        handler.postDelayed(poll, 2500);
    }

    /**
     * Map GAMESTATE JSON to a human phase, or null when nothing to report.
     * "no curtain" responses return null — never "keep waiting".
     */
    private static String formatGameState(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String state = o.optString("state", "");
            String fg = o.optString("foreground", "");
            switch (state) {
                case "waiting_window":
                    return parent_phase(fg, "waiting for its window…");
                case "blocked":
                    return parent_phase(fg, "is on screen — close it to continue");
                case "ready":
                    return null; // stream takes over now
                default:
                    return null; // unknown/older host: silence
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String parent_phase(String foreground, String suffix) {
        if (foreground == null || foreground.isEmpty()) {
            return "The game is " + suffix;
        }
        return foreground + " is " + suffix;
    }

    public static void doNetworkTest(final Activity parent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SpinnerDialog spinnerDialog = SpinnerDialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_waiting),
                        parent.getResources().getString(R.string.nettest_text_waiting),
                        false);

                int ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL);
                spinnerDialog.dismiss();

                String dialogSummary;
                if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_inconclusive);
                }
                else if (ret == 0) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_success);
                }
                else {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_failure);
                    dialogSummary += MoonBridge.stringifyPortFlags(ret, "\n");
                }

                Dialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_done),
                        dialogSummary,
                        false);
            }
        }).start();
    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete) {
        Toast.makeText(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + app.getAppName() + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                            managerBinder.getUniqueId(), computer.serverCert, PlatformBinding.getCryptoProvider(parent));
                    if (httpConn.quitApp()) {
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + app.getAppName();
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + app.getAppName();
                    }
                } catch (HostHttpResponseException e) {
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = parent.getResources().getString(R.string.error_404);
                } catch (IOException | XmlPullParserException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
