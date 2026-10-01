import * as THREE from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';

const status = document.querySelector('#status'), select = document.querySelector('#clips');
try {
  const scene = new THREE.Scene(); scene.background = new THREE.Color(0x101725);
  const camera = new THREE.PerspectiveCamera(40, innerWidth / innerHeight, 0.01, 1000);
  const renderer = new THREE.WebGLRenderer({antialias: false, powerPreference: 'low-power'});
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5)); renderer.setSize(innerWidth, innerHeight);
  document.body.appendChild(renderer.domElement);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x657189, 2));
  const light = new THREE.DirectionalLight(0xffffff, 3); light.position.set(3,5,4); scene.add(light);
  const controls = new OrbitControls(camera, renderer.domElement); controls.enableDamping = true;
  let mixer, action, clips = [], running = true, last = 0;
  window.viewerActive = active => { running = active; };
  const id = new URLSearchParams(location.search).get('id');
  if (!/^[a-f0-9]{32}$/.test(id)) throw new Error('Identifiant GLB invalide.');
  new GLTFLoader().load('/model/' + id + '.glb', gltf => {
    scene.add(gltf.scene);
    const box = new THREE.Box3().setFromObject(gltf.scene), size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3()), span = Math.max(size.x,size.y,size.z,0.1);
    controls.target.copy(center); camera.position.copy(center).add(new THREE.Vector3(span*1.2,span*0.7,span*2));
    camera.near = span/1000; camera.far = span*100; camera.updateProjectionMatrix(); controls.update();
    clips = gltf.animations; mixer = new THREE.AnimationMixer(gltf.scene);
    for (const clip of clips) { const option = document.createElement('option'); option.textContent = clip.name; select.appendChild(option); }
    status.textContent = clips.length ? 'Rotation : un doigt • zoom : deux doigts' : 'Sans animation • rotation et zoom tactiles';
    document.querySelector('#pause').disabled = !clips.length;
  }, undefined, () => { status.textContent = 'Ce GLB ne peut pas être chargé. Tu peux toujours l’exporter.'; });
  select.onchange = () => {
    mixer?.stopAllAction(); const clip = clips.find(c => c.name === select.value);
    action = clip ? mixer.clipAction(clip).reset().play() : null;
    if (action && /^(Jump|Land|Attack|Dodge)$/.test(clip.name)) { action.setLoop(THREE.LoopOnce,1); action.clampWhenFinished=true; }
  };
  document.querySelector('#pause').onclick = () => { if (action) action.paused = !action.paused; };
  addEventListener('resize', () => { camera.aspect=innerWidth/innerHeight; camera.updateProjectionMatrix(); renderer.setSize(innerWidth,innerHeight); });
  function frame(now) {
    requestAnimationFrame(frame);
    if (!running) { last = now; return; }
    if (now-last < 1000/30) return;
    mixer?.update(Math.min((now-last)/1000,0.1)); last=now;
    controls.update(); renderer.render(scene,camera);
  }
  requestAnimationFrame(frame);
} catch { status.textContent = 'Lecteur WebGL indisponible. Exporte le GLB pour l’ouvrir avec un autre lecteur.'; }
