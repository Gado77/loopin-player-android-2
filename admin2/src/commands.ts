import type { DeviceCommand, PlayerCommandStatus, PlayerCommandType } from "./types";
export const COMMAND_LABELS:Record<PlayerCommandType,string>={GET_STATUS:"Atualizar status",SYNC_NOW:"Sincronizar agora",RELOAD_PLAYLIST:"Recarregar playlist",CHECK_UPDATE:"Verificar atualização"};
export const commandStatusLabel=(status:PlayerCommandStatus)=>({PENDING:"Aguardando Player…",DELIVERED:"Entregue",SUCCEEDED:"Concluído",FAILED:"Falhou",EXPIRED:"Expirado"})[status];
export const hasPendingCommands=(commands:DeviceCommand[])=>commands.some(command=>command.status==="PENDING"||command.status==="DELIVERED");
export function commandResultLabel(command:DeviceCommand){if(!command.result)return"";if(command.command_type==="GET_STATUS")return [command.result.health_state,command.result.playback_state,command.result.sync_state].filter(Boolean).join(" · ");return typeof command.result.code==="string"?command.result.code:"";}
