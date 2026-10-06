import fs from 'node:fs/promises';
import path from 'node:path';
import { createIO, compressVerified } from './glb.mjs';
import { addHumanoid } from './humanoid.mjs';

const args=process.argv.slice(2), humanoid=args.includes('--humanoid');
const positional=args.filter(x=>x!=='--humanoid');
if(positional.length!==2) {
  console.error('Usage : node finish.mjs source.glb sortie.glb [--humanoid]');process.exit(2);
}
const [source,destination]=positional;
if(path.resolve(source)===path.resolve(destination))throw new Error('Conservez le GLB source dans un fichier distinct.');
await fs.mkdir(path.dirname(destination),{recursive:true});
const io=await createIO(),document=await io.read(source);
const rig=humanoid?addHumanoid(document):{added:false,reason:'Compression seule demandée.'};
const compression=await compressVerified(document,destination);
await fs.writeFile(destination+'.report.json',JSON.stringify({rig,compression},null,2));
console.log(JSON.stringify({rig,compression},null,2));
