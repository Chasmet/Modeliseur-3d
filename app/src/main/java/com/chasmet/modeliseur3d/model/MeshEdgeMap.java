package com.chasmet.modeliseur3d.model;

/** Primitive edge table. Zero is empty; an edge between distinct nonnegative indices is nonzero. */
final class MeshEdgeMap {
    private long[] keys=new long[1024];private int[] values=new int[1024];private int size;
    private static int hash(long key){key^=key>>>33;key*=0xff51afd7ed558ccdL;key^=key>>>33;return (int)key;}
    int get(long key){int mask=keys.length-1,at=hash(key)&mask;while(keys[at]!=0){if(keys[at]==key)return values[at];at=(at+1)&mask;}return -1;}
    int putIfAbsent(long key,int value){
        if(key==0||value<0)throw new IllegalArgumentException("Arête invalide.");
        int mask=keys.length-1,at=hash(key)&mask;
        while(keys[at]!=0){if(keys[at]==key)return values[at];at=(at+1)&mask;}
        if((size+1)*10>keys.length*7){grow();return putIfAbsent(key,value);}
        keys[at]=key;values[at]=value;size++;return -1;
    }
    private void grow(){long[] oldKeys=keys;int[] oldValues=values;keys=new long[oldKeys.length*2];values=new int[keys.length];size=0;for(int i=0;i<oldKeys.length;i++)if(oldKeys[i]!=0)putIfAbsent(oldKeys[i],oldValues[i]);}
    /** Storage iteration, skipping zero slots, avoids allocating a boxed key collection. */
    long[] keys(){return keys;}
}
