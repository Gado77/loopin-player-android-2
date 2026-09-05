import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {createClient} from "npm:@supabase/supabase-js@2.105.3";
const url=Deno.env.get("SUPABASE_URL")!,key=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const service=createClient(url,key,{auth:{persistSession:false,autoRefreshToken:false}});
const reply=(s:number,b:unknown)=>new Response(JSON.stringify(b),{status:s,headers:{"content-type":"application/json","cache-control":"no-store"}});
Deno.serve(async req=>{
 if(req.method!=="POST")return reply(405,{error:"method_not_allowed"});
 const token=req.headers.get("authorization")?.replace(/^Bearer\s+/i,"");if(!token)return reply(401,{error:"unauthorized"});
 const {data:{user}}=await service.auth.getUser(token);if(!user)return reply(401,{error:"unauthorized"});
 const {data:role}=await service.from("release_admins").select("user_id").eq("user_id",user.id).maybeSingle();if(!role)return reply(403,{error:"release_admin_required"});
 let b:Record<string,any>;try{b=await req.json()}catch{return reply(400,{error:"invalid_request"})}
 if(b.action==="create"&&["STABLE","BETA"].includes(b.channel)&&Number.isSafeInteger(b.size)&&b.size>0&&b.size<=104857600){
  const id=crypto.randomUUID(),path=`releases/${b.channel}/${id}/player.apk`;
  const {data,error}=await service.from("player_releases").insert({id,channel:b.channel,apk_size_bytes:b.size,storage_path:path,release_notes:String(b.release_notes??"").slice(0,4000),created_by:user.id}).select().single();
  if(error)return reply(409,{error:"release_create_failed"});const {data:signed}=await service.storage.from("player2-releases").createSignedUploadUrl(path);return reply(200,{release:data,upload:signed});
 }
 if(b.action==="inspect"){
  if(req.headers.get("x-release-inspector")!==Deno.env.get("RELEASE_INSPECTOR_TOKEN"))return reply(403,{error:"inspector_required"});
  if(b.package_name!=="com.loopin.player2"||!Number.isSafeInteger(b.version_code)||b.version_code<1||!/^[a-f0-9]{64}$/.test(b.apk_sha256)||!/^[a-f0-9]{64}$/.test(b.certificate_sha256))return reply(400,{error:"invalid_inspection"});
  const {data:r}=await service.from("player_releases").select("storage_path,apk_size_bytes,status").eq("id",b.release_id).single();if(!r||r.status!=="DRAFT")return reply(409,{error:"release_not_draft"});
  const parts=r.storage_path.split("/"),name=parts.pop()!,folder=parts.join("/");const {data:objects}=await service.storage.from("player2-releases").list(folder,{search:name,limit:2});const object=objects?.find(o=>o.name===name);if(!object||Number(object.metadata?.size)!==r.apk_size_bytes)return reply(400,{error:"stored_size_mismatch"});
  const {error}=await service.from("player_releases").update({version_code:b.version_code,version_name:String(b.version_name).slice(0,100),package_name:b.package_name,apk_sha256:b.apk_sha256,certificate_sha256:b.certificate_sha256,inspected_at:new Date().toISOString()}).eq("id",b.release_id).eq("status","DRAFT");return error?reply(409,{error:"inspection_failed"}):reply(200,{ok:true});
 }
 return reply(400,{error:"invalid_action"});
});
