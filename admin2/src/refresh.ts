export class DashboardPresenceRefresher {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private running = false;

  constructor(
    private readonly refresh: () => Promise<void>,
    private readonly isVisible: () => boolean,
    private readonly intervalMs = 60_000,
  ) {}

  start() {
    if (this.running) return;
    this.running = true;
    this.schedule();
  }

  stop() {
    this.running = false;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  private schedule() {
    if (!this.running || this.timer !== null) return;
    this.timer = setTimeout(async () => {
      this.timer = null;
      if (!this.running) return;
      if (this.isVisible()) await this.refresh().catch(() => undefined);
      this.schedule();
    }, this.intervalMs);
  }
}
