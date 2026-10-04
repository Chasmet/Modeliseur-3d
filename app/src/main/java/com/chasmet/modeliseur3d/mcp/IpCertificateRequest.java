package com.chasmet.modeliseur3d.mcp;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Arrays;

/** Small DER encoder for a PKCS#10 request containing exactly one IP subjectAltName. */
final class IpCertificateRequest {
    private IpCertificateRequest() { }
    static byte[] csr(KeyPair key, InetAddress ip) throws Exception {
        byte[] cn=sequence(tag(0x31, sequence(oid(2,5,4,3),tag(12,ip.getHostAddress().getBytes(StandardCharsets.UTF_8)))));
        byte[] san=sequence(oid(2,5,29,17),tag(4,sequence(tag(0x87,ip.getAddress()))));
        byte[] extension=sequence(oid(1,2,840,113549,1,9,14),tag(0x31,sequence(san)));
        byte[] info=sequence(tag(2,new byte[]{0}),cn,key.getPublic().getEncoded(),tag(0xa0,extension));
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update(info);
        byte[] signature=signer.sign(),bits=new byte[signature.length+1];System.arraycopy(signature,0,bits,1,signature.length);
        return sequence(info,sequence(oid(1,2,840,113549,1,1,11),tag(5,new byte[0])),tag(3,bits));
    }
    private static byte[] sequence(byte[]... children) {return tag(0x30,join(children));}
    private static byte[] join(byte[]... children) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] c:children)out.write(c,0,c.length);return out.toByteArray();
    }
    private static byte[] tag(int type,byte[] body) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(type);
        if(body.length<128)out.write(body.length);
        else {int n=body.length>65535?3:body.length>255?2:1;out.write(128+n);for(int i=n-1;i>=0;i--)out.write((body.length>>(8*i))&255);}
        out.write(body,0,body.length);return out.toByteArray();
    }
    private static byte[] oid(int... parts) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(parts[0]*40+parts[1]);
        for(int i=2;i<parts.length;i++) {int value=parts[i],n=1;while((value>>>(7*n))!=0&&n<4)n++;for(int j=n-1;j>=0;j--)out.write(((value>>>(7*j))&127)|(j>0?128:0));}
        return tag(6,out.toByteArray());
    }
}
