package com.chasmet.modeliseur3d.mcp;

import com.chasmet.modeliseur3d.cloud.CloudApi;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Internal adapter for the same service worker. Every operation stays on-device. */
final class PhoneMcpApi extends CloudApi {
    private final PhoneMcpStore store;
    PhoneMcpApi(PhoneMcpStore store) throws IOException { super("https://localhost","");this.store=store; }
    @Override public JSONObject json(String path,JSONObject body) throws Exception {
        if("/api/poll".equals(path))return store.poll();
        if("/api/disconnect".equals(path)||"/api/heartbeat".equals(path))return new JSONObject().put("ok",true);
        throw new IOException("Opération directe inconnue.");
    }
    @Override public JSONObject updateLocalStatus(String id,String state,String message) throws Exception { return store.update(id,state,message); }
    @Override public void downloadLocalImage(String command,String reference,File output) throws Exception {
        File source=store.image(command,reference);
        if(!source.isFile())throw new IOException("Image locale manquante.");
        if(!source.getCanonicalPath().equals(output.getCanonicalPath())) {
            try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(output)){copy(in,out);}
        }
    }
    @Override public JSONObject uploadLocalResult(String command,File glb) throws Exception {
        if(!glb.equals(store.model(command))||!glb.isFile()||glb.length()<12||glb.length()>64L*1024*1024)throw new IOException("GLB direct invalide.");
        try(RandomAccessFile file=new RandomAccessFile(glb,"r")) {
            byte[] header=new byte[12];file.readFully(header);ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if(b.getInt()!=0x46546c67 || b.getInt()!=2 || Integer.toUnsignedLong(b.getInt())!=glb.length())throw new IOException("GLB direct incomplet.");
        }
        return store.update(command,"ready","GLB créé et disponible directement sur le téléphone.");
    }
}
