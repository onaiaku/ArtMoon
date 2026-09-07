package io.github.onaiaku.artmoon.artlight;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import io.github.onaiaku.artmoon.LimeLog;
import io.github.onaiaku.artmoon.R;
import io.github.onaiaku.artmoon.utils.UiHelper;
import io.github.onaiaku.artmoon.nvstream.http.ComputerDetails;

import org.json.JSONObject;

/**
 * ArtLight settings tab (phone port of the desktop's ArtLight tab):
 * live host status via ArtLightBridge, ArtMoon self-version card, links out.
 */
public class ArtLightActivity extends Activity {

    private ArtLightBridge bridge;
    private TextView serverRow, versionRow, syncRow, authRow, selfRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        io.github.onaiaku.artmoon.OrientationHelper.lockPortraitOnPhones(this);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_artlight);

        bridge = new ArtLightBridge(this);

        serverRow = findViewById(R.id.am_server_row);
        versionRow = findViewById(R.id.am_server_version_row);
        syncRow = findViewById(R.id.am_sync_row);
        authRow = findViewById(R.id.am_auth_row);
        selfRow = findViewById(R.id.am_self_version_row);

        findViewById(R.id.am_changelogs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://github.com/onaiaku/ArtMoon/blob/main/changelog.txt");
            }
        });
        findViewById(R.id.am_releases).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://github.com/onaiaku/ArtMoon/releases");
            }
        });

        // ArtMoon self version
        String selfVersion;
        try {
            selfVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            selfVersion = "?";
        }
        selfRow.setText(getResources().getString(R.string.am_artlight_self_version, selfVersion));

        refreshHostStatus();
    }

    private void refreshHostStatus() {
        // The host list lives in ComputerManagerService; from settings we don't
        // have a bound manager, so we accept an address extra when launched from
        // a host card and otherwise show the passive card.
        final String address = getIntent().getStringExtra("host_address");
        final String hostName = getIntent().getStringExtra("host_name");

        if (address == null || address.isEmpty()) {
            authRow.setText(R.string.am_artlight_no_host);
            syncRow.setText(R.string.am_artlight_no_host);
            versionRow.setText(R.string.am_artlight_no_host);
            serverRow.setText(R.string.am_artlight_add_hint);
            return;
        }

        serverRow.setText(getResources().getString(R.string.am_artlight_server, hostName, address));

        bridge.requestStatus(address, new ArtLightBridge.ResponseCallback() {
            @Override
            public void onResult(String response) {
                setRow(versionRow, response.isEmpty()
                        ? getResources().getString(R.string.am_artlight_no_host)
                        : getResources().getString(R.string.am_artlight_reachable));
            }
        });

        bridge.requestAppStores(address, new ArtLightBridge.ResponseCallback() {
            @Override
            public void onResult(String response) {
                int count = 0;
                try {
                    JSONObject o = new JSONObject(response);
                    count = o.length();
                } catch (Exception ignored) {
                }
                final String text = count > 0
                        ? getResources().getString(R.string.am_artlight_sync, count)
                        : getResources().getString(R.string.am_artlight_no_host);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        syncRow.setText(text);
                    }
                });
            }
        });

        bridge.requestUpdateState(address, new ArtLightBridge.ResponseCallback() {
            @Override
            public void onResult(String response) {
                String text;
                try {
                    JSONObject o = new JSONObject(response);
                    boolean pending = o.optBoolean("pending", false);
                    text = getResources().getString(pending
                            ? R.string.am_artlight_updates_pending
                            : R.string.am_artlight_updates_ok);
                } catch (Exception e) {
                    text = getResources().getString(R.string.am_artlight_no_host);
                }
                final String t = text;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        authRow.setText(t);
                    }
                });
            }
        });
    }

    private void setRow(final TextView row, final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                row.setText(text);
            }
        });
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            LimeLog.info("ArtLightActivity: no browser for " + url);
        }
    }
}
