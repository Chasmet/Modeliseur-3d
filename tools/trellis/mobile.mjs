// Standard GLB for Android viewers: no required Meshopt decoder.
import fs from 'node:fs/promises';
import {createIO, validate} from './glb.mjs';
import {addHumanoid} from './humanoid.mjs';
const [source, target, rig] = process.argv.slice(2);
const io = await createIO(), doc = await io.read(source);
let animation = {added: false};
if (rig === 'humanoid') {
  try { animation = addHumanoid(doc); }
  catch { animation = {added: false, warning: 'Rig automatique incompatible : géométrie conservée sans animation.'}; }
}
const bytes = await io.writeBinary(doc);
await validate(bytes);
if (bytes.length > 64 * 1024 * 1024) throw new Error('GLB trop volumineux pour le profil mobile.');
await fs.writeFile(target, bytes);
console.log(JSON.stringify(animation));
