import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Document } from '@gltf-transform/core';
import { createIO, compressVerified } from '../glb.mjs';
import { addHumanoid, pose, CLIPS } from '../humanoid.mjs';

function fixture() {
  const doc=new Document(),buffer=doc.createBuffer(), scene=doc.createScene();
  const positions=[],indices=[];
  // Closed, slender vertical mesh, with enough data for a meaningful compression test.
  for(let y=0;y<=80;y++) for(let k=0;k<=32;k++) {
    const a=k/32*2*Math.PI;positions.push(.25*Math.cos(a),y/80*1.8,.15*Math.sin(a));
  }
  for(let y=0;y<80;y++)for(let k=0;k<32;k++) {
    const a=y*33+k,b=a+33;indices.push(a,b,a+1,a+1,b,b+1);
  }
  const material=doc.createMaterial('Tissu').setBaseColorFactor([.12,.02,.3,1]).setMetallicFactor(.1).setRoughnessFactor(.7);
  const primitive=doc.createPrimitive()
    .setAttribute('POSITION',doc.createAccessor().setType('VEC3').setArray(new Float32Array(positions)).setBuffer(buffer))
    .setIndices(doc.createAccessor().setType('SCALAR').setArray(new Uint16Array(indices)).setBuffer(buffer)).setMaterial(material);
  const mesh=doc.createMesh().addPrimitive(primitive);
  const node=doc.createNode().setMesh(mesh).setTranslation([2,3,-1]);scene.addChild(node);
  return doc;
}

test('rig préserve les positions mondiales et ajoute les 16 clips avec poids normalisés',()=>{
  const doc=fixture(),source=doc.getRoot().listMeshes()[0].listPrimitives()[0].getAttribute('POSITION').getArray().slice();
  const report=addHumanoid(doc);assert.equal(report.joints,21);assert.equal(doc.getRoot().listAnimations().length,16);
  const primitive=doc.getRoot().listNodes().find(n=>n.getMesh()).getMesh().listPrimitives()[0];
  const positions=primitive.getAttribute('POSITION').getArray();
  for(let i=0;i<source.length;i++)assert.ok(Math.abs(positions[i]-source[i]-[2,3,-1][i%3])<1e-6);
  const weights=primitive.getAttribute('WEIGHTS_0').getArray();
  for(let i=0;i<weights.length;i+=4)assert.ok(Math.abs(weights.slice(i,i+4).reduce((s,x)=>s+x,0)-1)<1e-6);
  for(const animation of doc.getRoot().listAnimations())if(animation.getExtras().loop) {
    for(const sampler of animation.listSamplers()) {
      const output=sampler.getOutput();assert.deepEqual(output.getElement(0,[]),output.getElement(output.getCount()-1,[]));
    }
  }
});

test('squelette existant conservé, objets non humanoïdes refusés',()=>{
  const doc=fixture();addHumanoid(doc);const before=doc.getRoot().listAnimations().length;
  assert.equal(addHumanoid(doc).added,false);assert.equal(doc.getRoot().listAnimations().length,before);
  const object=fixture();object.getRoot().listNodes()[0].setScale([10,1,1]);
  assert.throws(()=>addHumanoid(object),/Silhouette incompatible/);
});

test('tous les clips produisent des transformations finies',()=>{
  for(const [name,duration] of Object.entries(CLIPS))for(let i=0;i<=30;i++) {
    const frame=pose(name,duration*i/30,duration);
    assert.ok([...frame.hip,...Object.values(frame.rotations).flat()].every(Number.isFinite));
  }
});

test('compression réduit le GLB et préserve exactement géométrie, peau et animations',async()=>{
  const temp=await fs.mkdtemp(path.join(os.tmpdir(),'modeliseur-glb-'));
  try {
    const doc=fixture();addHumanoid(doc);
    const report=await compressVerified(doc,path.join(temp,'test.glb'));
    assert.ok(report.compressedBytes<report.uncompressedBytes);assert.equal(report.exactDataPreservation,true);
    const decoded=await (await createIO()).read(path.join(temp,'test.glb'));
    assert.equal(decoded.getRoot().listSkins().length,1);assert.equal(decoded.getRoot().listAnimations().length,16);
    assert.equal(report.validation.errors,0);assert.equal(report.compressedValidation.errors,0);
  } finally {await fs.rm(temp,{recursive:true,force:true});}
});
