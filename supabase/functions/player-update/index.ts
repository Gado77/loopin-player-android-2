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
  const {data:r}=await service.from("player_releases").select("id,channel,version_code,version_name,package_name,apk_size_bytes,apk_sha256,certificate_sha256,release_notes").eq("status","PUBLISHED").eq("channel",device.update_channel).eq("package_name","com.loopin.player2").gt("version_code",current).order("version_code",{ascending:false}).limit(1).maybeSingle();
  if(!r)return new Response(null,{status:204,headers:{"cache-control":"no-store"}});
  return json(200,{release_id:r.id,channel:r.channel,version_code:r.version_code,version_name:r.version_name,package_name:r.package_name,size:r.apk_size_bytes,sha256:r.apk_sha256,certificate_sha256:r.certificate_sha256,release_notes:r.release_notes});
 }
 if(body.action==="download"&&UUID.test(String(body.release_id))){
  const {data:r}=await service.from("player_releases").select("storage_path,channel,status").eq("id",body.release_id).maybeSingle();
  if(!r||r.status!=="PUBLISHED"||r.channel!==device.update_channel)return json(404,{error:"release_unavailable"});
  const {data:signed,error}=await service.storage.from("player2-releases").createSignedUrl(r.storage_path,300);
  if(error||!signed)return json(503,{error:"download_unavailable"});return json(200,{download_url:signed.signedUrl,expires_in:300});
 }
 return json(400,{error:"invalid_action"});
});
