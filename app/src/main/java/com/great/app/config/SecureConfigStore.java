package com.great.app.config;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the raw .conf encrypted at rest. The key never leaves Android Keystore. */
public final class SecureConfigStore {
    private static final String KEY_ALIAS = "great.awg.config.v1";
    private static final int VERSION = 1;
    private final Context context;

    public SecureConfigStore(Context context) { this.context = context.getApplicationContext(); }

    public boolean exists() { return file().getBaseFile().exists(); }

    public synchronized void save(byte[] plaintext) throws Exception {
        new AwgConfigParser().parse(plaintext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plaintext);
        AtomicFile file = file();
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(VERSION);
            output.write(iv.length);
            output.write(iv);
            output.write(encrypted);
            file.finishWrite(output);
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw e;
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    public synchronized byte[] load() throws Exception {
        byte[] data;
        try (FileInputStream input = file().openRead()) {
            data = readBounded(input, AwgConfigParser.MAX_CONFIG_BYTES + 128);
        }
        try {
            if (data.length < 2 || (data[0] & 0xff) != VERSION) throw new IOException("Unsupported config format");
            int ivLength = data[1] & 0xff;
            int payload = 2 + ivLength;
            if (ivLength < 12 || ivLength > 32 || payload >= data.length) throw new IOException("Corrupt config");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(data, 2, payload)));
            byte[] plaintext = cipher.doFinal(data, payload, data.length - payload);
            new AwgConfigParser().parse(plaintext);
            return plaintext;
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private AtomicFile file() { return new AtomicFile(new File(context.getNoBackupFilesDir(), "awg.conf.aesgcm")); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }

    private static byte[] readBounded(FileInputStream input, int max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (out.size() + read > max) throw new IOException("Config file is too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
