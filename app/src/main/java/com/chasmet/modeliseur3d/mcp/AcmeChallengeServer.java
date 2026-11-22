package com.chasmet.modeliseur3d.mcp;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Public HTTP-01 responder: one random challenge path, no application data or directory listing. */
final class AcmeChallengeServer implements AutoCloseable,PhoneAcmeClient.Challenge {
    private final ServerSocket listener=new ServerSocket();
    private final ThreadPoolExecutor clients=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private volatile boolean closed;
    private volatile String token="",authorization="";
    private final AtomicInteger observed=new AtomicInteger();
    int port() {return listener.getLocalPort();}
    AcmeChallengeServer(InetAddress local,int port) throws IOException {
        listener.setReuseAddress(true);
        try {listener.bind(new InetSocketAddress(local,port));}catch(IOException busy) {listener.close();clients.shutdownNow();throw busy;}
        Thread thread=new Thread(()->{
            while(!closed)try {
                Socket client=listener.accept();client.setSoTimeout(3000);
                try {clients.execute(()->serve(client));}catch(RejectedExecutionException full) {client.close();}
            }catch(IOException stopped) {if(closed)return;}
        },"PhoneAcmeChallenge");thread.setDaemon(true);thread.start();
    }
    @Override public void present(String token,String authorization) {this.authorization=authorization;this.token=token;observed.set(0);}
    @Override public void clear() {token="";authorization="";}
    int hits() {return observed.get();}
    private void serve(Socket socket) {
        try(Socket client=socket) {
            String request=PhoneHttp.readLine(client.getInputStream(),2048);String[] parts=request.split(" ");int size=0;
            while(true) {String line=PhoneHttp.readLine(client.getInputStream(),4096);size+=line.length();if(size>8192)throw new IOException("En-têtes trop grands.");if(line.isEmpty())break;}
            String active=token;boolean matches=!active.isEmpty()&&parts.length==3&&"GET".equals(parts[0])&&("/.well-known/acme-challenge/"+active).equals(parts[1]);
            if(matches)observed.incrementAndGet();PhoneHttp.respond(client.getOutputStream(),matches?200:404,matches?authorization:"");
        }catch(Exception ignored) { }
    }
    @Override public void close() {closed=true;clear();try {listener.close();}catch(IOException ignored) { }clients.shutdownNow();}
}
