import { mat4, mat3, vec3, quat } from 'gl-matrix';

export const CLIPS = {Idle:3, Walk:1.15, Run:.72, Jump:1.2, Fall:1.2, Land:.65,
  Attack:1, Defend:2, Dodge:.8, Swim:2, Climb:2, Ride:2, Drive:2, Pilot:2, Rap:3, Wave:2.4};
const ONE_SHOTS = new Set(['Jump','Land','Attack','Dodge']);

export function pose(kind, t, duration) {
  const p=t/duration, a=2*Math.PI*p, s=Math.sin(a), c=Math.cos(a), envelope=Math.sin(Math.PI*p)**2;
  const rotations={}, hip=[0,0,0];
  const set=(b,x=0,y=0,z=0)=>{ rotations[b]=[x,y,z]; };
  if(kind==='Idle') {
    set('Spine',.014*s,0,.01*s); set('Head',0,.035*s,.015*Math.sin(a+.3)); hip[1]=.004*s;
  } else if(kind==='Walk'||kind==='Run') {
    const run=kind==='Run'; hip[1]=(run?.04:.015)*(1-Math.cos(2*a));
    set('Spine',run?-.10:-.025,.05*s); set('Chest',0,-.05*s,.02*s);
    for(const [side,sign] of [['Left',1],['Right',-1]]) {
      const q=a+(sign===1?0:Math.PI), v=Math.sin(q);
      set(side+'UpLeg',(run?.60:.32)*v,0,sign*.02);
      set(side+'Leg',-(run?.85:.50)*Math.max(0,-v)-.04);
      set(side+'Foot',.12*v); set(side+'UpperArm',-(run?.52:.29)*v,0,sign*.04);
      set(side+'ForeArm',run?-.95:-.14-.13*Math.max(0,v));
    }
  } else if(kind==='Jump'||kind==='Land') {
    const k=envelope; hip[1]=kind==='Jump'?.35*Math.sin(Math.PI*p):-.13*k;
    set('Spine',-.13*k);
    for(const side of ['Left','Right']) {
      set(side+'UpLeg',.35*k); set(side+'Leg',-.70*k); set(side+'Foot',.15*k);
      set(side+'UpperArm',-.65*k); set(side+'ForeArm',-.25*k);
    }
  } else if(kind==='Fall') {
    set('Spine',-.08); set('Head',.08);
    for(const [side,sign] of [['Left',1],['Right',-1]]) {
      set(side+'UpperArm',-.12+.04*s,0,.6*sign); set(side+'ForeArm',-.35);
      set(side+'UpLeg',.15+.03*sign*s); set(side+'Leg',-.3);
    }
  } else if(kind==='Attack') {
    set('Chest',0,-.22*envelope); set('RightUpperArm',-1.35*envelope,0,-.08*envelope);
    set('RightForeArm',-.75*Math.sin(2*Math.PI*p)**2); set('LeftForeArm',-.65*envelope);
  } else if(kind==='Defend') {
    set('Spine',-.06); set('Head',.1);
    for(const [side,sign] of [['Left',1],['Right',-1]]) {
      set(side+'UpperArm',-.5,0,.08*sign); set(side+'ForeArm',-1.25); set(side+'Hand',-.08);
    }
  } else if(kind==='Dodge') {
    hip[0]=.25*envelope; hip[1]=-.12*envelope; set('Spine',0,0,-.3*envelope);
    for(const side of ['Left','Right']) {set(side+'UpLeg',.25*envelope);set(side+'Leg',-.5*envelope);}
  } else if(kind==='Swim') {
    set('Hips',Math.PI/2); set('Head',-.15);
    for(const [side,sign] of [['Left',1],['Right',-1]]) {
      set(side+'UpperArm',-1.4+.65*sign*s,0,.20*sign);
      set(side+'ForeArm',-.3-.2*(1+sign*c)); set(side+'UpLeg',.12*sign*s); set(side+'Leg',-.18);
    }
  } else if(kind==='Climb') {
    set('Spine',-.08);
    for(const [side,sign] of [['Left',1],['Right',-1]]) {
      set(side+'UpperArm',-2.1+.4*sign*s);set(side+'ForeArm',-.45-.3*sign*s);
      set(side+'UpLeg',.55+.3*sign*s);set(side+'Leg',-1-.3*sign*s);
    }
  } else if(['Ride','Drive','Pilot'].includes(kind)) {
    hip[1]=-.2; set('Spine',-.06+.015*s);
    for(const [side,sign] of [['Left',1],['Right',-1]]) {
      set(side+'UpLeg',1.15,0,sign*(kind==='Ride'?.24:.06));set(side+'Leg',-1.35);set(side+'Foot',.20);
      set(side+'UpperArm',-.7,0,.08*sign);set(side+'ForeArm',-.65);set(side+'Hand',0,.04*sign*s);
    }
  } else if(kind==='Rap') {
    hip[0]=.015*s; hip[1]=.012*Math.sin(2*a);set('Spine',.025*Math.sin(2*a),.065*s,.035*s);
    set('Head',.055*Math.sin(2*a),-.1*s);
    set('LeftUpperArm',-.55+.25*s,.12,.22+.12*s);set('LeftForeArm',-.75-.22*Math.sin(2*a),.06,.12);
    set('RightUpperArm',-.32-.22*s,-.06,-.16);set('RightForeArm',-.52-.25*Math.sin(2*a+.8),-.06,-.06);
  } else if(kind==='Wave') {
    set('LeftUpperArm',0,0,1.9);set('LeftForeArm',0,0,.3+.2*Math.sin(3*a));
    set('LeftHand',.1*Math.sin(3*a),0,.25*Math.sin(3*a));set('Head',0,.08*s,-.06);
  } else throw new Error(`Animation inconnue : ${kind}`);
  return {rotations, hip};
}

export function addHumanoid(document) {
  const root=document.getRoot();
  if(root.listSkins().length) return {added:false, reason:'Squelette existant conservé.'};
  if(root.listAnimations().length) throw new Error('Animation sans squelette existante : rig automatique refusé pour préserver ses mouvements.');
  if(root.listScenes().length!==1) throw new Error('Le rig automatique exige une scène unique.');
  const meshNodes=root.listNodes().filter(n=>n.getMesh());
  const sourceMeshes=new Set(meshNodes.map(node=>node.getMesh()));
  const sourcePrimitives=new Set([...sourceMeshes].flatMap(mesh=>mesh.listPrimitives()));
  if(!meshNodes.length) throw new Error('Aucun maillage.');
  if(meshNodes.some(n=>n.listChildren().length)) throw new Error('Le rig automatique exige des nœuds de maillage sans enfants.');
  const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];
  const baked=[];
  for(const node of meshNodes) {
    const world=node.getWorldMatrix(), normalMatrix=mat3.normalFromMat4(mat3.create(),world);
    if(!normalMatrix) throw new Error('Transformation non inversible.');
    const primitives=[];
    for(const primitive of node.getMesh().listPrimitives()) {
      if(primitive.getMode()!==4)throw new Error('Le rig exige un maillage triangulé.');
      if(mat4.determinant(world)<=0)throw new Error('Transformation inversée : appliquer les transformations dans le logiciel 3D avant rigging.');
      if(primitive.listTargets().length) throw new Error('Morph targets existants : rig automatique refusé.');
      const position=primitive.getAttribute('POSITION');
      const points=new Float32Array(position.getArray().length),normals=primitive.getAttribute('NORMAL');
      const transformedNormals=normals?new Float32Array(normals.getArray().length):null;
      for(let i=0;i<position.getCount();i++) {
        const v=vec3.transformMat4(vec3.create(),position.getElement(i,[]),world);points.set(v,i*3);
        for(let k=0;k<3;k++){min[k]=Math.min(min[k],v[k]);max[k]=Math.max(max[k],v[k]);}
        if(normals) {
          const n=vec3.transformMat3(vec3.create(),normals.getElement(i,[]),normalMatrix);vec3.normalize(n,n);transformedNormals.set(n,i*3);
        }
      }
      primitives.push({primitive,points,transformedNormals});
    }
    baked.push({node,primitives});
  }
  const height=max[1]-min[1],width=max[0]-min[0],depth=max[2]-min[2];
  if(!Number.isFinite(height)||height<=0||height/width<1.25||height/width>5||depth>height*.65) {
    throw new Error('Silhouette incompatible : utilisez un humanoïde entier, debout, axe Y vertical, bras abaissés ou légèrement écartés.');
  }
  const scale=height/1.8,center=[(min[0]+max[0])/2,min[1],(min[2]+max[2])/2];
  const definitions=[['Hips',null,[0,.99,0]],['Spine','Hips',[0,1.12,0]],['Chest','Spine',[0,1.39,0]],
    ['Neck','Chest',[0,1.54,0]],['Head','Neck',[0,1.65,0]]];
  for(const [side,s] of [['Left',1],['Right',-1]]) definitions.push(
    [side+'Shoulder','Chest',[s*.21,1.43,0]],[side+'UpperArm',side+'Shoulder',[s*.255,1.425,0]],
    [side+'ForeArm',side+'UpperArm',[s*.315,1.13,0]],[side+'Hand',side+'ForeArm',[s*.326,.895,.016]],
    [side+'UpLeg','Hips',[s*.135,.985,0]],[side+'Leg',side+'UpLeg',[s*.18,.605,.005]],
    [side+'Foot',side+'Leg',[s*.19,.17,.015]],[side+'ToeBase',side+'Foot',[s*.19,.062,.155]]);
  const buffer=root.listBuffers()[0]||document.createBuffer();
  const nodes=new Map(),positions=new Map(),indices=new Map();
  definitions.forEach(([name,parent,position],i)=>{
    const point=position.map((x,k)=>center[k]+x*scale), node=document.createNode(name);
    positions.set(name,point);indices.set(name,i);nodes.set(name,node);
    if(parent){node.setTranslation(point.map((v,k)=>v-positions.get(parent)[k]));nodes.get(parent).addChild(node);}
    else node.setTranslation(point);
  });
  const inverse=new Float32Array(definitions.length*16);
  definitions.forEach(([name],i)=>{
    const matrix=mat4.fromTranslation(mat4.create(),positions.get(name).map(v=>-v));inverse.set(matrix,i*16);
  });
  const skin=document.createSkin('Humanoïde procédural').setSkeleton(nodes.get('Hips'))
    .setInverseBindMatrices(document.createAccessor().setType('MAT4').setArray(inverse).setBuffer(buffer));
  definitions.forEach(([name])=>skin.addJoint(nodes.get(name)));
  root.listScenes()[0].addChild(nodes.get('Hips'));

  const blends=(y,anchors)=>{
    if(y<=anchors[0][0])return [[anchors[0][1],1]];
    for(let i=1;i<anchors.length;i++)if(y<=anchors[i][0]) {
      let t=(y-anchors[i-1][0])/(anchors[i][0]-anchors[i-1][0]);t=t*t*(3-2*t);
      return [[anchors[i-1][1],1-t],[anchors[i][1],t]];
    }
    return [[anchors.at(-1)[1],1]];
  };
  for(const {node,primitives} of baked) {
    const mesh=document.createMesh(node.getMesh().getName());
    for(const {primitive,points,transformedNormals} of primitives) {
      const p=primitive.clone(),joints=new Uint16Array(points.length/3*4),weights=new Float32Array(joints.length);
      for(let i=0;i<points.length/3;i++) {
        const x=(points[i*3]-center[0])/scale,y=(points[i*3+1]-center[1])/scale;
        const side=x>=0?'Left':'Right';let influences;
        if(y<.99) {
          // Arms extend below the waist. Keep hands out of leg weighting.
          influences=Math.abs(x)>.275&&y>.70?[[side+'Hand',1]]:
            blends(y,[[.16,side+'Foot'],[.60,side+'Leg'],[.985,side+'UpLeg']]);
        } else if(Math.abs(x)>.225&&y<1.51) {
          influences=blends(y,[[.90,side+'Hand'],[1.13,side+'ForeArm'],[1.425,side+'UpperArm'],[1.49,side+'Shoulder']]);
        } else influences=blends(y,[[.99,'Hips'],[1.16,'Spine'],[1.40,'Chest'],[1.54,'Neck'],[1.63,'Head']]);
        influences.forEach(([name,w],k)=>{joints[i*4+k]=indices.get(name);weights[i*4+k]=w;});
      }
      p.setAttribute('POSITION',document.createAccessor().setType('VEC3').setArray(points).setBuffer(buffer));
      if(transformedNormals)p.setAttribute('NORMAL',document.createAccessor().setType('VEC3').setArray(transformedNormals).setBuffer(buffer));
      p.setAttribute('JOINTS_0',document.createAccessor().setType('VEC4').setArray(joints).setBuffer(buffer));
      p.setAttribute('WEIGHTS_0',document.createAccessor().setType('VEC4').setArray(weights).setBuffer(buffer));
      mesh.addPrimitive(p);
    }
    node.getParentNode()?.removeChild(node);
    node.setMatrix(mat4.create()).setMesh(mesh).setSkin(skin);root.listScenes()[0].addChild(node);
  }
  // Remove the detached unskinned copies without touching shared textures/materials.
  for(const mesh of sourceMeshes)mesh.dispose();
  for(const primitive of sourcePrimitives)primitive.dispose();
  for(const accessor of root.listAccessors()) {
    if(accessor.listParents().every(parent=>parent.propertyType==='Root'))accessor.dispose();
  }
  for(const [kind,duration] of Object.entries(CLIPS)) {
    const count=Math.round(duration*30)+1,times=Float32Array.from({length:count},(_,i)=>i*duration/(count-1));
    const frames=Array.from(times,t=>pose(kind,t,duration));
    if(!ONE_SHOTS.has(kind))frames[count-1]=frames[0];
    const animation=document.createAnimation(kind).setExtras({loop:!ONE_SHOTS.has(kind),procedural:true,inPlace:kind!=='Dodge'});
    const input=document.createAccessor().setType('SCALAR').setArray(times).setBuffer(buffer);
    const names=new Set(frames.flatMap(f=>Object.keys(f.rotations)));
    for(const name of names) {
      const values=new Float32Array(count*4);
      frames.forEach((frame,i)=>{
        const e=frame.rotations[name]||[0,0,0],q=quat.fromEuler(quat.create(),...e.map(v=>v*180/Math.PI),'xyz');values.set(q,i*4);
      });
      const output=document.createAccessor().setType('VEC4').setArray(values).setBuffer(buffer);
      const sampler=document.createAnimationSampler().setInput(input).setOutput(output).setInterpolation('LINEAR');
      animation.addSampler(sampler).addChannel(document.createAnimationChannel().setSampler(sampler).setTargetNode(nodes.get(name)).setTargetPath('rotation'));
    }
    const translation=new Float32Array(count*3);
    frames.forEach((f,i)=>translation.set(positions.get('Hips').map((v,k)=>v+f.hip[k]*scale),i*3));
    const output=document.createAccessor().setType('VEC3').setArray(translation).setBuffer(buffer);
    const sampler=document.createAnimationSampler().setInput(input).setOutput(output).setInterpolation('LINEAR');
    animation.addSampler(sampler).addChannel(document.createAnimationChannel().setSampler(sampler).setTargetNode(nodes.get('Hips')).setTargetPath('translation'));
  }
  return {added:true,joints:definitions.length,animations:Object.keys(CLIPS),
    limitation:'Rig et mouvements procéduraux approximatifs ; contrôle visuel requis pour chaque personnage.'};
}
