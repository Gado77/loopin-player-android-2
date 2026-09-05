import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {createClient} from "npm:@supabase/supabase-js@2.105.3";
const service=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{persistSession:false,autoRefreshToken:false}});
const json=(status:number,value:unknown)=>new Response(JSON.stringify(value),{status,headers:{"content-type":"application/json","cache-control":"no-store"}});
const sha=async(v:string)=>Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(v)))).map(x=>x.toString(16).padStart(2,"0")).join("");
const UUID=/^[0-9a-f]{8}-[0-9a-f-]{27}$/i;
Deno.serve(async req=>{
 if(req.method!=="POST")return json(405,{error:"method_not_allowed"});
 const credential=req.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1]?.trim();
 if(!credential||credential.length<40||credential.length>128)return json(401,{error:"invalid_device_credential"});
 const {data:auth}=await service.from("device_credentials").select("device_id,devices!inner(pairing_status,update_channel)").eq("credential_hash",await sha(credential)).is("revoked_at",null).maybeSingle();
 const device=auth?.devices as unknown as {pairing_status:string;update_channel:string}|null;
 if(!auth||device?.pairing_status!=="PAIRED")return json(401,{error:"invalid_device_credential"});
 let body:Record<string,unknown>;try{body=await req.json()}catch{return json(400,{error:"invalid_request"})}
 if(body.action==="check"){
  const current=Number(body.version_code);if(!Number.isSafeInteger(current)||current<1)return json(400,{error:"invalid_version"});
  const {data:rows}=await service.rpc("get_eligible_player_release",{p_device_id:auth.device_id,p_current_version:current,p_release_id:null});const r=rows?.[0];
  if(!r)return new Response(null,{status:204,headers:{"cache-control":"no-store"}});
  await service.from("update_rollout_devices").update({state:"AVAILABLE",first_check_at:new Date().toISOString()}).eq("rollout_id",r.rollout_id).eq("device_id",auth.device_id).in("state",["PENDING","NOT_IN_CURRENT_WAVE","WAITING_WINDOW","AVAILABLE"]);
  return json(200,{release_id:r.release_id,channel:r.channel,version_code:r.version_code,version_name:r.version_name,package_name:r.package_name,size:r.apk_size_bytes,sha256:r.apk_sha256,certificate_sha256:r.certificate_sha256,release_notes:r.release_notes});
 }
 if(body.action==="download"&&UUID.test(String(body.release_id))){
  const {data:r}=await service.from("player_releases").select("storage_path,channel,status").eq("id",body.release_id).maybeSingle();
  const {data:eligible}=await service.rpc("get_eligible_player_release",{p_device_id:auth.device_id,p_current_version:0,p_release_id:body.release_id});
  if(!r||!eligible?.[0]||r.status!=="PUBLISHED"||r.channel!==device.update_channel)return json(404,{error:"release_unavailable"});
  const {data:signed,error}=await service.storage.from("player2-releases").createSignedUrl(r.storage_path,300);
  if(error||!signed)return json(503,{error:"download_unavailable"});return json(200,{download_url:signed.signedUrl,expires_in:300});
 }
 if(body.action==="authorize_install"&&UUID.test(String(body.release_id))){
  const current=Number(body.current_version_code);if(!Number.isSafeInteger(current)||current<1)return json(400,{error:"invalid_version"});
  const {data:eligible}=await service.rpc("get_eligible_player_release",{p_device_id:auth.device_id,p_current_version:current,p_release_id:body.release_id});const r=eligible?.[0];
  if(!r)return json(409,{error:"install_window_closed_or_rollout_inactive"});
  const {data:a}=await service.from("device_update_attempts").select("id").eq("device_id",auth.device_id).eq("release_id",r.release_id).maybeSingle();
  if(!a)return json(409,{error:"install_not_requested"});
  return json(200,{authorized:true,release_id:r.release_id,target_version_code:r.version_code});
 }
 if(body.action==="report_install"&&UUID.test(String(body.release_id))){
  const allowed=["INSTALL_PERMISSION_REQUIRED","USER_ACTION_REQUIRED","INSTALLING","POST_UPDATE_VERIFYING","INSTALLED","INSTALL_DEFERRED","INSTALL_CANCELED","INSTALL_FAILED","UPDATE_RECOVERY_REQUIRED"];
  const state=String(body.state??""),failure=body.failure_code==null?null:String(body.failure_code),current=Number(body.current_version_code);
  if(!allowed.includes(state)||failure&&failure.length>64||!Number.isSafeInteger(current)||current<1)return json(400,{error:"invalid_report"});
  const {error}=await service.rpc("report_device_update_attempt",{p_device_id:auth.device_id,p_release_id:body.release_id,p_state:state,p_failure_code:failure,p_current_version:current});
  if(error)return json(409,{error:"invalid_transition"});return json(200,{ok:true});
 }
 return json(400,{error:"invalid_action"});
});
