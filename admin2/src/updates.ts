import type {DeviceRuntimeStatus,DeviceUpdateAttempt} from "./types";
export const canInstallUpdate=(r?:DeviceRuntimeStatus|null)=>!!r&&r.update_state==="READY_TO_INSTALL"&&Number(r.prepared_version_code)>Number(r.current_version_code)&&!["INSTALL_REQUESTED","USER_ACTION_REQUIRED","INSTALLING","POST_UPDATE_VERIFYING"].includes(r.installation_state??"");
export function installStateLabel(state?:string|null,requestedAt?:string|null,now=Date.now()){
 if((state==="INSTALLING"||state==="POST_UPDATE_VERIFYING")&&requestedAt&&now-new Date(requestedAt).getTime()>15*60_000)return"Possível falha pós-update";
 return ({INSTALL_REQUESTED:"Solicitação aceita",INSTALL_PERMISSION_REQUIRED:"Autorize apps desconhecidos na TV",USER_ACTION_REQUIRED:"Aguardando confirmação na TV",INSTALLING:"Instalando…",POST_UPDATE_VERIFYING:"Verificando nova versão…",INSTALLED:"Atualização concluída",INSTALL_DEFERRED:"Adiada",INSTALL_CANCELED:"Cancelada",INSTALL_FAILED:"Falhou",UPDATE_RECOVERY_REQUIRED:"Recuperação necessária"} as Record<string,string>)[state??""]??(state??"Sem tentativa");
}
export const installationCapabilityLabel=(value?:string|null)=>value==="DEVICE_OWNER"?"Instalação gerenciada disponível":value==="INTERACTIVE_PERMISSION_REQUIRED"?"Requer autorização de fonte na TV":value==="INTERACTIVE_READY"?"Requer confirmação na TV":"Capacidade indisponível";
export const latestAttempt=(all:DeviceUpdateAttempt[],screenId:string)=>all.find(a=>a.screen_id===screenId);
