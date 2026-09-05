export const MAX_APK_BYTES=100*1024*1024;
export function validateApkUpload(file:{name:string;size:number}){if(!file.name.toLowerCase().endsWith(".apk"))throw new Error("invalid_extension");if(file.size<1)throw new Error("empty_apk");if(file.size>MAX_APK_BYTES)throw new Error("apk_too_large");return true;}
export const canPublishRelease=(release:{status:string;inspected_at:string|null})=>release.status==="DRAFT"&&!!release.inspected_at;
export const shortSha=(sha:string|null)=>sha?`${sha.slice(0,12)}…`:"SHA pendente";
