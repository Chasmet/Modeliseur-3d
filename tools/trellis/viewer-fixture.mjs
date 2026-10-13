// Synthetic animated fixture for the Android viewer's browser smoke test.
import fs from 'node:fs/promises';
import {Document} from '@gltf-transform/core';
import {createIO} from './glb.mjs';
import {addHumanoid} from './humanoid.mjs';
const doc=new Document(), buffer=doc.createBuffer(), scene=doc.createScene();
const positions=[], indices=[];
for(let y=0;y<=20;y++)for(let k=0;k<=16;k++) {
  const a=k/16*2*Math.PI; positions.push(.25*Math.cos(a),y/20*1.8,.15*Math.sin(a));
}
for(let y=0;y<20;y++)for(let k=0;k<16;k++) {
  const a=y*17+k,b=a+17; indices.push(a,b,a+1,a+1,b,b+1);
}
const primitive=doc.createPrimitive()
  .setAttribute('POSITION',doc.createAccessor().setType('VEC3').setArray(new Float32Array(positions)).setBuffer(buffer))
  .setIndices(doc.createAccessor().setType('SCALAR').setArray(new Uint16Array(indices)).setBuffer(buffer))
  .setMaterial(doc.createMaterial().setBaseColorFactor([.6,.15,.9,1]));
scene.addChild(doc.createNode().setMesh(doc.createMesh().addPrimitive(primitive)));
addHumanoid(doc);
const folder=process.argv[2]; await fs.mkdir(folder,{recursive:true});
await (await createIO()).write(folder+'/00000000000000000000000000000000.glb',doc);
