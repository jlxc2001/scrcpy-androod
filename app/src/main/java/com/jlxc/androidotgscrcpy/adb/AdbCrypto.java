package com.jlxc.androidotgscrcpy.adb;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public final class AdbCrypto {
    private final KeyPair keyPair;

    private AdbCrypto(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static AdbCrypto loadOrCreate(Context context) throws Exception {
        File privateFile = new File(context.getFilesDir(), "adbkey.pk8");
        File publicFile = new File(context.getFilesDir(), "adbkey.x509");
        if (privateFile.exists() && publicFile.exists()) {
            byte[] priv = readAll(privateFile);
            byte[] pub = readAll(publicFile);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(priv));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(pub));
            return new AdbCrypto(new KeyPair(publicKey, privateKey));
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair kp = generator.generateKeyPair();
        writeAll(privateFile, kp.getPrivate().getEncoded());
        writeAll(publicFile, kp.getPublic().getEncoded());
        return new AdbCrypto(kp);
    }

    public byte[] signAdbToken(byte[] token) throws Exception {
        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(token);
        return sig.sign();
    }

    public byte[] getAdbPublicKeyPayload() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        byte[] adbKey = encodeAdbPublicKey(publicKey);
        String b64 = Base64.encodeToString(adbKey, Base64.NO_WRAP);
        String text = b64 + " jlxc-android-otg-scrcpy\0";
        return text.getBytes("US-ASCII");
    }

    private static byte[] encodeAdbPublicKey(RSAPublicKey key) throws Exception {
        final int words = 64; // 2048-bit RSA key / 32 bits
        final int bytes = words * 4;
        BigInteger n = key.getModulus();
        BigInteger e = key.getPublicExponent();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger r = BigInteger.ONE.shiftLeft(words * 32);
        BigInteger rr = r.multiply(r).mod(n);
        BigInteger n0 = n.mod(two32);
        BigInteger n0inv = two32.subtract(n0.modInverse(two32)).mod(two32);

        ByteArrayOutputStream out = new ByteArrayOutputStream(524);
        writeLittleEndianInt(out, words);
        writeLittleEndianInt(out, n0inv.longValue());
        out.write(toFixedLittleEndian(n, bytes));
        out.write(toFixedLittleEndian(rr, bytes));
        writeLittleEndianInt(out, e.longValue());
        return out.toByteArray();
    }

    private static byte[] toFixedLittleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[size];
        int start = (big.length > 0 && big[0] == 0) ? 1 : 0;
        int len = big.length - start;
        for (int i = 0; i < len && i < size; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xff));
        out.write((int) ((value >> 8) & 0xff));
        out.write((int) ((value >> 16) & 0xff));
        out.write((int) ((value >> 24) & 0xff));
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File file, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
