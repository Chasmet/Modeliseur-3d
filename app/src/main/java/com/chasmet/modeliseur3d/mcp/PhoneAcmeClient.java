package com.chasmet.modeliseur3d.mcp;

import org.json.*;
import java.io.*;
import java.net.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** ACME v2 with IP identifiers, shortlived profile, JWS and POST-as-GET (RFC 8555/8738). */
final class PhoneAcmeClient {
    static final String DIRECTORY="https://acme-v02.api.letsencrypt.org/directory";
    interface Transport {Reply request(String url,String method,String body) throws Exception;}
    interface Challenge {void present(String token,String authorization) throws Exception;void clear();}
    interface Pause {void await(long milliseconds) throws Exception;}
    interface Progress {void update(String message);}
    static final class Reply {
        final int code;final String body,nonce,location,retryAfter;
        Reply(int c,String b,String n,String l,String r) {code=c;body=b;nonce=n;location=l;retryAfter=r;}
        JSONObject json() throws JSONException {return new JSONObject(body);}
    }
    static final class Failure extends IOException {
        final String type;final long retryMs,serverRetryMs;
        Failure(String type,long delay) {this(type,"",delay,0);}
        Failure(String type,String detail,long delay,long serverDelay) {
            super("Validation ACME refusée : "+type.substring(type.lastIndexOf(':')+1)+(detail.isEmpty()?"":" · "+detail));
            this.type=type;retryMs=delay;serverRetryMs=serverDelay;
        }
    }
    private final KeyPair account;
    private final Transport transport;
    private final Pause pause;
    private final String directory;
    private final Progress progress;
    private String nonce="",kid="",nonceUrl;
    private long deadline;
    PhoneAcmeClient(KeyPair account) {this(account,message->{});}
    PhoneAcmeClient(KeyPair account,Progress progress) {this(account,DIRECTORY,PhoneAcmeClient::https,Thread::sleep,progress);}
    PhoneAcmeClient(KeyPair account,String directory,Transport transport,Pause pause) {this(account,directory,transport,pause,message->{});}
    PhoneAcmeClient(KeyPair account,String directory,Transport transport,Pause pause,Progress progress) {this.account=account;this.directory=directory;this.transport=transport;this.pause=pause;this.progress=progress;}
    String issue(InetAddress address,KeyPair tls,Challenge responder) throws Exception {
        deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
        progress.update("Certificat : connexion à Let’s Encrypt…");
        JSONObject dir=transport.request(directory,"GET",null).json();nonceUrl=endpoint(dir.getString("newNonce"));
        JSONObject profiles=dir.getJSONObject("meta").optJSONObject("profiles");
        if(profiles==null||!profiles.has("shortlived"))throw new IOException("Le service ACME ne propose pas de certificat IP court.");
        Reply registered=post(endpoint(dir.getString("newAccount")),new JSONObject().put("termsOfServiceAgreed",true).toString(),true);
        kid=endpoint(registered.location);
        progress.update("Certificat : demande pour l’IP publique détectée…");
        Reply ordered=post(endpoint(dir.getString("newOrder")),new JSONObject().put("profile","shortlived")
                .put("identifiers",new JSONArray().put(new JSONObject().put("type","ip").put("value",address.getHostAddress()))).toString(),false);
        String orderUrl=endpoint(ordered.location);JSONObject order=ordered.json();JSONArray authorizations=order.getJSONArray("authorizations");
        try {
            for(int i=0;i<authorizations.length();i++) {
                String authUrl=endpoint(authorizations.getString(i));JSONObject auth=post(authUrl,"",false).json();
                JSONObject identifier=auth.getJSONObject("identifier");
                if(!"ip".equals(identifier.optString("type"))||!address.equals(InetAddress.getByName(identifier.getString("value"))))throw new IOException("Identifiant ACME inattendu.");
                if("valid".equals(auth.optString("status")))continue;
                JSONArray challenges=auth.getJSONArray("challenges");JSONObject challenge=null;
                for(int j=0;j<challenges.length();j++)if("http-01".equals(challenges.getJSONObject(j).optString("type")))challenge=challenges.getJSONObject(j);
                if(challenge==null)throw new IOException("Validation HTTP-01 indisponible.");
                String token=challenge.getString("token");if(!token.matches("[A-Za-z0-9_-]{16,256}"))throw new IOException("Token ACME invalide.");
                progress.update("Certificat : validation HTTP-01 depuis Internet…");
                responder.present(token,token+"."+thumbprint());post(endpoint(challenge.getString("url")),"{}",false);
                waitState(authUrl,"valid",45);responder.clear();
            }
            order=waitState(orderUrl,"ready",20);
            progress.update("Certificat : validation du CSR et émission…");
            post(endpoint(order.getString("finalize")),new JSONObject().put("csr",base64(IpCertificateRequest.csr(tls,address))).toString(),false);
            order=waitState(orderUrl,"valid",45);
            progress.update("Certificat : récupération et vérification de la chaîne HTTPS…");
            return post(endpoint(order.getString("certificate")),"",false).body;
        } finally {responder.clear();}
    }
    private JSONObject waitState(String url,String wanted,int attempts) throws Exception {
        for(int i=0;i<attempts;i++) {
            checkDeadline();
            Reply reply=post(url,"",false);JSONObject state=reply.json();String status=state.optString("status");
            if(wanted.equals(status))return state;
            if("invalid".equals(status)||"expired".equals(status)||"revoked".equals(status)||"deactivated".equals(status)) {
                JSONObject error=state.optJSONObject("error");JSONArray challenges=state.optJSONArray("challenges");
                if(error==null&&challenges!=null)for(int j=0;j<challenges.length();j++) {
                    JSONObject item=challenges.optJSONObject(j);
                    if(item!=null&&item.optJSONObject("error")!=null) {error=item.optJSONObject("error");break;}
                }
                throw failure(error,reply,"urn:ietf:params:acme:error:challengeFailed");
            }
            pause.await(Math.max(2000,Math.min(15000,retryDelay(reply.retryAfter,2000))));
        }
        throw new IOException("Validation ACME expirée : accès au port public 80 à vérifier.");
    }
    private Reply post(String url,String payload,boolean jwk) throws Exception {
        for(int attempt=0;attempt<3;attempt++) {
            checkDeadline();
            if(nonce.isEmpty())nonce=transport.request(nonceUrl,"HEAD",null).nonce;
            if(nonce.isEmpty())throw new IOException("Nonce ACME absent.");
            JSONObject protectedHeader=new JSONObject().put("alg","RS256").put("nonce",nonce).put("url",url);
            if(jwk)protectedHeader.put("jwk",jwk());else protectedHeader.put("kid",kid);
            String protectedBytes=base64(protectedHeader.toString().getBytes(StandardCharsets.UTF_8));String bodyBytes=base64(payload.getBytes(StandardCharsets.UTF_8));
            Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(account.getPrivate());signature.update((protectedBytes+"."+bodyBytes).getBytes(StandardCharsets.US_ASCII));
            Reply reply=transport.request(url,"POST",new JSONObject().put("protected",protectedBytes).put("payload",bodyBytes).put("signature",base64(signature.sign())).toString());
            nonce=reply.nonce==null?"":reply.nonce;
            if(reply.code>=200&&reply.code<300)return reply;
            JSONObject problem;try {problem=reply.json();}catch(JSONException bad) {problem=new JSONObject().put("type","urn:ietf:params:acme:error:http");}
            String type=problem.optString("type","urn:ietf:params:acme:error:unknown");
            if(type.endsWith(":badNonce")&&attempt<2)continue;
            throw failure(problem,reply,"urn:ietf:params:acme:error:unknown");
        }
        throw new IOException("Nonce ACME refusé.");
    }
    private static Failure failure(JSONObject problem,Reply reply,String fallback) {
        String type=problem==null?fallback:problem.optString("type",fallback);
        String detail=problem==null?"":problem.optString("detail","").replaceAll("[\\p{Cntrl}]"," ");
        if(detail.length()>400)detail=detail.substring(0,400);
        long serverDelay=retryDelay(reply.retryAfter,0);
        if(type.endsWith(":rateLimited")||reply.code==429)serverDelay=Math.max(3600000L,serverDelay);
        return new Failure(type,detail,Math.max(3600000L,serverDelay),serverDelay);
    }
    private JSONObject jwk() throws Exception {
        RSAPublicKey rsa=(RSAPublicKey)account.getPublic();
        return new JSONObject().put("kty","RSA").put("e",base64(unsigned(rsa.getPublicExponent()))).put("n",base64(unsigned(rsa.getModulus())));
    }
    private void checkDeadline() throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(System.nanoTime()>deadline)throw new IOException("Délai ACME dépassé : accès au challenge HTTP-01 à vérifier.");
    }
    private String thumbprint() throws Exception {
        JSONObject key=jwk();String canonical="{\"e\":\""+key.getString("e")+"\",\"kty\":\"RSA\",\"n\":\""+key.getString("n")+"\"}";
        return base64(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    private String endpoint(String url) throws Exception {
        URI root=new URI(directory),u=new URI(url);
        if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||u.getFragment()!=null||!Objects.equals(root.getHost(),u.getHost())||root.getPort()!=u.getPort())throw new IOException("Adresse ACME inattendue.");
        return url;
    }
    static String base64(byte[] bytes) {return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static byte[] unsigned(BigInteger number) {byte[] bytes=number.toByteArray();return bytes[0]==0?Arrays.copyOfRange(bytes,1,bytes.length):bytes;}
    private static long retryDelay(String value,long fallback) {
        if(value==null||value.isEmpty())return fallback;
        try {return Math.min(86400000L,Math.multiplyExact(Long.parseLong(value),1000L));}catch(Exception ignored) { }
        try {java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);return Math.max(0,Math.min(86400000L,f.parse(value).getTime()-System.currentTimeMillis()));}catch(Exception ignored) {return fallback;}
    }
    static Reply https(String url,String method,String body) throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent","Modeliseur3D-Android-ACME/1");connection.setRequestProperty("Accept","application/json, application/pem-certificate-chain");
            if(body!=null) {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/jose+json");connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=connection.getOutputStream()) {out.write(bytes);}}
            int code=connection.getResponseCode();InputStream stream=code<400?connection.getInputStream():connection.getErrorStream();String response="";
            if(stream!=null)try(InputStream input=stream) {response=new String(PhoneHttp.body(input,-1,false,131072),StandardCharsets.UTF_8);}
            return new Reply(code,response,connection.getHeaderField("Replay-Nonce"),connection.getHeaderField("Location"),connection.getHeaderField("Retry-After"));
        } finally {connection.disconnect();}
    }
}
