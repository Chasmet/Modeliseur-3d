package com.chasmet.modeliseur3d.mcp;

import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.*;
import android.security.keystore.KeyGenParameterSpec;

/** JVM-only AndroidKeyStore adapter. Real AES-GCM is tested; hardware protection requires a device. */
public final class PhoneSecretTestProvider extends Provider {
    private static final Map<String,Key> KEYS=new ConcurrentHashMap<>();
    public PhoneSecretTestProvider() {
        super("AndroidKeyStore",1.0,"Desktop test adapter, never packaged into APK");
        put("KeyStore.AndroidKeyStore",Store.class.getName());put("KeyGenerator.AES",Aes.class.getName());
    }
    public static void install() {
        if(Security.getProvider("AndroidKeyStore")==null)Security.addProvider(new PhoneSecretTestProvider());
    }
    public static final class Aes extends KeyGeneratorSpi {
        private String alias;
        private int bits=256;
        @Override protected void engineInit(SecureRandom random) { }
        @Override protected void engineInit(int bits,SecureRandom random) {this.bits=bits;}
        @Override protected void engineInit(AlgorithmParameterSpec spec,SecureRandom random) throws InvalidAlgorithmParameterException {
            if(!(spec instanceof KeyGenParameterSpec))throw new InvalidAlgorithmParameterException();
            alias=((KeyGenParameterSpec)spec).getKeystoreAlias();bits=((KeyGenParameterSpec)spec).getKeySize();
        }
        @Override protected SecretKey engineGenerateKey() {
            try {KeyGenerator generator=KeyGenerator.getInstance("AES","SunJCE");generator.init(bits);SecretKey key=generator.generateKey();KEYS.put(alias,key);return key;}
            catch(Exception error) {throw new IllegalStateException(error);}
        }
    }
    public static final class Store extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias,char[] password) {return KEYS.get(alias);}
        @Override public Certificate[] engineGetCertificateChain(String alias) {return null;}
        @Override public Certificate engineGetCertificate(String alias) {return null;}
        @Override public Date engineGetCreationDate(String alias) {return new Date();}
        @Override public void engineSetKeyEntry(String alias,Key key,char[] password,Certificate[] certificates) {KEYS.put(alias,key);}
        @Override public void engineSetKeyEntry(String alias,byte[] key,Certificate[] certificates) {throw new UnsupportedOperationException();}
        @Override public void engineSetCertificateEntry(String alias,Certificate certificate) {throw new UnsupportedOperationException();}
        @Override public void engineDeleteEntry(String alias) {KEYS.remove(alias);}
        @Override public Enumeration<String> engineAliases() {return Collections.enumeration(KEYS.keySet());}
        @Override public boolean engineContainsAlias(String alias) {return KEYS.containsKey(alias);}
        @Override public int engineSize() {return KEYS.size();}
        @Override public boolean engineIsKeyEntry(String alias) {return KEYS.containsKey(alias);}
        @Override public boolean engineIsCertificateEntry(String alias) {return false;}
        @Override public String engineGetCertificateAlias(Certificate certificate) {return null;}
        @Override public void engineStore(OutputStream output,char[] password) { }
        @Override public void engineLoad(InputStream input,char[] password) { }
    }
}
