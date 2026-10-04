package com.chasmet.modeliseur3d.mcp;

import org.json.*;
import org.junit.Test;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;

public class PhoneAcmeClientTest {
    private static final String BASE="https://ca.example/",TOKEN="test-challenge-token-0123456789";
    private static KeyPair key() throws Exception {KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}
    private static final class Fixture implements PhoneAcmeClient.Transport,PhoneAcmeClient.Challenge {
        final KeyPair account;int accountCalls,nonce=1;boolean accepted,presented,cleared,finalized;String wrongIp="",presentedValue="";boolean badNonce;
        Fixture(KeyPair key) {account=key;}
        PhoneAcmeClient.Reply response(int code,String body,String location) {return new PhoneAcmeClient.Reply(code,body,"nonce-"+(++nonce),location,"");}
        public PhoneAcmeClient.Reply request(String url,String method,String body) throws Exception {
            String route=url.substring(BASE.length());
            if("GET".equals(method))return response(200,"{\"meta\":{\"profiles\":{\"shortlived\":\"yes\"}},\"newNonce\":\""+BASE+"nonce\",\"newAccount\":\""+BASE+"account\",\"newOrder\":\""+BASE+"orders\"}","");
            if("HEAD".equals(method))return response(200,"","");
            assertEquals("POST",method);JSONObject jws=new JSONObject(body);String p=jws.getString("protected"),payload=jws.getString("payload");
            Signature verify=Signature.getInstance("SHA256withRSA");verify.initVerify(account.getPublic());verify.update((p+"."+payload).getBytes(StandardCharsets.US_ASCII));
            assertTrue(verify.verify(Base64.getUrlDecoder().decode(jws.getString("signature"))));
            JSONObject header=new JSONObject(new String(Base64.getUrlDecoder().decode(p),StandardCharsets.UTF_8));assertEquals(url,header.getString("url"));assertEquals("nonce-"+nonce,header.getString("nonce"));
            String decoded=new String(Base64.getUrlDecoder().decode(payload),StandardCharsets.UTF_8);
            if(route.equals("account")) {accountCalls++;assertTrue(header.has("jwk"));assertTrue(new JSONObject(decoded).getBoolean("termsOfServiceAgreed"));
                if(badNonce&&accountCalls==1)return response(400,"{\"type\":\"urn:ietf:params:acme:error:badNonce\"}","");return response(201,"{}",BASE+"kid");}
            assertEquals(BASE+"kid",header.getString("kid"));
            switch(route) {
                case "orders":JSONObject order=new JSONObject(decoded);assertEquals("shortlived",order.getString("profile"));assertEquals("ip",order.getJSONArray("identifiers").getJSONObject(0).getString("type"));
                    return response(201,"{\"authorizations\":[\""+BASE+"auth\"]}",BASE+"order");
                case "auth":assertEquals("",decoded);return response(200,"{\"identifier\":{\"type\":\"ip\",\"value\":\""+(wrongIp.isEmpty()?"8.8.8.8":wrongIp)+"\"},\"status\":\""+(accepted?"valid":"pending")+"\",\"challenges\":[{\"type\":\"http-01\",\"token\":\""+TOKEN+"\",\"url\":\""+BASE+"challenge\"}]}","");
                case "challenge":assertTrue(presented);assertTrue(presentedValue.startsWith(TOKEN+"."));assertEquals("{}",decoded);accepted=true;return response(200,"{}","");
                case "order":assertEquals("",decoded);return response(200,"{\"status\":\""+(finalized?"valid":"ready")+"\",\"finalize\":\""+BASE+"finalize\",\"certificate\":\""+BASE+"certificate\"}","");
                case "finalize":assertTrue(Base64.getUrlDecoder().decode(new JSONObject(decoded).getString("csr")).length>400);finalized=true;return response(200,"{}","");
                case "certificate":assertEquals("",decoded);return response(200,"synthetic-certificate-chain","");
                default:throw new AssertionError(url);
            }
        }
        public void present(String token,String value) {assertEquals(TOKEN,token);presented=true;presentedValue=value;}
        public void clear() {cleared=true;presented=false;}
    }
    @Test public void issuesIpWithSignedJwsPostAsGetAndRecoversFromBadNonce() throws Exception {
        KeyPair account=key();Fixture fixture=new Fixture(account);fixture.badNonce=true;
        assertEquals("synthetic-certificate-chain",new PhoneAcmeClient(account,BASE+"directory",fixture,millis->{}).issue(InetAddress.getByName("8.8.8.8"),key(),fixture));
        assertEquals(2,fixture.accountCalls);assertTrue(fixture.finalized);assertTrue(fixture.cleared);assertFalse(fixture.presented);
    }
    @Test public void rejectsWrongAuthorizationIpAndAlwaysClearsChallenge() throws Exception {
        KeyPair account=key();Fixture fixture=new Fixture(account);fixture.wrongIp="1.1.1.1";
        assertThrows(IOException.class,()->new PhoneAcmeClient(account,BASE+"directory",fixture,millis->{}).issue(InetAddress.getByName("8.8.8.8"),key(),fixture));assertTrue(fixture.cleared);assertFalse(fixture.finalized);
    }
    @Test public void refusesAcmeEndpointOnAnotherHost() throws Exception {
        KeyPair account=key();PhoneAcmeClient.Transport transport=(url,method,body)->new PhoneAcmeClient.Reply(200,"{\"newNonce\":\"https://other.example/nonce\"}","","","");
        Fixture responder=new Fixture(account);assertThrows(IOException.class,()->new PhoneAcmeClient(account,BASE+"directory",transport,millis->{}).issue(InetAddress.getByName("8.8.8.8"),key(),responder));
    }
    @Test public void pcpResponseRequiresNoncePortsAndPositiveLifetime() throws Exception {
        byte[] nonce=new byte[12];Arrays.fill(nonce,(byte)17);
        byte[] request=PortMappingProtocol.pcp(InetAddress.getByName("192.168.1.22"),8080,80,600,nonce);
        assertEquals(64,request.length);assertEquals(6,request[36]);assertEquals(2,request[60]);
        byte[] reply=Arrays.copyOf(request,60);reply[1]=(byte)129;ByteBuffer.wrap(reply).putInt(4,300);reply[54]=(byte)255;reply[55]=(byte)255;reply[56]=8;reply[57]=8;reply[58]=8;reply[59]=8;
        assertEquals("8.8.8.8",PortMappingProtocol.pcpReply(reply,8080,80,nonce,false).externalIp);
        byte[] forged=reply.clone();forged[24]^=1;assertThrows(IOException.class,()->PortMappingProtocol.pcpReply(forged,8080,80,nonce,false));
        ByteBuffer.wrap(reply).putInt(4,0);assertThrows(IOException.class,()->PortMappingProtocol.pcpReply(reply,8080,80,nonce,false));
        assertEquals(0,PortMappingProtocol.pcpReply(reply,8080,80,nonce,true).lifetime);
    }
    @Test public void natPmpDoesNotAcceptADifferentPublicPort() throws Exception {
        byte[] reply=ByteBuffer.allocate(16).put((byte)0).put((byte)130).putShort((short)0).putInt(1).putShort((short)8080).putShort((short)80).putInt(600).array();
        assertEquals(80,PortMappingProtocol.pmpReply(reply,8080,80,false).externalPort);
        ByteBuffer.wrap(reply).putShort(10,(short)81);assertThrows(IOException.class,()->PortMappingProtocol.pmpReply(reply,8080,80,false));
    }
    @Test public void challengeServerOnlyServesTheActiveRandomPath() throws Exception {
        try(AcmeChallengeServer server=new AcmeChallengeServer(InetAddress.getLoopbackAddress(),0)) {
            server.present(TOKEN,TOKEN+".thumbprint");assertTrue(get(server.port(),"/.well-known/acme-challenge/"+TOKEN).endsWith(TOKEN+".thumbprint"));
            assertTrue(get(server.port(),"/").startsWith("HTTP/1.1 404"));assertTrue(get(server.port(),"/status").startsWith("HTTP/1.1 404"));
            server.clear();assertTrue(get(server.port(),"/.well-known/acme-challenge/"+TOKEN).startsWith("HTTP/1.1 404"));
        }
    }
    @Test public void routerXmlRejectsExternalEntitiesEvenWithWideEncoding() throws Exception {
        String hostile="<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]><root>&leak;</root>";
        assertThrows(IOException.class,()->PhonePortMapper.xml(hostile.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class,()->PhonePortMapper.xml(hostile.getBytes(StandardCharsets.UTF_16)));
        assertEquals("root",PhonePortMapper.xml("<root><value>ok</value></root>".getBytes(StandardCharsets.UTF_8)).getDocumentElement().getTagName());
    }
    private static String get(int port,String path) throws Exception {
        try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),port)) {
            socket.setSoTimeout(5000);socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            return new String(PhoneHttp.body(socket.getInputStream(),-1,false,8192),StandardCharsets.US_ASCII);
        }
    }
    @Test public void generatedCsrIsIndependentlyVerifiedByOpenSslWithIpSan() throws Exception {
        File csr=File.createTempFile("phone-ip-csr-",".der"),log=File.createTempFile("phone-ip-csr-",".txt");
        try {try(FileOutputStream out=new FileOutputStream(csr)) {out.write(IpCertificateRequest.csr(key(),InetAddress.getByName("8.8.8.8")));}
            Process p=new ProcessBuilder("openssl","req","-inform","DER","-in",csr.getPath(),"-verify","-text","-noout").redirectErrorStream(true).redirectOutput(log).start();
            assertTrue(p.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue());String text=new String(java.nio.file.Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8);assertTrue(text,text.contains("IP Address:8.8.8.8"));
        }finally {csr.delete();log.delete();}
    }
}
