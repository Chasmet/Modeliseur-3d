import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS, EXTMeshoptCompression } from '@gltf-transform/extensions';
import { MeshoptEncoder, MeshoptDecoder } from 'meshoptimizer';
import validator from 'gltf-validator';
import fs from 'node:fs/promises';

export async function createIO() {
  await Promise.all([MeshoptEncoder.ready, MeshoptDecoder.ready]);
  return new NodeIO().registerExtensions(ALL_EXTENSIONS).registerDependencies({
    'meshopt.encoder': MeshoptEncoder, 'meshopt.decoder': MeshoptDecoder,
  });
}

export async function validate(bytes) {
  const report = await validator.validateBytes(bytes, { maxIssues: 100 });
  if (report.issues.numErrors) throw new Error(`GLB invalide : ${report.issues.numErrors} erreurs.`);
  return {errors: report.issues.numErrors, warnings: report.issues.numWarnings};
}

function sameArray(a, b) {
  if (a.length !== b.length) throw new Error('Longueur des données modifiée.');
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) throw new Error(`Données modifiées à ${i}.`);
}

function sameTriangles(a, b) {
  if (a.length !== b.length) throw new Error('Nombre de triangles modifié.');
  for (let i = 0; i < a.length; i += 3) {
    const x = [a[i], a[i+1], a[i+2]], y = [b[i], b[i+1], b[i+2]];
    if (![0, 1, 2].some(s => x.every((v, k) => v === y[(k+s)%3]))) {
      throw new Error('Topologie modifiée pendant la compression.');
    }
  }
}

export function verifyPreservation(before, after) {
  const a = before.getRoot(), b = after.getRoot();
  for (const collection of ['listMeshes', 'listMaterials', 'listSkins', 'listAnimations', 'listTextures', 'listNodes']) {
    if (a[collection]().length !== b[collection]().length) throw new Error(`${collection} modifié.`);
  }
  a.listTextures().forEach((texture, i) => sameArray(texture.getImage(), b.listTextures()[i].getImage()));
  a.listMeshes().forEach((mesh, i) => {
    const bp = b.listMeshes()[i].listPrimitives();
    if (mesh.listPrimitives().length !== bp.length) throw new Error('Primitives modifiées.');
    mesh.listPrimitives().forEach((primitive, j) => {
      const other = bp[j];
      if (primitive.getMode() !== other.getMode()) throw new Error('Mode modifié.');
      if (primitive.listSemantics().join() !== other.listSemantics().join()) throw new Error('Attributs modifiés.');
      primitive.listSemantics().forEach(s => sameArray(primitive.getAttribute(s).getArray(), other.getAttribute(s).getArray()));
      if (primitive.getIndices()) {
        const compare = primitive.getMode() === 4 ? sameTriangles : sameArray;
        compare(primitive.getIndices().getArray(), other.getIndices().getArray());
      }
      primitive.listTargets().forEach((target, k) => target.listSemantics().forEach(s =>
        sameArray(target.getAttribute(s).getArray(), other.listTargets()[k].getAttribute(s).getArray())));
    });
  });
  a.listAnimations().forEach((animation, i) => animation.listSamplers().forEach((sampler, j) => {
    const other = b.listAnimations()[i].listSamplers()[j];
    sameArray(sampler.getInput().getArray(), other.getInput().getArray());
    sameArray(sampler.getOutput().getArray(), other.getOutput().getArray());
  }));
  a.listSkins().forEach((skin, i) => sameArray(skin.getInverseBindMatrices().getArray(), b.listSkins()[i].getInverseBindMatrices().getArray()));
}

export async function compressVerified(document, destination) {
  const io = await createIO();
  const uncompressed = await io.writeBinary(document);
  const validation = await validate(uncompressed);
  const pristine = await io.readBinary(uncompressed);
  document.createExtension(EXTMeshoptCompression).setRequired(true)
    .setEncoderOptions({method: EXTMeshoptCompression.EncoderMethod.QUANTIZE});
  // No quantize(), simplify(), texture transcoding, or lossy Meshopt filters.
  const compressed = await io.writeBinary(document);
  const decoded = await io.readBinary(compressed);
  verifyPreservation(pristine, decoded);
  const beforeJSON=(await io.writeJSON(pristine)).json;
  const afterJSON=(await io.writeJSON(decoded)).json;
  for(const section of ['materials','textures','samplers','nodes','skins','scenes']) {
    if(JSON.stringify(beforeJSON[section])!==JSON.stringify(afterJSON[section])) {
      throw new Error(`Apparence ou hiérarchie modifiée : ${section}.`);
    }
  }
  if (compressed.length >= uncompressed.length) {
    throw new Error('La compression ne réduit pas ce fichier ; aucun export présenté comme compressé.');
  }
  const compressedValidation = await validate(compressed);
  await fs.writeFile(destination, compressed);
  return {uncompressedBytes: uncompressed.length, compressedBytes: compressed.length,
    reductionPercent: Number((100*(1-compressed.length/uncompressed.length)).toFixed(2)),
    validation, compressedValidation, exactDataPreservation: true,
    requiredDecoder: 'EXT_meshopt_compression'};
}
