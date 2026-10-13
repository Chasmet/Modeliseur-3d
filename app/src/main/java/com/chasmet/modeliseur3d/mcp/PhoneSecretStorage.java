package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.util.Calendar;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/** AES-GCM data protected by Android Keystore, including an RSA-wrapped AES key on API 21/22. */
public final class PhoneSecretStorage {
    private static final String ALIAS="modeliseur.phone.mcp.v1";
    private PhoneSecretStorage() { }

    @SuppressWarnings("deprecation")
    private static synchronized SecretKey key(Context context) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String wrapped=PhoneMcpSettings.prefs(context).getString("wrapped_aes_key","");
        if(Build.VERSION.SDK_INT>=23 && wrapped.isEmpty()) {
            if(!store.containsAlias(ALIAS+".aes")) {
                KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(ALIAS+".aes",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build());generator.generateKey();
            }
            return (SecretKey)store.getKey(ALIAS+".aes",null);
        }
        if(!store.containsAlias(ALIAS)) {
            Calendar start=Calendar.getInstance(),end=Calendar.getInstance();end.add(Calendar.YEAR,30);
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS).setKeySize(2048)
                    .setSubject(new X500Principal("CN=Modeliseur MCP")).setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.getTime()).setEndDate(end.getTime()).build());generator.generateKeyPair();
        }
        Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");
        if(wrapped.isEmpty()) {
            byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);
            rsa.init(Cipher.ENCRYPT_MODE,store.getCertificate(ALIAS).getPublicKey());
            if(!PhoneMcpSettings.prefs(context).edit().putString("wrapped_aes_key",Base64.encodeToString(rsa.doFinal(raw),Base64.NO_WRAP)).commit())throw new IOException("Stockage secret indisponible.");
            return new SecretKeySpec(raw,"AES");
        }
        rsa.init(Cipher.DECRYPT_MODE,store.getKey(ALIAS,null));
        return new SecretKeySpec(rsa.doFinal(Base64.decode(wrapped,Base64.NO_WRAP)),"AES");
    }
    public static byte[] encrypt(Context context,byte[] data) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(context));
        byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(data),result=new byte[1+iv.length+encrypted.length];
        result[0]=(byte)iv.length;System.arraycopy(iv,0,result,1,iv.length);System.arraycopy(encrypted,0,result,1+iv.length,encrypted.length);return result;
    }
    public static byte[] decrypt(Context context,byte[] data) throws Exception {
        if(data.length<29 || (data[0]&255)!=12)throw new IOException("Secret chiffré invalide.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(context),new GCMParameterSpec(128,data,1,12));return cipher.doFinal(data,13,data.length-13);
    }
}
