'use strict';
const token = document.querySelector('meta[name="jade-session"]').content;
let state;
let busy = false;
let renderedExpiry = '';
const $ = id => document.getElementById(id);
const el = (tag, text, cls) => {const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (cls) n.className = cls; return n;};
const label = {OFFLINE_ARTIFACT_RECHECK:'Recontrôler les preuves locales', OFFICIAL_SOURCE_COLLECTION:'Recapturer les documentations officielles', BLENDER_SYNTHETIC_FBX_ROUNDTRIP:'Refaire le test Blender sur un modèle synthétique'};
const kinds = {GRANTED:'Autorisation accordée', REVOKED:'Autorisation révoquée', STARTED:'Exécution commencée', COMPLETED:'Exécution terminée', FAILED:'Exécution en échec', INSPECTION_RECORDED:'Relecture humaine consignée'};
const date = ms => new Date(ms).toLocaleString('fr-FR');
const evaluation = {LOCAL_CHECKS_PASSED_NOT_INDEPENDENTLY_ATTESTED:'Contrôles locaux réussis, sans attestation indépendante',UNASSESSED_DOCUMENT_CAPTURE:'Documentation capturée, à relire',COLLECTION_PARTIAL_FAILURE:'Collecte partielle : certaines sources ont échoué'};
function errorText(e){return e.message.includes('reviewer and reason') ? 'Renseignez votre nom, un motif et confirmez le périmètre de cet essai.' : e.message;}
async function request(path, body) {
  const response = await fetch(path, {method:body ? 'POST':'GET', cache:'no-store', headers:{'X-Jade-Review':token, ...(body ? {'Content-Type':'application/json'}:{})}, ...(body ? {body:JSON.stringify(body)}:{})});
  const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Action refusée'); return value;
}
function identity(){return {reviewer:$('reviewer').value.trim(), note:$('note').value.trim()};}
async function showResult(id, target){
  try{
    const payload=await request('/api/result/'+encodeURIComponent(id));
    const result=payload.result;target.replaceChildren(el('p','Résultat conservé localement · empreinte du fichier contrôlée · connaissance toujours non vérifiée.'));
    if(result.sources){result.sources.forEach(s=>{const card=el('div',undefined,'card');card.append(el('strong',s.source_key),el('p',s.source_url,'hash'));s.excerpts.forEach(x=>card.append(el('blockquote',x.excerpt)));target.append(card);});if(result.errors && result.errors.length)target.append(el('p','Certaines sources n’ont pas pu être récupérées. Consultez les détails.'));}
    if(result.experiment)target.append(el('p',`Essai Blender synthétique · ${result.experiment.mesh_count} maillages · contrôles locaux réussis · aucune attestation indépendante.`));
    if(result.scope==='JADE_SELF_IMPROVEMENT')target.append(el('p',`${result.hypothesis_count} hypothèses et ${result.next_experiments.length} propositions de Jade recontrôlées, sans lancement d'essai ni promotion.`));
    else if(result.experiment_artifacts)target.append(el('p',`${result.source_references.length} sources et ${result.experiment_artifacts.length} artefacts recontrôlés, sans promotion de connaissance.`));
    const details=el('details');details.append(el('summary','Détails du rapport'),el('pre',JSON.stringify(result,null,2)));target.append(details);
  }catch(e){target.replaceChildren(el('p','Résultat indisponible : '+errorText(e)));}
}
async function action(path, body){
  if (busy) return; busy=true;
  document.querySelectorAll('button').forEach(b=>b.disabled=true);
  $('error').hidden=true; $('status').textContent='Action en cours…';
  try {const result=await request('/api/'+path,body); await refresh(); $('status').textContent=path==='execute' ? 'Exécution terminée. Rapport local : '+result.output_directory : path==='import-learning' ? 'Compte rendu de Jade importé. Aucun essai autorisé.' : 'Décision enregistrée dans le journal local.';}
  catch(e){$('error').textContent=errorText(e);$('error').hidden=false;try{await refresh();}catch(_){state=undefined;} $('status').textContent='Action refusée ou interrompue. Consultez le journal avant un nouvel essai.';}
  finally{busy=false; render();}
}
function render(){
  $('refresh').disabled=busy;
  $('export-mobile').disabled=busy || !state || state.ready===false;
  $('import-learning').disabled=busy || !state || state.scope!=='JADE_SELF_IMPROVEMENT';
  if(!state){document.querySelectorAll('button:not(#refresh)').forEach(b=>b.disabled=true);return;}
  renderedExpiry=expiryState();
  const generic=state.scope==='JADE_SELF_IMPROVEMENT';
  const summary=$('summary');summary.replaceChildren(el('span','EN ATTENTE DE REVUE','badge'),el('h2',state.goal));
  summary.append(el('p',state.ready===false?'Aucun compte rendu importé. Aucune proposition inventée et aucun essai autorisé.':`${state.sources.length} éléments à relire · ${state.packet.next_experiments.length} essais proposés · aucune connaissance déclarée vérifiée.`));
  summary.append(el('p','Les observations déclarées, les hypothèses et les résultats doivent être examinés avant de conclure à une amélioration de Jade.'));
  summary.append(el('p','Capture : '+(state.captured_at_ms ? date(state.captured_at_ms):'date non fournie')),el('p','Dossier : '+state.snapshot_sha256,'hash'));
  const sources=$('source-list');sources.replaceChildren();
  state.sources.forEach(s=>{
    const card=el('article',undefined,'card');card.append(el('h3',s.title),el('span','ÉLÉMENT À RELIRE','badge'));
    if(s.url){const link=el('a',s.url);link.href=s.url;link.target='_blank';link.rel='noreferrer noopener';const linkRow=el('p');linkRow.append(link);card.append(linkRow);}
    else card.append(el('p','Compte rendu local de Jade. Les observations sont déclarées par le rapport, sans attestation indépendante.'));
    card.append(el('p','Document SHA-256 : '+s.sha256,'hash'));
    s.excerpts.forEach(x=>{card.append(el('blockquote',x.text));card.append(el('p',`Bloc ${x.block_index} · ${x.excerpt_sha256}`,'hash'));if(s.caveat_excerpt_hashes.includes(x.excerpt_sha256))card.append(el('span','Restriction possible à examiner','badge'));});
    const select=el('select');[['UNCERTAIN','Incertain / à approfondir'],['SUPPORTS','Soutient l’hypothèse selon ma lecture'],['CONTRADICTS','Contredit l’hypothèse selon ma lecture']].forEach(([v,t])=>{const o=el('option',t);o.value=v;select.append(o);});select.setAttribute('aria-label','Lecture de '+s.title);
    const button=el('button','Consigner ma lecture','secondary');button.onclick=()=>action('inspect',{...identity(),snapshot_sha256:state.snapshot_sha256, source_sha256:s.sha256,stance:select.value});card.append(select,button,el('p','Utilise le nom et le motif saisis ci-dessous. Cette lecture ne valide pas automatiquement la connaissance.'));sources.append(card);
  });
  const proofs=$('proof-list');proofs.replaceChildren();
  if(!state.packet.experiment_artifacts.length)proofs.append(el('p','Aucun résultat d’essai attesté dans ce dossier. Les propositions restent à tester.'));
  state.packet.experiment_artifacts.forEach((p,i)=>{const card=el('article',undefined,'card');card.append(el('h3','Fichier de preuve '+(i+1)),el('span','CONTRÔLES LOCAUX · NON ATTESTÉ','badge'),el('p',`${p.blender_version} · ${date(p.executed_at_ms)}`),el('p','Le contrôle du fichier ne prouve pas une amélioration générale de Jade.'),el('p','Fichier : '+p.fbx_sha256,'hash'),el('p','Rapport : '+p.report_sha256,'hash'));proofs.append(card);});
  const plans=$('plan-list');plans.replaceChildren();
  state.plans.forEach(item=>{
    const p=item.plan;const card=el('article',undefined,'card');card.append(el('h3',label[p.action_id]));
    const effects={OFFLINE_ARTIFACT_RECHECK:'Relit les captures et les FBX existants. Produit un nouveau rapport dans un dossier séparé. Aucun lancement de Blender.',OFFICIAL_SOURCE_COLLECTION:'Télécharge uniquement les pages HTTPS du catalogue affiché. L’objectif reste sur ce PC. Écrit une nouvelle capture séparée.',BLENDER_SYNTHETIC_FBX_ROUNDTRIP:'Lance Blender en arrière-plan avec une scène vide. Crée uniquement un tronc et un feuillage synthétiques, puis exporte et réimporte leur FBX. Aucun projet personnel ouvert. Ce processus ne possède pas de sandbox système.'};
    card.append(el('p',generic?'Relit le compte rendu, ses hypothèses et ses propositions. Écrit un rapport séparé. Ce contrôle ne lance aucun essai d’amélioration.':effects[p.action_id]));
    if(p.urls)p.urls.forEach(url=>card.append(el('p',url,'hash')));
    card.append(el('p','Sorties : '+p.output_root+' / nouveau dossier unique','hash'));
    const details=el('details');details.append(el('summary','Plan exact et empreinte'),el('pre',JSON.stringify(p,null,2)),el('p',item.plan_sha256,'hash'));card.append(details);
    const grants=state.events.filter(e=>e.kind==='GRANTED' && e.plan_sha256===item.plan_sha256);
    const live=grants.filter(g=>state.events.filter(e=>e.authorization_id===g.authorization_id).at(-1).kind==='GRANTED' && g.expires_at_ms>Date.now()).at(-1);
    if(live){card.append(el('p','Autorisé par '+live.reviewer+' jusqu’au '+date(live.expires_at_ms),'badge safe'));const row=el('div',undefined,'row');const run=el('button','Lancer cet essai');run.onclick=()=>action('execute',{action_id:p.action_id,plan_sha256:item.plan_sha256,snapshot_sha256:state.snapshot_sha256,authorization_id:live.authorization_id});const revoke=el('button','Révoquer','secondary');revoke.onclick=()=>action('revoke',{authorization_id:live.authorization_id});row.append(run,revoke);card.append(row);}
    else {const confirm=el('input');confirm.type='checkbox';const checkLabel=el('label');checkLabel.append(confirm,document.createTextNode('J’ai examiné ce plan et j’autorise cet essai dans la copie isolée.'));const approve=el('button','Autoriser pour 15 minutes');approve.disabled=true;confirm.onchange=()=>approve.disabled=!confirm.checked;approve.onclick=()=>action('authorize',{...identity(),confirmed:confirm.checked,action_id:p.action_id,plan_sha256:item.plan_sha256,snapshot_sha256:state.snapshot_sha256});card.append(checkLabel,approve);}
    plans.append(card);
  });
  $('blocked').replaceChildren();state.packet.next_experiments.forEach(step=>{const card=el('div',undefined,'card');card.append(el('h3',step.title || step.question || step.id),el('span','PROPOSÉ · NON EXÉCUTÉ','badge'));
    if(step.target)card.append(el('p','Cible : '+step.target+' · mesure : '+step.primary_metric),el('p',`${step.minimum_samples_per_side} à ${step.maximum_samples_per_side} scénarios par variante, avec référence conservée.`),el('p','Critère : '+step.success_criterion));
    card.append(el('p','Un plan d’essai précis et un environnement isolé restent à préparer. Cette proposition n’accorde aucune autorisation.'));$('blocked').append(card);});
  const events=$('event-list');events.replaceChildren();
  if(!state.events.length)events.append(el('p','Aucune décision enregistrée. Aucun essai lancé depuis cette interface.'));
  const latest=new Map();state.events.forEach(e=>{if(e.authorization_id)latest.set(e.authorization_id,e.kind);});
  state.events.slice().reverse().forEach(e=>{
    const row=el('article',undefined,'event');row.append(el('strong',`${e.sequence} · ${kinds[e.kind]}`),el('p',date(e.at_ms)+(e.reviewer ? ' · '+e.reviewer:'')));
    if(e.note)row.append(el('p',e.note));if(e.plan)row.append(el('p',label[e.plan.action_id]));if(e.stance)row.append(el('p','Lecture déclarée : '+e.stance+' · connaissance toujours non vérifiée'));
    if(e.kind==='GRANTED' && latest.get(e.authorization_id)==='GRANTED'){row.append(el('p',e.expires_at_ms<=Date.now()?'EXPIRÉE':'EN ATTENTE'));const revoke=el('button','Révoquer cette autorisation','secondary');revoke.onclick=()=>action('revoke',{authorization_id:e.authorization_id});row.append(revoke);}
    if(e.kind==='STARTED' && latest.get(e.authorization_id)==='STARTED')row.append(el('p','Résultat inconnu : exécution en cours ou arrêt du processus. Autorisation déjà consommée.'));
    if(e.error_type)row.append(el('p','Échec : '+e.error_type));
    if(e.result_summary){const r=e.result_summary;if(r.output_directory){row.append(el('p','Rapport : '+r.output_directory,'hash'));const preview=el('div');const view=el('button','Voir le résultat','secondary');view.onclick=()=>showResult(e.authorization_id,preview);row.append(view,preview);}if(r.evaluation_status)row.append(el('p','Évaluation : '+(evaluation[r.evaluation_status] || 'Résultat à examiner')));if(r.result_file_sha256)row.append(el('p','Fichier résultat : '+r.result_file_sha256,'hash'));}
    row.append(el('p',e.sha256,'hash'));events.append(row);
  });
  if(busy)document.querySelectorAll('button').forEach(b=>b.disabled=true);
}
async function refresh(){state=await request('/api/state');render();$('status').textContent='Dossier chargé · intégrité du journal local contrôlée.';}
$('export-mobile').onclick=async()=>{
  if(busy || !state)return;busy=true;render();
  try {
    const result=await request('/api/export-mobile',{snapshot_sha256:state.snapshot_sha256});
    const url=URL.createObjectURL(new Blob([result.dossier_json],{type:'application/json'}));
    const link=el('a');link.href=url;link.download=result.filename;document.body.append(link);link.click();link.remove();
    setTimeout(()=>URL.revokeObjectURL(url),1000);
    $('export-status').textContent=`Dossier préparé : ${result.bytes} octets. Aucune autorisation transférée. Empreinte : ${result.dossier_sha256}`;
  } catch(e){$('error').textContent=errorText(e);$('error').hidden=false;}
  finally{busy=false;render();}
};
$('import-learning').onclick=async()=>{
  if(busy || !state)return;const file=$('learning-file').files[0];
  if(!file || file.size>300000){$('error').hidden=false;$('error').textContent='Choisis un compte rendu JSON de Jade de moins de 300 000 octets.';return;}
  try{const report=JSON.parse(await file.text());await action('import-learning',{snapshot_sha256:state.snapshot_sha256,report});}
  catch(e){$('error').hidden=false;$('error').textContent='Compte rendu refusé : '+errorText(e);}
};
$('refresh').onclick=()=>refresh().catch(e=>{state=undefined;render();$('error').textContent=e.message;$('error').hidden=false;});
// Keep expired permissions from leaving an active launch button on an idle page.
function expiryState(){return state.events.filter(e=>e.kind==='GRANTED').map(e=>e.authorization_id+':'+(e.expires_at_ms<=Date.now())).join('|');}
setInterval(()=>{if(!busy && state && expiryState()!==renderedExpiry)render();},1000);
refresh().catch(e=>{$('error').textContent=e.message;$('error').hidden=false;$('status').textContent='Dossier indisponible. Aucune action autorisée.';});
