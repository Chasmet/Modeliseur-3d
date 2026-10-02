package com.chasmet.modeliseur3d.model;

import android.content.Context;
import android.graphics.*;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Genuine embedded TripoSR encoder + NeRF decoder. Only CPU and private local files. */
public final class TripoSREngine {
    public static final String CACHE_VERSION="triposr-int4-triplane-v2";
    private static final int SIZE=512,CHANNELS=40,PLANE=64,SCENE=3*CHANNELS*PLANE*PLANE,BATCH=2048,DEFAULT_SIDE=64;
    private static final int SCENE_MAGIC=0x5453504c;
    private static final int[] FIELD_SIDES={64,88,112};
    private static final String[] ASSETS={"triposr_encoder_int4.onnx","triposr_encoder_int4.onnx.data","triposr_decoder.onnx","triposr_decoder.onnx.data"};
    private static final String[] SHA={"76dab077ff2768898ace523c72e0a017134bfd3628c4b15fd6f65cf304439089","fd5be249ab455b368812ba25095a2720e561ab0dcdeb42c81d922f7f67669a00","90b322cae570324f1d699f7307d4f056275b3bc8564db8568a5aebe403691424","9c3b1412ecbc803983003091939e449f9be3078c1c9a1880d5c5196a40a31d6c"};
    public interface Progress {void update(String message);void check();}
    private static void check(Progress progress){if(Thread.currentThread().isInterrupted())throw new CancellationException();progress.check();}

    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
            byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);
        }
        StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }

    private static File unpack(Context context,Progress progress)throws Exception{
        File folder=new File(context.getFilesDir(),"triposr-int4-v1");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Stockage des modèles IA indisponible.");
        for(int i=0;i<ASSETS.length;i++){
            check(progress);File file=new File(folder,ASSETS[i]);
            if(file.isFile()&&hash(file).equals(SHA[i]))continue;
            progress.update("Préparation locale de TripoSR · "+(i+1)+" / 4 fichiers embarqués…");
            File part=new File(file.getPath()+".part");
            try{
                try(InputStream in=context.getAssets().open("models/"+ASSETS[i]);FileOutputStream out=new FileOutputStream(part)){
                    byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){check(progress);out.write(b,0,n);}out.getFD().sync();
                }
                if(!hash(part).equals(SHA[i]))throw new IOException("Poids TripoSR incomplets. Réinstalle la mise à jour officielle.");
                if(!part.renameTo(file))throw new IOException("Copie locale de TripoSR impossible.");
            }finally{part.delete();}
        }
        return folder;
    }

    private static OrtSession.SessionOptions options()throws OrtException{
        OrtSession.SessionOptions options=new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);
        options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false);
        return options;
    }

    /** Alpha-composite a centered 90% foreground on 0.5 grey, matching TripoSR preprocessing. */
    public static float[] prepareInput(Bitmap cutout){
        Bitmap canvas=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
        try{
            Canvas c=new Canvas(canvas);c.drawColor(Color.rgb(128,128,128));
            float scale=SIZE*.9f/Math.max(cutout.getWidth(),cutout.getHeight());
            float w=cutout.getWidth()*scale,h=cutout.getHeight()*scale;
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(cutout,null,new RectF((SIZE-w)/2,(SIZE-h)/2,(SIZE+w)/2,(SIZE+h)/2),p);
            int[] pixels=new int[SIZE*SIZE];canvas.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE);
            float[] input=new float[3*pixels.length];
            for(int i=0;i<pixels.length;i++){
                input[i]=Color.red(pixels[i])/255f;
                input[pixels.length+i]=Color.green(pixels[i])/255f;
                input[2*pixels.length+i]=Color.blue(pixels[i])/255f;
            }
            return input;
        }finally{canvas.recycle();}
    }

    public static TripoSRField[] reconstruct(Context context,Bitmap[] images,File[] caches,String[] keys,Progress progress)throws Exception{
        return reconstruct(context,images,caches,keys,DEFAULT_SIDE,progress);
    }

    /**
     * V6.2: cache the learned triplanes, not only a 64³ density field.
     * The decoder therefore samples the neural representation at the requested
     * 64/88/112 resolution without rerunning the expensive image encoder.
     */
    public static TripoSRField[] reconstruct(Context context,Bitmap[] images,File[] caches,String[] keys,int requestedSide,Progress progress)throws Exception{
        if(images==null||images.length!=4||caches==null||caches.length!=4||keys==null||keys.length!=4)
            throw new IllegalArgumentException("TripoSR utilise les quatre vues.");
        int side=Math.max(48,Math.min(112,requestedSide));
        TripoSRField[] fields=new TripoSRField[4];
        float[][] scenes=new float[4][];
        boolean needsEncoder=false,needsDecoder=false;

        for(int i=0;i<4;i++){
            check(progress);
            if(images[i]==null||images[i].isRecycled())throw new IOException("Une vue TripoSR est absente.");
            File marker=new File(caches[i].getPath()+".key");
            boolean valid=caches[i].isFile()&&marker.isFile()&&readKey(marker).equals(keys[i]);
            if(!valid){
                caches[i].delete();marker.delete();deleteDerived(caches[i]);
                needsEncoder=true;needsDecoder=true;continue;
            }
            File fieldFile=fieldCache(caches[i],side);
            if(fieldFile.isFile()){
                try{fields[i]=TripoSRField.read(fieldFile);}
                catch(IOException e){fieldFile.delete();}
            }
            if(fields[i]==null){
                try{scenes[i]=readScene(caches[i]);needsDecoder=true;}
                catch(IOException e){caches[i].delete();marker.delete();deleteDerived(caches[i]);needsEncoder=true;}
            }
        }

        boolean allReady=true;for(TripoSRField field:fields)if(field==null){allReady=false;break;}
        if(allReady){
            progress.update("Les quatre formes IA "+side+"³ sont reprises du cache local.");
            return fields;
        }

        File folder=unpack(context,progress);
        OrtEnvironment environment=OrtEnvironment.getEnvironment();
        String[] labels={"Face","Dos","Profil droit","Profil gauche"};

        if(needsEncoder){
            try(OrtSession.SessionOptions options=options();
                OrtSession encoder=environment.createSession(new File(folder,ASSETS[0]).getPath(),options)){
                for(int i=0;i<4;i++)if(fields[i]==null&&scenes[i]==null){
                    check(progress);
                    File marker=new File(caches[i].getPath()+".key");
                    if(caches[i].isFile()&&marker.isFile()&&readKey(marker).equals(keys[i])){
                        try{scenes[i]=readScene(caches[i]);continue;}catch(IOException ignored){}
                    }
                    progress.update(labels[i]+" · TripoSR IA 3D · "+(i+1)+" / 4 · encodage CPU local…");
                    try(OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(prepareInput(images[i])),new long[]{1,3,SIZE,SIZE});
                        OrtSession.Result result=encoder.run(Collections.singletonMap("input_image",input))){
                        FloatBuffer out=((OnnxTensor)result.get(0)).getFloatBuffer();
                        if(out.remaining()!=SCENE)throw new IOException("Sortie de l’encodeur TripoSR invalide.");
                        scenes[i]=new float[SCENE];out.get(scenes[i]);
                        for(float f:scenes[i])if(!Float.isFinite(f))throw new IOException("Sortie TripoSR non finie.");
                    }
                    check(progress);
                    writeSceneAtomic(caches[i],scenes[i]);
                    deleteDerived(caches[i]);
                    try(Writer out=new OutputStreamWriter(new FileOutputStream(marker),java.nio.charset.StandardCharsets.UTF_8)){out.write(keys[i]);}
                }
            }
        }

        // Encoder is closed before the decoder is opened: lower peak RAM on Android.
        if(needsDecoder){
            try(OrtSession.SessionOptions options=options();
                OrtSession decoder=environment.createSession(new File(folder,ASSETS[2]).getPath(),options)){
                for(int i=0;i<4;i++)if(fields[i]==null){
                    check(progress);
                    if(scenes[i]==null)scenes[i]=readScene(caches[i]);
                    progress.update(labels[i]+" · décodage neuronal réel "+side+"³…");
                    fields[i]=decode(environment,decoder,scenes[i],side,progress);
                    scenes[i]=null;
                    File target=fieldCache(caches[i],side),part=new File(target.getPath()+".part");
                    try{
                        fields[i].write(part);check(progress);
                        if(!part.renameTo(target))throw new IOException("Cache 3D "+side+"³ non enregistré.");
                    }finally{part.delete();}
                }
            }
        }
        return fields;
    }

    private static File fieldCache(File scene,int side){return new File(scene.getPath()+".field-"+side);}
    private static void deleteDerived(File scene){for(int side:FIELD_SIDES)fieldCache(scene,side).delete();}

    private static String readKey(File file)throws IOException{
        if(file.length()>512)return "";
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),java.nio.charset.StandardCharsets.UTF_8))){
            String value=in.readLine();return value==null?"":value;
        }
    }

    private static void writeSceneAtomic(File file,float[] scene)throws IOException{
        File part=new File(file.getPath()+".part");
        try{
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(part)))){
                out.writeInt(SCENE_MAGIC);out.writeInt(SCENE);
                for(float value:scene)out.writeFloat(value);
            }
            if(!part.renameTo(file))throw new IOException("Triplanes TripoSR non enregistrés.");
        }finally{part.delete();}
    }

    private static float[] readScene(File file)throws IOException{
        if(file.length()!=8L+4L*SCENE)throw new IOException("Cache triplanes TripoSR incomplet.");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=SCENE_MAGIC||in.readInt()!=SCENE)throw new IOException("Cache triplanes TripoSR invalide.");
            float[] scene=new float[SCENE];
            for(int i=0;i<scene.length;i++){scene[i]=in.readFloat();if(!Float.isFinite(scene[i]))throw new IOException("Triplanes TripoSR non finis.");}
            return scene;
        }
    }

    private static TripoSRField decode(OrtEnvironment env,OrtSession decoder,float[] scene,int side,Progress progress)throws Exception{
        float[] density=new float[side*side*side];
        for(int start=0;start<density.length;start+=BATCH){
            check(progress);int count=Math.min(BATCH,density.length-start);float[] features=new float[count*120];
            for(int j=0;j<count;j++){
                int index=start+j;
                int ix=index/(side*side),iy=index/side%side,iz=index%side;
                float x=-1+2f*ix/(side-1),y=-1+2f*iy/(side-1),z=-1+2f*iz/(side-1);
                samplePlane(scene,0,x,y,features,j*120);
                samplePlane(scene,1,x,z,features,j*120+40);
                samplePlane(scene,2,y,z,features,j*120+80);
            }
            try(OnnxTensor input=OnnxTensor.createTensor(env,FloatBuffer.wrap(features),new long[]{1,count,120});
                OrtSession.Result result=decoder.run(Collections.singletonMap("triplane_features",input))){
                FloatBuffer out=((OnnxTensor)result.get(0)).getFloatBuffer();
                if(out.remaining()!=count*4)throw new IOException("Sortie du décodeur TripoSR invalide.");
                for(int j=0;j<count;j++){density[start+j]=out.get();out.get();out.get();out.get();}
            }
        }
        return new TripoSRField(density,side);
    }

    /** align_corners=false bilinear grid_sample with zero padding, exactly as upstream. */
    public static void samplePlane(float[] scene,int plane,float u,float v,float[] features,int offset){
        float x=(u+1)*32-.5f,y=(v+1)*32-.5f;int ix=(int)Math.floor(x),iy=(int)Math.floor(y);float fx=x-ix,fy=y-iy;
        for(int channel=0;channel<CHANNELS;channel++){
            float value=0;
            for(int a=0;a<2;a++)for(int b=0;b<2;b++){
                int xx=ix+a,yy=iy+b;if(xx<0||xx>=PLANE||yy<0||yy>=PLANE)continue;
                value+=scene[(plane*CHANNELS+channel)*PLANE*PLANE+yy*PLANE+xx]*(a==0?1-fx:fx)*(b==0?1-fy:fy);
            }
            features[offset+channel]=value;
        }
    }
}
