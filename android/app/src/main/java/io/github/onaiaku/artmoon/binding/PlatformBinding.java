package io.github.onaiaku.artmoon.binding;

import android.content.Context;

import io.github.onaiaku.artmoon.binding.audio.AndroidAudioRenderer;
import io.github.onaiaku.artmoon.binding.crypto.AndroidCryptoProvider;
import io.github.onaiaku.artmoon.nvstream.av.audio.AudioRenderer;
import io.github.onaiaku.artmoon.nvstream.http.LimelightCryptoProvider;

public class PlatformBinding {
    public static LimelightCryptoProvider getCryptoProvider(Context c) {
        return new AndroidCryptoProvider(c);
    }
}
