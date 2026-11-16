package com.chasmet.modeliseur3d.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded parsing shared by gateway requests and the dedicated ACME challenge responder. */
final class PhoneHttp {
    static String readLine(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1) {if(b==10)return out.toString("US-ASCII").replaceAll("\r$","");if(out.size()>=max)throw new IOException("Ligne HTTP trop longue.");out.write(b);}throw new EOFException();
    }
    static byte[] body(InputStream in,int length,boolean chunked,int max) throws IOException {
        if(length>max||length< -1)throw new IOException("Réponse HTTP trop grande.");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];
        if(chunked) {
            while(true) {
                int size;try {size=Integer.parseInt(readLine(in,128).split(";",2)[0].trim(),16);}catch(NumberFormatException bad) {throw new IOException("Chunk invalide.");}
                if(size<0||size>max-out.size())throw new IOException("Réponse HTTP trop grande.");
                if(size==0) {int headers=0;while(true) {String l=readLine(in,4096);headers+=l.length();if(headers>8192)throw new IOException("Trailer trop grand.");if(l.isEmpty())break;}return out.toByteArray();}
                int remaining=size;while(remaining>0) {int n=in.read(buf,0,Math.min(buf.length,remaining));if(n<0)throw new EOFException();out.write(buf,0,n);remaining-=n;}
                if(!readLine(in,2).isEmpty())throw new IOException("Chunk invalide.");
            }
        }
        while(length<0||out.size()<length) {int n=in.read(buf,0,length<0?buf.length:Math.min(buf.length,length-out.size()));if(n<0) {if(length>=0)throw new EOFException();break;}if(out.size()+n>max)throw new IOException("Réponse HTTP trop grande.");out.write(buf,0,n);}
        return out.toByteArray();
    }
    static void respond(OutputStream out,int status,String text) throws IOException {
        byte[] body=text.getBytes(StandardCharsets.US_ASCII);
        out.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: text/plain\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
}
