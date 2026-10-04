package com.chasmet.modeliseur3d.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** RFC 6887 PCP MAP and RFC 6886 NAT-PMP TCP wire formats. No Android dependencies. */
final class PortMappingProtocol {
    static final class Mapping {
        final int internalPort,externalPort,lifetime;
        final String externalIp;
        Mapping(int internal,int external,int seconds,String ip) {internalPort=internal;externalPort=external;lifetime=seconds;externalIp=ip;}
    }
    static byte[] pcp(InetAddress local,int internal,int external,int seconds,byte[] nonce) {
        if(nonce.length!=12)throw new IllegalArgumentException("PCP nonce");
        ByteBuffer out=ByteBuffer.allocate(64);out.put((byte)2).put((byte)1).putShort((short)0).putInt(seconds);
        byte[] ip=local.getAddress();if(ip.length==4) {out.put(new byte[10]).putShort((short)0xffff).put(ip);}else out.put(ip);
        out.put(nonce).put((byte)6).put(new byte[3]).putShort((short)internal).putShort((short)external).put(new byte[16]);
        // PREFER_FAILURE avoids silently changing the requested external port.
        out.put((byte)2).put(new byte[3]);return out.array();
    }
    static Mapping pcpReply(byte[] bytes,int internal,int external,byte[] nonce,boolean deleting) throws IOException {
        if(bytes.length<60||bytes[0]!=2||(bytes[1]&255)!=129||bytes[3]!=0||bytes[36]!=6
                ||!Arrays.equals(nonce,Arrays.copyOfRange(bytes,24,36)))throw new IOException("Réponse PCP refusée.");
        ByteBuffer in=ByteBuffer.wrap(bytes);int lifetime=in.getInt(4),actualInternal=in.getShort(40)&65535,actualExternal=in.getShort(42)&65535;
        if(actualInternal!=internal||actualExternal!=external||(!deleting&&lifetime<=0))throw new IOException("Redirection PCP inattendue.");
        byte[] address=Arrays.copyOfRange(bytes,44,60);
        return new Mapping(internal,external,lifetime,InetAddress.getByAddress(address).getHostAddress());
    }
    static byte[] pmp(int internal,int external,int seconds) {
        return ByteBuffer.allocate(12).put((byte)0).put((byte)2).putShort((short)0).putShort((short)internal).putShort((short)external).putInt(seconds).array();
    }
    static Mapping pmpReply(byte[] bytes,int internal,int external,boolean deleting) throws IOException {
        if(bytes.length!=16||bytes[0]!=0||(bytes[1]&255)!=130||ByteBuffer.wrap(bytes).getShort(2)!=0)throw new IOException("Réponse NAT-PMP refusée.");
        ByteBuffer in=ByteBuffer.wrap(bytes);int actualInternal=in.getShort(8)&65535,actualExternal=in.getShort(10)&65535,lifetime=in.getInt(12);
        if(actualInternal!=internal||actualExternal!=external||(!deleting&&lifetime<=0))throw new IOException("Redirection NAT-PMP inattendue.");
        return new Mapping(internal,external,lifetime,"");
    }
    static String pmpAddress(byte[] bytes) throws IOException {
        if(bytes.length!=12||bytes[0]!=0||(bytes[1]&255)!=128||ByteBuffer.wrap(bytes).getShort(2)!=0)throw new IOException("Adresse NAT-PMP indisponible.");
        return InetAddress.getByAddress(Arrays.copyOfRange(bytes,8,12)).getHostAddress();
    }
}
