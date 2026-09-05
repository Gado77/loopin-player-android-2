import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2.105.3";

const service=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{persistSession:false,autoRefreshToken:false}});
const headers={"content-type":"application/json","cache-control":"no-store"};
const reply=(status:number,body:unknown)=>new Response(JSON.stringify(body),{status,headers});
const sha256=async(value:string)=>Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(value)))).map(b=>b.toString(16).padStart(2,"0")).join("");
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
async function body(request:Request){if(Number(request.headers.get("content-length")??"0")>16384)return null;try{const value=await request.json();return value&&typeof value==="object"&&!Array.isArray(value)?value as Record<string,unknown>:null;}catch{return null;}}
function exact(value:Record<string,unknown>,keys:string[]){return Object.keys(value).every(k=>keys.includes(k))&&keys.every(k=>k in value);}
function safeResult(commandType:string,value:unknown){if(!value||typeof value!=="object"||Array.isArray(value))return null;const source=value as Record<string,unknown>;if(JSON.stringify(source).length>4096)return null;if(commandType==="GET_STATUS"){const keys=["app_version","session_id","uptime_ms","available_memory_bytes","memory_low","free_storage_bytes","total_storage_bytes","connection","playback_state","cache_state","sync_state","health_state","last_sync_epoch_ms","active_playlist_id","active_playlist_version","active_manifest_etag","previous_playlist_id","current_item_id","current_content_kind","current_media_type","last_error_code","last_error_summary","last_error_at_epoch_ms","update_channel","current_version_code","update_state","available_version_code","prepared_version_code","last_update_check_epoch_ms","last_update_error","installation_capability","installation_state","install_requested_at_epoch_ms","post_update_verification_state","last_install_failure_code"];if(!exact(source,keys)||typeof source.app_version!=="string"||source.app_version.length>100||typeof source.session_id!=="string"||source.session_id.length>64||typeof source.memory_low!=="boolean")return null;for(const k of ["uptime_ms","available_memory_bytes","free_storage_bytes","total_storage_bytes","current_version_code"]){if(typeof source[k]!=="number"||!Number.isSafeInteger(source[k])||(source[k] as number)<0)return null;}for(const k of ["last_sync_epoch_ms","active_playlist_version","last_error_at_epoch_ms","available_version_code","prepared_version_code","last_update_check_epoch_ms","install_requested_at_epoch_ms"]){if(!(source[k]===null||(typeof source[k]==="number"&&Number.isSafeInteger(source[k])&&(source[k] as number)>=0)))return null;}for(const k of ["connection","playback_state","cache_state","sync_state","health_state","update_channel","update_state","installation_capability"]){if(typeof source[k]!=="string"||String(source[k]).length>32)return null;}for(const k of ["active_playlist_id","active_manifest_etag","previous_playlist_id","current_item_id","current_content_kind","current_media_type","last_error_code","last_error_summary","last_update_error","installation_state","post_update_verification_state","last_install_failure_code"]){if(source[k]!==null&&typeof source[k]!=="string")return null;if(typeof source[k]==="string"&&String(source[k]).length>256)return null;}return source;}if(!exact(source,["code"])||typeof source.code!=="string"||source.code.length<1||source.code.length>64)return null;return{code:source.code};}

Deno.serve(async request=>{
  if(request.method!=="POST")return reply(405,{error:"method_not_allowed"});
  const credential=request.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1]?.trim();
  if(!credential||credential.length<40||credential.length>128)return reply(401,{error:"invalid_device_credential"});
  const{data:auth,error:authError}=await service.from("device_credentials").select("device_id, devices!inner(pairing_status)").eq("credential_hash",await sha256(credential)).is("revoked_at",null).maybeSingle();
  const device=auth?.devices as unknown as {pairing_status:string}|null;
  if(authError||!auth||device?.pairing_status!=="PAIRED")return reply(401,{error:"invalid_device_credential"});
  const input=await body(request);if(!input||typeof input.action!=="string")return reply(400,{error:"invalid_request"});
  if(input.action==="fetch"){
    if(!exact(input,["action"]))return reply(400,{error:"invalid_request"});
    const{data,error}=await service.rpc("claim_player_commands",{p_device_id:auth.device_id,p_limit:5});
    if(error)return reply(500,{error:"command_claim_failed"});
    return reply(200,{commands:(data??[]).map((c:any)=>({id:c.id,type:c.command_type,created_at:c.created_at,expires_at:c.expires_at,payload:null}))});
  }
  if(input.action==="complete"){
    if(!exact(input,["action","command_id","status","result"])||!UUID.test(String(input.command_id))||!['SUCCEEDED','FAILED'].includes(String(input.status)))return reply(400,{error:"invalid_completion"});
    const{data:command}=await service.from("device_commands").select("command_type").eq("id",input.command_id).eq("device_id",auth.device_id).maybeSingle();
    if(!command)return reply(403,{error:"command_unavailable"});const result=safeResult(command.command_type,input.result);if(!result)return reply(400,{error:"invalid_result"});
    const{data,error}=await service.rpc("complete_player_command",{p_device_id:auth.device_id,p_command_id:input.command_id,p_status:input.status,p_result:result});
    if(error)return reply(403,{error:"command_completion_failed"});return reply(200,{ok:true,status:data.status,completed_at:data.completed_at,result:data.result});
  }
  return reply(400,{error:"invalid_action"});
});
