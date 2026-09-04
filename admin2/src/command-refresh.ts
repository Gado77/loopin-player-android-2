export class CommandRefreshController {
  private timer:ReturnType<typeof setTimeout>|null=null;
  constructor(private readonly refresh:()=>Promise<boolean>,private readonly visible:()=>boolean,private readonly intervalMs=12_000){}
  start(){if(this.timer!==null)return;this.timer=setTimeout(()=>void this.tick(),this.intervalMs);}
  stop(){if(this.timer!==null)clearTimeout(this.timer);this.timer=null;}
  private async tick(){this.timer=null;if(!this.visible()){this.start();return;}const keep=await this.refresh().catch(()=>true);if(keep)this.start();}
}
