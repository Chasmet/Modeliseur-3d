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
    public static int httpPort(Context c) {return prefs(c).getInt("http_port",HTTP_PORT);}
    public static int httpsPort(Context c) {return prefs(c).getInt("https_port",HTTPS_PORT);}
    public static synchronized String token(Context c) {
        try {
            String encrypted=prefs(c).getString("token_encrypted","");
            if(!encrypted.isEmpty())return new String(PhoneSecretStorage.decrypt(c,android.util.Base64.decode(encrypted,android.util.Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
            String token=prefs(c).getString("token","");
            if(token.isEmpty()) {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder hex=new StringBuilder();for(byte value:bytes)hex.append(String.format(Locale.ROOT,"%02x",value&255));token=hex.toString();}
            byte[] protectedBytes=PhoneSecretStorage.encrypt(c,token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(!prefs(c).edit().putString("token_encrypted",android.util.Base64.encodeToString(protectedBytes,android.util.Base64.NO_WRAP)).remove("token").commit())throw new IOException("Secret non enregistré.");
            return token;
        }catch(Exception failure) {throw new IllegalStateException("Secret MCP indisponible dans le stockage sécurisé Android.",failure);}
    }
    public static String publicBase(Context c) { return prefs(c).getString("public_base",""); }
    public static String validatePublicBase(String address) throws IOException {
        if (address.trim().isEmpty()) return "";
        try {
            URI u=new URI(address.trim());
            if (!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null
                    || !(u.getPath()==null || u.getPath().isEmpty() || "/".equals(u.getPath()))) throw new IOException("Adresse HTTPS publique sans chemin requise.");
            String host=u.getHost().replace("[","").replace("]","");
            if(host.contains(":") || host.matches("[0-9.]+")) {
                if(!PhoneNetworkDiagnostics.global(InetAddress.getByName(host)))throw new IOException("L’adresse publique ne peut pas être une IP locale ou réservée.");
            }
            if(u.getPort()!=-1 && (u.getPort()<1 || u.getPort()>65535))throw new IOException("Port HTTPS invalide.");
            return address.trim().replaceAll("/+$","");
        } catch (URISyntaxException e) { throw new IOException("Adresse HTTPS invalide."); }
    }
    public static File certificate(Context c) { return new File(c.getFilesDir(),"phone_mcp/tls.p12.enc"); }
    private static File legacyCertificate(Context c) { return new File(c.getFilesDir(),"phone_mcp/tls.p12"); }
    public static boolean hasCertificate(Context c) {return certificate(c).isFile()||legacyCertificate(c).isFile();}
    public static synchronized void importCertificate(Context c,InputStream input,String password) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1) {if(bytes.size()+count>1024*1024)throw new IOException("Certificat limité à 1 Mo.");bytes.write(buffer,0,count);}
        load(bytes.toByteArray(),password);
        File file=certificate(c);if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("Stockage certificat indisponible.");
        byte[] protectedPassword=PhoneSecretStorage.encrypt(c,password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Bundle the password with the PKCS12 so replacement remains atomic even after process death.
        ByteArrayOutputStream payload=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(payload)) {out.writeInt(protectedPassword.length);out.write(protectedPassword);out.write(bytes.toByteArray());}
        File part=new File(file.getPath()+".part");
        try {
            try(FileOutputStream output=new FileOutputStream(part)) {output.write(PhoneSecretStorage.encrypt(c,payload.toByteArray()));output.getFD().sync();}
            if(!part.renameTo(file))throw new IOException("Certificat non enregistré.");
            prefs(c).edit().remove("tls_password").remove("external_client").commit();legacyCertificate(c).delete();
        }finally {part.delete();}
    }
    private static KeyStore load(byte[] data,String password) throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(InputStream input=new ByteArrayInputStream(data)) {keys.load(input,password.toCharArray());}
        boolean key=false;Enumeration<String> aliases=keys.aliases();
        while(aliases.hasMoreElements()) {String alias=aliases.nextElement();if(keys.isKeyEntry(alias)) {key=true;if(!(keys.getKey(alias,password.toCharArray()) instanceof PrivateKey))throw new IOException("Clé privée du certificat illisible.");((X509Certificate)keys.getCertificate(alias)).checkValidity();}}
        if(!key)throw new IOException("Le fichier PKCS12 doit contenir la clé privée et le certificat valides.");
        return keys;
    }
    public static synchronized SSLContext tls(Context c) throws Exception {
        File file=certificate(c);
        if(!file.isFile()&&legacyCertificate(c).isFile()) {
            try(InputStream input=new FileInputStream(legacyCertificate(c))) {importCertificate(c,input,prefs(c).getString("tls_password",""));}
        }
        if(!file.isFile())return null;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)) {byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1) {if(bytes.size()+count>2*1024*1024)throw new IOException("Certificat chiffré invalide.");bytes.write(buffer,0,count);}}
        String password;byte[] p12;
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(PhoneSecretStorage.decrypt(c,bytes.toByteArray())))) {
            int length=input.readInt();if(length<29||length>65536)throw new IOException("Mot de passe chiffré invalide.");
            byte[] protectedPassword=new byte[length];input.readFully(protectedPassword);
            password=new String(PhoneSecretStorage.decrypt(c,protectedPassword),java.nio.charset.StandardCharsets.UTF_8);
            ByteArrayOutputStream remainder=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1)remainder.write(buffer,0,count);p12=remainder.toByteArray();
        }
        KeyStore keys=load(p12,password);
        KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password.toCharArray());
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(managers.getKeyManagers(),null,new SecureRandom());return tls;
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
    public static boolean isLocalHost(String host) {
        host=host.replace("[","").replace("]","");
        if("127.0.0.1".equals(host)||"::1".equals(host))return true;
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()) {
                NetworkInterface nic=interfaces.nextElement();if(!nic.isUp())continue;
                Enumeration<InetAddress> ips=nic.getInetAddresses();
                while(ips.hasMoreElements()) {
                    InetAddress ip=ips.nextElement();
                    String address=ip.getHostAddress().split("%",2)[0];
                    if(address.equalsIgnoreCase(host))return true;
                }
            }
        }catch(Exception ignored) { }
        return false;
    }
    public static String localUrl(Context c) { return "http://127.0.0.1:"+httpPort(c)+"/mcp/"+token(c); }
    public static String publicUrl(Context c) { String base=publicBase(c);return base.isEmpty()?"":base+"/mcp/"+token(c); }
    public static String connectionUrl(Context c) {String base=publicBase(c);return base.isEmpty()?"non disponible":base+"/apps/modeliseur3d/mcp";}
}
