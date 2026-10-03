package com.chasmet.modeliseur3d.mcp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.chasmet.modeliseur3d.model.*;
import com.chasmet.modeliseur3d.cloud.CloudApi;
import com.chasmet.modeliseur3d.update.UpdateManager;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Context-only reconstruction: no Activity, window or UI lifetime owns an MCP job. */
public final class LocalMcpGeneration {
    private LocalMcpGeneration() { }
    public static File generate(Context context, JSONObject command, TripoSREngine.Progress progress) throws Exception {
        String id = CloudApi.id(command.getString("id"));
        String mode = command.getString("mode");
        if (!mode.matches("(triposr|silhouettes)_(single|four)")) throw new IOException("Moteur MCP local inconnu.");
        boolean four = mode.endsWith("_four"), learned = mode.startsWith("triposr");
        JSONObject options = command.optJSONObject("options");
        String quality = options == null ? "balanced" : options.optString("quality", "balanced");
        int detail = "fast".equals(quality) ? 0 : "precise".equals(quality) ? 2 : 1;
        boolean smooth = options == null || options.optBoolean("smoothing", true);
        File folder = new File(context.getFilesDir(), "mcp_inputs/" + id);
        File output = new File(context.getFilesDir(), "cloud_models/" + id + ".glb");
        // A finished model survives network failures and is uploaded without another inference.
        if (output.isFile()) return output;
        File part = new File(output.getPath() + ".part");
        Bitmap[] images = new Bitmap[four ? 4 : 1];
        OfflineImageVolume.Result result = null;
        try {
            OfflineDepthField[] depths = learned ? null : new OfflineDepthField[images.length];
            for (int i = 0; i < images.length; i++) {
                progress.check(); progress.update("Détourage local · image " + (i + 1) + " / " + images.length);
                File cutout = new File(folder, "service-cutout-" + i + ".png");
                if (!cutout.isFile()) {
                    Bitmap source = BitmapFactory.decodeFile(new File(folder, "image-" + i + ".png").getPath());
                    if (source == null) throw new IOException("Image MCP illisible.");
                    try {
                        int max = Math.max(source.getWidth(), source.getHeight());
                        if (max > 1024) {
                            Bitmap scaled = Bitmap.createScaledBitmap(source, Math.max(3, source.getWidth()*1024/max),
                                    Math.max(3, source.getHeight()*1024/max), true);
                            if (scaled != source) { source.recycle(); source = scaled; }
                        }
                        AnimeSegmentationEngine.Mask mask = null;
                        if (!OfflineImageVolume.hasUsefulTransparency(source)) {
                            try (AnimeSegmentationEngine engine = new AnimeSegmentationEngine(context, 2)) {
                                mask = engine.segment(source);
                            }
                        }
                        progress.check();
                        try (OfflineImageVolume.Prepared ready = OfflineImageVolume.prepare(source, 43, mask)) {
                            File temp = new File(cutout.getPath() + ".part");
                            try {
                                try (OutputStream out = new FileOutputStream(temp)) {
                                    if (!ready.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Détourage non enregistré.");
                                }
                                progress.check();
                                if (!temp.renameTo(cutout)) throw new IOException("Détourage non enregistré.");
                            } finally { temp.delete(); }
                        }
                    } finally { source.recycle(); }
                }
                images[i] = BitmapFactory.decodeFile(cutout.getPath());
                if (images[i] == null) throw new IOException("Détourage MCP illisible.");
                if (!learned) {
                    File depth = new File(folder, "service-depth-" + i + ".bin");
                    if (depth.isFile()) depths[i] = OfflineDepthField.read(depth);
                    else {
                        progress.update("Profondeur IA locale · image " + (i + 1));
                        try (NeuralDepthEngine engine = new NeuralDepthEngine(context, 2, false)) {
                            NeuralDepthEngine.DepthMap map = engine.estimate(images[i]);
                            float[] samples = new float[128*128];
                            for (int y=0;y<128;y++) for (int x=0;x<128;x++) samples[y*128+x]=map.sample(x/127f,y/127f);
                            depths[i] = new OfflineDepthField(samples,128,128);
                        }
                        progress.check();
                        File temp = new File(depth.getPath()+".part");
                        try { depths[i].write(temp); if (!temp.renameTo(depth)) throw new IOException("Profondeur non enregistrée."); }
                        finally { temp.delete(); }
                    }
                }
            }
            progress.check();
            if (learned && !four) {
                TripoSRRefinedField field = TripoSREngine.reconstructSingleDetailed(context, images[0],
                        new File(folder,"service-triposr-0.bin"),TripoSREngine.CACHE_VERSION+":"+id+":0",
                        new int[]{128,192,256}[detail],progress);
                progress.check(); progress.update("Maillage détaillé et texture…");
                result = TripoSRSingleViewVolume.buildDetailed(field,images[0],smooth);
            } else if (learned) {
                File[] caches = new File[4]; String[] keys = new String[4];
                for (int i=0;i<4;i++) { caches[i]=new File(folder,"service-triposr-"+i+".bin"); keys[i]=TripoSREngine.CACHE_VERSION+":"+id+":"+i; }
                int side = new int[]{64,88,112}[detail];
                TripoSRField[] fields = TripoSREngine.reconstruct(context,images,caches,keys,side,progress);
                progress.check(); result = TripoSRFourViewVolume.build(images,fields,side,1f,smooth);
            } else {
                progress.update("Maillage et textures locaux…");
                result = four ? OfflineFourViewVolume.build(images,new int[]{64,88,112}[detail],1f,depths)
                        : OfflineImageVolume.buildPrepared(images[0],new int[]{80,112,144}[detail],.165f,0,"Détourage local",depths[0]);
            }
            progress.check(); progress.update("Sauvegarde du GLB sur le téléphone…");
            if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) throw new IOException("Stockage GLB indisponible.");
            JSONObject metadata = new JSONObject().put("appVersion",UpdateManager.currentVersion(context))
                    .put("engine",learned?"TripoSR":"Silhouettes").put("method",result.method).put("localOnly",true)
                    .put("mcpCommandId",id).put("inputViews",images.length).put("detail",detail).put("smoothing",smooth)
                    .put("projectName","ChatGPT "+id.substring(0,8)).put("generatedAt",System.currentTimeMillis())
                    .put("reconstructionMode",four?"four-view":"single-image").put("hiddenSurfacesEstimated",!four)
                    .put("textureMode",learned&&!four?"visible-photo-neural-hidden":"existing");
            ExternalViewerGlbExporter.write(part,result.mesh,result.texture,metadata);
            if (part.length()>64L*1024*1024) throw new IOException("GLB supérieur à 64 Mo.");
            progress.check(); if (!part.renameTo(output)) throw new IOException("GLB non enregistré.");
            try (Writer info = new OutputStreamWriter(new FileOutputStream(output.getPath()+".json"), StandardCharsets.UTF_8)) {
                info.write(metadata.toString());
            } catch (IOException ignored) { }
            context.getSharedPreferences("offline_workshop",Context.MODE_PRIVATE).edit().putString("last",id).apply();
            return output;
        } finally {
            part.delete(); for (Bitmap image : images) if (image!=null) image.recycle();
            if (result!=null) result.texture.recycle();
        }
    }
}
