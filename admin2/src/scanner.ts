import { BrowserQRCodeReader, type IScannerControls } from "@zxing/browser";

export class PairingScanner {
  private readonly reader = new BrowserQRCodeReader();
  private controls: IScannerControls | null = null;

  async start(video: HTMLVideoElement, onResult: (value: string) => boolean, onError: (message: string) => void) {
    this.stop();
    try {
      this.controls = await this.reader.decodeFromVideoDevice(undefined, video, (result) => {
        if (!result) return;
        if (onResult(result.getText())) this.stop();
      });
    } catch {
      onError("Não foi possível acessar a câmera. Verifique a permissão do navegador.");
    }
  }

  stop() {
    this.controls?.stop();
    this.controls = null;
  }
}
