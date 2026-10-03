package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

/** Direct MCP configuration stays in Android private storage, separate from APK updates. */
public final class PhoneMcpSettings {
    public static final int HTTP_PORT=8787, HTTPS_PORT=8443;
    private PhoneMcpSettings() { }
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("phone_mcp",Context.MODE_PRIVATE); }
    public static boolean direct(Context c) { return prefs(c).getBoolean("direct",false); }
    public static synchronized String token(Context c) {
        String token=prefs(c).getString("token","");
        if (token.isEmpty()) { token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");prefs(c).edit().putString("token",token).commit(); }
        return token;
    }
    public static String publicBase(Context c) { return prefs(c).getString("public_base",""); }
    public static String validatePublicBase(String address) throws IOException {
        if (address.trim().isEmpty()) return "";
        try {
            URI u=new URI(address.trim());
            if (!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null
                    || !(u.getPath()==null || u.getPath().isEmpty() || "/".equals(u.getPath()))) throw new IOException("Adresse HTTPS publique sans chemin requise.");
            return address.trim().replaceAll("/+$","");
        } catch (URISyntaxException e) { throw new IOException("Adresse HTTPS invalide."); }
    }
    public static File certificate(Context c) { return new File(c.getFilesDir(),"phone_mcp/tls.p12"); }
    public static void importCertificate(Context c,InputStream input,String password) throws Exception {
        File file=certificate(c);if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("Stockage certificat indisponible.");
        File part=new File(file.getPath()+".part");
        try {
            try (OutputStream out=new FileOutputStream(part)) {
                byte[] buffer=new byte[8192];int n;long total=0;
                while ((n=input.read(buffer))!=-1) { total+=n;if(total>1024*1024)throw new IOException("Certificat limité à 1 Mo.");out.write(buffer,0,n); }
            }
            load(part,password);
            if (!part.renameTo(file)) throw new IOException("Certificat non enregistré.");
            prefs(c).edit().putString("tls_password",password).commit();
        } finally { part.delete(); }
    }
    private static KeyStore load(File file,String password) throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(InputStream in=new FileInputStream(file)) { keys.load(in,password.toCharArray()); }
        boolean key=false;Enumeration<String> aliases=keys.aliases();
        while(aliases.hasMoreElements()) { String alias=aliases.nextElement();if(keys.isKeyEntry(alias)) { key=true;((X509Certificate)keys.getCertificate(alias)).checkValidity(); } }
        if(!key)throw new IOException("Le fichier PKCS12 doit contenir la clé privée et le certificat valides.");
        return keys;
    }
    public static SSLContext tls(Context c) throws Exception {
        File file=certificate(c);if(!file.isFile())return null;
        String password=prefs(c).getString("tls_password","");KeyStore keys=load(file,password);
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,new SecureRandom());return tls;
    }
    public static String lanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            String candidate="127.0.0.1";
            while(interfaces.hasMoreElements()) {
                NetworkInterface nic=interfaces.nextElement();if(!nic.isUp()||nic.isLoopback())continue;
                Enumeration<InetAddress> addresses=nic.getInetAddresses();
                while(addresses.hasMoreElements()) { InetAddress ip=addresses.nextElement();if(ip instanceof Inet4Address && ip.isSiteLocalAddress()) { candidate=ip.getHostAddress();if(nic.getName().startsWith("wlan"))return candidate; } }
            }
            return candidate;
        } catch(Exception e) { return "127.0.0.1"; }
    }
    public static String localUrl(Context c) { return "http://"+lanAddress()+":"+HTTP_PORT+"/mcp/"+token(c); }
    public static String publicUrl(Context c) { String base=publicBase(c);return base.isEmpty()?"":base+"/mcp/"+token(c); }
}
