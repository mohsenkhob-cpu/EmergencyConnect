package ir.emergencyconnect.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persist a verified OpenVPN profile encrypted in Android's private storage. */
public final class RecentRelayStore {
    private static final String PREFS = "verified_openvpn_relay";
    private static final String KEY_ALIAS = "filter_mohsen_verified_openvpn_aes_v1";
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private RecentRelayStore() { }

    public static final class Entry {
        public final String profile;
        public final String endpoint;
        public final String protocol;
        private Entry(String p, String e, String pr) { profile=p; endpoint=e; protocol=pr; }
    }

    public static int physicalTransport(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return -1;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return -1;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 1;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return 2;
        return -1;
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS))
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    public static synchronized void save(Context context, String profile, String endpoint,
                                         String protocol, int transport) {
        if (transport < 0 || profile == null || profile.isEmpty() || profile.length() > 262144
                || endpoint == null || endpoint.isEmpty()) return;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV(), ciphertext = cipher.doFinal(profile.getBytes(StandardCharsets.UTF_8));
            if (iv.length != 12) return;
            byte[] data = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, data, 0, iv.length);
            System.arraycopy(ciphertext, 0, data, iv.length, ciphertext.length);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("ciphertext", Base64.encodeToString(data, Base64.NO_WRAP))
                    .putString("endpoint", endpoint).putString("protocol", protocol)
                    .putInt("transport", transport).putLong("saved_at", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { /* Never persist plaintext on Keystore failure. */ }
    }

    public static synchronized Entry load(Context context, int transport) {
        if (transport < 0) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt("transport", -1) != transport) return null;
        long age = System.currentTimeMillis() - prefs.getLong("saved_at", 0);
        if (age < 0 || age >= MAX_AGE_MS) return null;
        String encoded = prefs.getString("ciphertext", "");
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] payload = Base64.decode(encoded, Base64.DEFAULT);
            if (payload.length <= 28) return null;
            byte[] iv = new byte[12], body = new byte[payload.length - 12];
            System.arraycopy(payload, 0, iv, 0, 12);
            System.arraycopy(payload, 12, body, 0, body.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            String profile = new String(cipher.doFinal(body), StandardCharsets.UTF_8);
            if (!profile.contains("\nremote ") && !profile.startsWith("remote ")) return null;
            return new Entry(profile, prefs.getString("endpoint", ""), prefs.getString("protocol", ""));
        } catch (Exception ignored) { return null; }
    }
}
