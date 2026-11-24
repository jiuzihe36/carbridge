package com.carbridge.carplay;

import android.content.Context;
import android.content.SharedPreferences;

import com.carbridge.carplay.pairing.Ed25519;
import com.carbridge.carplay.pairing.IdentityStore;
import com.carbridge.carplay.util.Diag;

/**
 * 用 SharedPreferences 持久化配对身份。
 */
public final class AndroidIdentityStore implements IdentityStore {

    private static final String PREFS = "carbridge_pairing";
    private static final String KEY_SEED = "ed25519_seed";

    private final Context ctx;

    public AndroidIdentityStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public Ed25519.KeyPair load() {
        try {
            String hex = prefs().getString(KEY_SEED, null);
            if (hex == null || hex.length() != 64) return null;
            byte[] seed = fromHex(hex);
            return Ed25519.keyPairFromSeed(seed);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void save(Ed25519.KeyPair identity) {
        try {
            prefs().edit().putString(KEY_SEED, toHex(identity.seed)).apply();
        } catch (Throwable tDiag) {
            Diag.w("AndroidIdentityStore.save", tDiag);
        }
    }

    @Override
    public void clear() {
        try {
            prefs().edit().remove(KEY_SEED).apply();
        } catch (Throwable tDiag) {
            Diag.w("AndroidIdentityStore.clear", tDiag);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        return sb.toString();
    }

    private static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
