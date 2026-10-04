package com.chasmet.modeliseur3d.mcp;

import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Runs on its own executor: network discovery/ACME cannot block the 3D worker or MCP polling. */
final class PhonePublicConnection implements AutoCloseable {
    private final Context context;
    private final PhoneMcpServer server;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService leaseWorker=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile PhonePortMapper mapper;
    private final java.util.concurrent.atomic.AtomicReference<PhonePortMapper.Lease> mainLease=new java.util.concurrent.atomic.AtomicReference<>(),temporaryLease=new java.util.concurrent.atomic.AtomicReference<>();
    private long leaseRetryAt;
    private String link="";
    private long retryAt;
    private final java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
    PhonePublicConnection(Context c,PhoneMcpServer server) {
        context=c.getApplicationContext();this.server=server;
        worker.scheduleWithFixedDelay(this::cycle,0,5,TimeUnit.MINUTES);
        leaseWorker.scheduleWithFixedDelay(this::renewLeases,5,5,TimeUnit.SECONDS);
    }
    private void renewLeases() {
        if(closed||System.currentTimeMillis()<leaseRetryAt)return;
        PhonePortMapper current=mapper;if(current==null)return;
        try {PhonePortMapper.Lease lease=mainLease.get();if(lease!=null&&lease.created&&System.currentTimeMillis()>=lease.renewAt)
            mainLease.compareAndSet(lease,current.map(lease.internal,lease.external,1800));}catch(Exception ignored) {leaseRetryAt=System.currentTimeMillis()+60000;}
        try {PhonePortMapper.Lease lease=temporaryLease.get();if(lease!=null&&lease.created&&System.currentTimeMillis()>=lease.renewAt)
            temporaryLease.compareAndSet(lease,current.map(lease.internal,lease.external,600));}catch(Exception ignored) {leaseRetryAt=System.currentTimeMillis()+60000;}
    }
    void request() {if(!closed&&queued.compareAndSet(false,true))try {worker.execute(()->{try {cycle();}finally {queued.set(false);}});}catch(RejectedExecutionException ignored) {queued.set(false);}}
    private SharedPreferences prefs() {return PhoneMcpSettings.prefs(context);}
    private void state(String message) {if(!closed)prefs().edit().putString("automatic_status",message).apply();}
    private void cycle() {
        if(closed||!prefs().getBoolean("automatic_https",false))return;
        try {
            if(prefs().getLong("automatic_certificate_expires",0)<=System.currentTimeMillis())server.pauseTls();
            state("Détection de l’IPv4 publique et de la passerelle…");
            PhonePortMapper current=PhonePortMapper.active(context);
            String network=current.local.getHostAddress()+"/"+current.gateway.getHostAddress();
            if(!network.equals(link)) {mapper=current;link=network;mainLease.set(null);
                prefs().edit().remove("external_client").remove("public_mcp_client").remove("chatgpt_client").putString("automatic_local_ip",current.local.getHostAddress()).putString("automatic_gateway",current.gateway.getHostAddress()).apply();}
            mapper.retryDiscovery();
            String wan="";try {wan=mapper.wan();}catch(Exception ignored) { }
            String ip;
            if(validPublicV4(wan))ip=wan;
            else {
                String first=detect("https://api4.ipify.org"),second=detect("https://checkip.amazonaws.com");
                if(!first.equals(second))throw new IOException("Les services de détection IPv4 ne concordent pas. Nouvel essai automatique.");ip=first;
            }
            String previous=prefs().getString("automatic_public_ip","");
            if(!ip.equals(previous)) {
                server.pauseTls();prefs().edit().putString("automatic_public_ip",ip).putString("public_base","").remove("external_client").remove("public_mcp_client").remove("chatgpt_client").apply();
            }
            String storedIp=prefs().getString("automatic_certificate_ip","");
            long expires=prefs().getLong("automatic_certificate_expires",0),now=System.currentTimeMillis();
            if(!ip.equals(storedIp)||expires<now)server.pauseTls();
            String mapping="Règle existante à vérifier depuis Internet";
            // Establish the actual TLS listener first, then map its actual port (including conflict fallback).
            if(ip.equals(storedIp)&&expires>now&&!server.httpsRunning())server.reloadTls();
            int internal=PhoneMcpSettings.httpsPort(context),external=8443;
            try {PhonePortMapper.Lease lease=mapper.map(internal,external,1800);mainLease.set(lease);mapping=lease.method+" accepté · test extérieur requis";}
            catch(Exception failure) {mapping="Automatisation refusée. Livebox : TCP 8443 → "+internal+" → "+mapper.local.getHostAddress()+". Garder le pare-feu Moyen.";}
            prefs().edit().putString("automatic_mapping",mapping).putInt("public_port",external).putLong("public_ip_measured_at",now)
                    .putString("box_wan_ip",wan).apply();
            if(!storedIp.equals(ip)||expires-now<48*3600000L||prefs().getBoolean("automatic_force_renew",false)) {
                long persisted=prefs().getLong("acme_retry_at",0);
                if(now<Math.max(retryAt,persisted)) {state("Nouvel essai certificat après le délai ACME. "+prefs().getString("automatic_last_error",""));return;}
                issue(ip);
                if(closed)return;
                server.reloadTls();internal=PhoneMcpSettings.httpsPort(context);
                try {PhonePortMapper.Lease lease=mapper.map(internal,external,1800);mainLease.set(lease);prefs().edit().putString("automatic_mapping",lease.method+" accepté · test extérieur requis").apply();}
                catch(Exception ignored) {prefs().edit().putString("automatic_mapping","Livebox : TCP 8443 → "+internal+" → "+mapper.local.getHostAddress()+". Garder le pare-feu Moyen.").apply();}
            }
            String base="https://"+ip+":"+external;
            if(!base.equals(PhoneMcpSettings.publicBase(context)))prefs().edit().putString("public_base",base).remove("external_client").remove("public_mcp_client").remove("chatgpt_client").apply();
            prefs().edit().putString("automatic_last_error","").apply();
            state("Certificat installé et renouvellement surveillé. Accès extérieur HTTPS/MCP à tester depuis ChatGPT.");
        }catch(InterruptedException stopped) {Thread.currentThread().interrupt();}
        catch(Exception error) {
            if(closed)return;
            String message=error instanceof IOException?error.getMessage():"Configuration automatique incomplète : consulter le diagnostic.";
            prefs().edit().putString("automatic_last_error",message==null?"Configuration automatique incomplète.":message).apply();state(message);
        }
    }
    private void issue(String ip) throws Exception {
        int port=prefs().getInt("acme_challenge_port",8080);AcmeChallengeServer challenge;
        try {challenge=new AcmeChallengeServer(mapper.local,port);}
        catch(BindException busy) {challenge=new AcmeChallengeServer(mapper.local,0);}
        prefs().edit().putInt("acme_challenge_port",challenge.port()).apply();
        PhonePortMapper.Lease temporary=null;
        try(AcmeChallengeServer responder=challenge) {
            String rule;
            try {temporary=mapper.map(challenge.port(),80,600);temporaryLease.set(temporary);rule=temporary.method+" : TCP 80 → "+challenge.port();}
            catch(Exception unavailable) {rule="Si la validation échoue : Livebox NAT/PAT, TCP externe 80 → interne "+challenge.port()+" → "+mapper.local.getHostAddress()+". Une seule règle initiale suffit ; garder le pare-feu Moyen.";}
            prefs().edit().putString("acme_challenge_mapping",rule).apply();
            state("Validation du certificat IP par Let’s Encrypt. "+rule);
            // Persist backoff before a request, so process death cannot cause an issuance loop.
            retryAt=System.currentTimeMillis()+3600000L;prefs().edit().putLong("acme_retry_at",retryAt).commit();
            KeyPair account=accountKey(),tls=newKey();
            String pem=new PhoneAcmeClient(account).issue(InetAddress.getByName(ip),tls,responder);
            if(closed)throw new InterruptedException();
            java.util.Collection<? extends java.security.cert.Certificate> parsed=CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            X509Certificate[] chain=parsed.toArray(new X509Certificate[0]);validateChain(chain,tls,ip);
            KeyStore keys=KeyStore.getInstance("PKCS12");keys.load(null,null);
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);String password=PhoneAcmeClient.base64(random);
            keys.setKeyEntry("phone-mcp",tls.getPrivate(),password.toCharArray(),chain);ByteArrayOutputStream out=new ByteArrayOutputStream();keys.store(out,password.toCharArray());
            PhoneMcpSettings.importCertificate(context,new ByteArrayInputStream(out.toByteArray()),password);
            prefs().edit().putString("automatic_certificate_ip",ip).putLong("automatic_certificate_expires",chain[0].getNotAfter().getTime())
                    .putLong("acme_retry_at",0).putBoolean("automatic_force_renew",false).putString("automatic_last_error","").commit();retryAt=0;
        }catch(PhoneAcmeClient.Failure failure) {
            retryAt=System.currentTimeMillis()+failure.retryMs;prefs().edit().putLong("acme_retry_at",retryAt).commit();throw failure;
        }finally {temporaryLease.set(null);if(temporary!=null)temporary.close();}
    }
    static void validateChain(X509Certificate[] chain,KeyPair key,String ip) throws Exception {
        if(chain.length==0)throw new IOException("Chaîne du certificat absente.");
        X509Certificate leaf=chain[0];leaf.checkValidity();
        if(!Arrays.equals(key.getPublic().getEncoded(),leaf.getPublicKey().getEncoded()))throw new IOException("Clé du certificat différente.");
        boolean matches=false;Collection<List<?>> names=leaf.getSubjectAlternativeNames();
        if(names!=null)for(List<?> name:names)if(Integer.valueOf(7).equals(name.get(0))&&InetAddress.getByName(ip).equals(InetAddress.getByName(name.get(1).toString())))matches=true;
        if(!matches)throw new IOException("Certificat ne correspondant pas à l’IP publique.");
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init((KeyStore)null);
        boolean trusted=false;for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager) {((X509TrustManager)manager).checkServerTrusted(chain,"RSA");trusted=true;}
        if(!trusted)throw new IOException("Chaîne HTTPS non reconnue par Android.");
    }
    private KeyPair accountKey() throws Exception {
        String encrypted=prefs().getString("acme_account_encrypted","");
        if(!encrypted.isEmpty()) {
            JSONObject stored=new JSONObject(new String(PhoneSecretStorage.decrypt(context,android.util.Base64.decode(encrypted,android.util.Base64.NO_WRAP)),StandardCharsets.UTF_8));
            KeyFactory f=KeyFactory.getInstance("RSA");return new KeyPair(f.generatePublic(new X509EncodedKeySpec(java.util.Base64.getDecoder().decode(stored.getString("public")))),f.generatePrivate(new PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(stored.getString("private")))));
        }
        KeyPair pair=newKey();JSONObject stored=new JSONObject().put("public",java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())).put("private",java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        byte[] protectedBytes=PhoneSecretStorage.encrypt(context,stored.toString().getBytes(StandardCharsets.UTF_8));
        if(!prefs().edit().putString("acme_account_encrypted",android.util.Base64.encodeToString(protectedBytes,android.util.Base64.NO_WRAP)).commit())throw new IOException("Compte ACME non enregistré.");return pair;
    }
    private static KeyPair newKey() throws Exception {KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}
    private static boolean validPublicV4(String address) {
        if(!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;
        try {return "ipv4_public".equals(PhoneNetworkDiagnostics.classify(InetAddress.getByName(address)));}catch(Exception ignored) {return false;}
    }
    private static String detect(String url) throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();
        try {connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
            if(connection.getResponseCode()!=200)throw new IOException("Détection IPv4 publique indisponible.");
            String ip;try(InputStream in=connection.getInputStream()) {ip=new String(PhoneHttp.body(in,-1,false,128),StandardCharsets.US_ASCII).trim();}
            if(!validPublicV4(ip))throw new IOException("La connexion ne fournit pas d’IPv4 publique utilisable.");return ip;
        } finally {connection.disconnect();}
    }
    static String report(Context c) {
        SharedPreferences p=PhoneMcpSettings.prefs(c);
        return "Mode : autonome · modeliseur3d\nÉtat local : "+(p.getBoolean("running",false)?p.getLong("local_test_at",0)>0?"LOCAL_OK":"actif · test local requis":"arrêté")
                +"\nTCP public : "+(p.getLong("external_client",0)>0?"PUBLIC_TCP_OK observé":"test extérieur requis")
                +"\nHTTPS : "+(p.getBoolean("https_running",false)?"listener TLS actif · validation extérieure requise":"non actif")
                +"\nMCP public : "+(p.getLong("public_mcp_client",0)>0?"appel extérieur réussi":"test extérieur requis")
                +"\nPlugin / ChatGPT : validation par appel réel requise"
                +"\nIPv4 locale : "+p.getString("automatic_local_ip","non mesurée")+"\nIPv4 publique : "+p.getString("automatic_public_ip","non mesurée")
                +"\nPasserelle : "+p.getString("automatic_gateway","non mesurée")
                +"\nNAT/PAT : "+p.getString("automatic_mapping","non testé")
                +"\nChallenge certificat : "+p.getString("acme_challenge_mapping","non démarré")
                +"\nCertificat expire : "+(p.getLong("automatic_certificate_expires",0)>0?new Date(p.getLong("automatic_certificate_expires",0)).toString():"non obtenu automatiquement")
                +"\nURL publique (2 outils non sensibles) : "+PhoneMcpSettings.connectionUrl(c)
                +"\nAutomatisation : "+p.getString("automatic_status","désactivée")
                +"\nDernière erreur : "+p.getString("automatic_last_error","");
    }
    @Override public void close() {closed=true;leaseWorker.shutdownNow();worker.shutdownNow();/* Main finite mapping expires without deleting a manual router rule. */}
}
