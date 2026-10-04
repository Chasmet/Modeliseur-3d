package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import android.net.*;
import android.net.wifi.WifiManager;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Only maps this phone's own TCP listeners; never changes Wi-Fi or the router firewall. */
final class PhonePortMapper {
    final InetAddress local,gateway;
    private final Context context;
    private final Map<Integer,Lease> leases=new HashMap<>();
    private String control,service;
    private boolean discovered;
    final class Lease implements AutoCloseable {
        final int internal,external,seconds;
        final String method,ip;
        final byte[] nonce;
        final boolean created;
        final long renewAt;
        Lease(int in,int out,int ttl,String type,String address,byte[] n,boolean own) {internal=in;external=out;seconds=ttl;method=type;ip=address;nonce=n;created=own;renewAt=System.currentTimeMillis()+Math.max(5000L,ttl*500L);}
        @Override public void close() {
            synchronized(PhonePortMapper.this) {
            if(!created)return;
            try {
                if("PCP".equals(method))pcp(internal,external,0,nonce);
                else if("NAT-PMP".equals(method))pmp(internal,external,0);
                else {
                    Document entry=soap("GetSpecificPortMappingEntry",externalArguments(external));
                    if(local.getHostAddress().equals(value(entry,"NewInternalClient"))&&Integer.toString(internal).equals(value(entry,"NewInternalPort"))
                            &&description(external).equals(value(entry,"NewPortMappingDescription")))soap("DeletePortMapping",externalArguments(external));
                }
            }catch(Exception ignored) { /* Finite leases expire if cleanup is interrupted. */ }
            leases.remove(external);
            }
        }
    }
    PhonePortMapper(Context c,InetAddress local,InetAddress gateway) {context=c.getApplicationContext();this.local=local;this.gateway=gateway;}
    synchronized void retryDiscovery() {if(control==null)discovered=false;}
    static PhonePortMapper active(Context c) throws Exception {
        ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)throw new IOException("Réseau Android indisponible.");
        for(Network n:cm.getAllNetworks()) {
            if(android.os.Build.VERSION.SDK_INT>=23&&!n.equals(cm.getActiveNetwork()))continue;
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);LinkProperties lp=cm.getLinkProperties(n);
            if(caps==null||lp==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
            InetAddress local=null,gateway=null;
            for(LinkAddress a:lp.getLinkAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress())local=a.getAddress();
            for(RouteInfo r:lp.getRoutes())if(r.isDefaultRoute()&&r.getGateway() instanceof Inet4Address)gateway=r.getGateway();
            if(local!=null&&gateway!=null&&!gateway.isAnyLocalAddress())return new PhonePortMapper(c,local,gateway);
        }
        throw new IOException("IPv4 Wi-Fi et passerelle nécessaires pour la redirection de box.");
    }
    synchronized String wan() throws Exception {
        try {discover();String ip=value(soap("GetExternalIPAddress",""),"NewExternalIPAddress");if(!ip.isEmpty())return ip;}catch(Exception ignored) { }
        return PortMappingProtocol.pmpAddress(udp(new byte[]{0,0}));
    }
    synchronized Lease map(int internal,int external,int seconds) throws Exception {
        Lease old=leases.get(external);
        if(old!=null&&old.internal!=internal&&old.created)old.close();
        if(old!=null&&old.internal==internal) {
            try {
                if("PCP".equals(old.method))return keep(pcp(internal,external,seconds,old.nonce));
                if("NAT-PMP".equals(old.method))return keep(pmp(internal,external,seconds));
            }catch(Exception ignored) { }
        }
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        try {return keep(pcp(internal,external,seconds,nonce));}catch(Exception ignored) { }
        try {return keep(pmp(internal,external,seconds));}catch(Exception ignored) { }
        discover();
        try {
            Document entry=soap("GetSpecificPortMappingEntry",externalArguments(external));
            if(!local.getHostAddress().equals(value(entry,"NewInternalClient"))||!Integer.toString(internal).equals(value(entry,"NewInternalPort")))
                throw new ExistingMappingException();
            boolean own=description(external).equals(value(entry,"NewPortMappingDescription"));
            if(!own) {
                if(!"1".equals(value(entry,"NewEnabled")))throw new IOException("La règle existante est désactivée.");
                return keep(new Lease(internal,external,seconds,"UPnP règle existante","",null,false));
            }
        }catch(ExistingMappingException conflict) {throw new IOException("Port externe déjà réservé à un autre service.");}
        catch(IOException missing) {
            // 714 is the only fault that means the port has no existing entry.
            if(!(missing instanceof SoapFault)||((SoapFault)missing).code!=714)throw missing;
        }
        soap("AddPortMapping",externalArguments(external)+element("NewInternalPort",Integer.toString(internal))
                +element("NewInternalClient",local.getHostAddress())+element("NewEnabled","1")
                +element("NewPortMappingDescription",description(external))+element("NewLeaseDuration",Integer.toString(seconds)));
        return keep(new Lease(internal,external,seconds,"UPnP","",null,true));
    }
    private Lease keep(Lease lease) {leases.put(lease.external,lease);return lease;}
    private Lease pcp(int in,int out,int ttl,byte[] nonce) throws Exception {
        PortMappingProtocol.Mapping m=PortMappingProtocol.pcpReply(udp(PortMappingProtocol.pcp(local,in,out,ttl,nonce)),in,out,nonce,ttl==0);
        return new Lease(in,out,m.lifetime,"PCP",m.externalIp,nonce,true);
    }
    private Lease pmp(int in,int out,int ttl) throws Exception {
        PortMappingProtocol.Mapping m=PortMappingProtocol.pmpReply(udp(PortMappingProtocol.pmp(in,out,ttl)),in,out,ttl==0);
        return new Lease(in,out,m.lifetime,"NAT-PMP","",null,true);
    }
    private byte[] udp(byte[] request) throws IOException {
        try(DatagramSocket socket=new DatagramSocket(new InetSocketAddress(local,0))) {
            socket.connect(gateway,5351);socket.setSoTimeout(1500);
            socket.send(new DatagramPacket(request,request.length));byte[] response=new byte[1200];DatagramPacket packet=new DatagramPacket(response,response.length);socket.receive(packet);
            return Arrays.copyOf(response,packet.getLength());
        }
    }
    private void discover() throws Exception {
        if(control!=null)return;
        if(discovered)throw new IOException("UPnP IGD indisponible.");discovered=true;
        WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock=wifi==null?null:wifi.createMulticastLock("Modeliseur:McpDiscovery");
        try {
            if(lock!=null)lock.acquire();
            try(DatagramSocket socket=new DatagramSocket(new InetSocketAddress(local,0))) {
                socket.setSoTimeout(2500);
                String query="M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
                byte[] bytes=query.getBytes(StandardCharsets.US_ASCII);socket.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("239.255.255.250"),1900));
                long end=System.currentTimeMillis()+3000;
                while(System.currentTimeMillis()<end) {
                    byte[] response=new byte[8192];DatagramPacket packet=new DatagramPacket(response,response.length);socket.receive(packet);
                    if(!packet.getAddress().equals(gateway))continue;
                    String location="";
                    for(String line:new String(response,0,packet.getLength(),StandardCharsets.US_ASCII).split("\r?\n"))if(line.toLowerCase(Locale.ROOT).startsWith("location:"))location=line.substring(9).trim();
                    if(location.isEmpty())continue;
                    URI uri=routerUri(location);Document description=xml(http(uri,"GET",null,null));
                    NodeList services=description.getElementsByTagNameNS("*","service");
                    for(int i=0;i<services.getLength();i++) {
                        Element entry=(Element)services.item(i);String type=value(entry,"serviceType");
                        if(type.matches("urn:schemas-upnp-org:service:WAN(IP|PPP)Connection:[12]")) {
                            control=routerUri(uri.resolve(value(entry,"controlURL")).toString()).toString();service=type;return;
                        }
                    }
                }
            }
        } finally {if(lock!=null&&lock.isHeld())lock.release();}
        throw new IOException("UPnP IGD indisponible.");
    }
    private URI routerUri(String url) throws Exception {
        URI u=new URI(url);
        if(!"http".equals(u.getScheme())||u.getUserInfo()!=null||u.getFragment()!=null||u.getHost()==null||!InetAddress.getByName(u.getHost()).equals(gateway))
            throw new IOException("Adresse UPnP différente de la passerelle.");
        return u;
    }
    private Document soap(String action,String arguments) throws Exception {
        if(control==null)throw new IOException("UPnP non découvert.");
        String body="<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"
                +action+" xmlns:u=\""+service+"\">"+arguments+"</u:"+action+"></s:Body></s:Envelope>";
        Document response=xml(http(routerUri(control),"POST",body,"\""+service+"#"+action+"\""));
        String code=value(response,"errorCode");if(!code.isEmpty())throw new SoapFault(Integer.parseInt(code));return response;
    }
    private byte[] http(URI uri,String method,String body,String action) throws IOException {
        // Plain HTTP is restricted to the measured local gateway; Android cleartext policy is untouched.
        try(Socket socket=new Socket()) {
            socket.bind(new InetSocketAddress(local,0));socket.connect(new InetSocketAddress(gateway,uri.getPort()<0?80:uri.getPort()),2500);socket.setSoTimeout(3000);
            byte[] bytes=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
            String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
            String head=method+" "+path+" HTTP/1.1\r\nHost: "+uri.getAuthority()+"\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n"
                    +(action==null?"":"Content-Type: text/xml; charset=utf-8\r\nSOAPAction: "+action+"\r\n")+"\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
            InputStream input=socket.getInputStream();String status=PhoneHttp.readLine(input,2048);int headers=0,length=-1;boolean chunked=false;
            while(true) {String line=PhoneHttp.readLine(input,4096);headers+=line.length();if(headers>16384)throw new IOException("En-têtes box trop grands.");if(line.isEmpty())break;
                String lower=line.toLowerCase(Locale.ROOT);if(lower.startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());if(lower.startsWith("transfer-encoding:")&&lower.contains("chunked"))chunked=true;}
            return PhoneHttp.body(input,length,chunked,131072);
        }
    }
    static Document xml(byte[] bytes) throws Exception {
        // Accept text XML only; UTF-16/32 could hide a DTD from an ASCII precheck.
        for(byte value:bytes)if(value==0)throw new IOException("XML UPnP non textuel.");
        String value=new String(bytes,StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        if(value.contains("<!DOCTYPE")||value.contains("<!ENTITY"))throw new IOException("DTD UPnP interdit.");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static String value(Node node,String name) {
        NodeList list=node instanceof Document?((Document)node).getElementsByTagNameNS("*",name):((Element)node).getElementsByTagNameNS("*",name);
        return list.getLength()==0?"":list.item(0).getTextContent().trim();
    }
    private static String element(String key,String value) {return "<"+key+">"+value+"</"+key+">";}
    private static String externalArguments(int port) {return element("NewRemoteHost","")+element("NewExternalPort",Integer.toString(port))+element("NewProtocol","TCP");}
    private static String description(int port) {return port==80?"ModeliseurACME":"ModeliseurMCP";}
    private static final class ExistingMappingException extends IOException { }
    private static final class SoapFault extends IOException {final int code;SoapFault(int c) {super("UPnP refuse la redirection ("+c+").");code=c;}}
}
