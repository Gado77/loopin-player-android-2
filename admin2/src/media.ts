export const MEDIA_LIMITS = { IMAGE: 20 * 1024 * 1024, VIDEO: 300 * 1024 * 1024 } as const;
export const MIME_TYPES = { "video/mp4":"VIDEO", "image/jpeg":"IMAGE", "image/png":"IMAGE" } as const;

export function validateMediaFile(file: Pick<File,"name"|"type"|"size">) {
  if (!file.name.trim() || file.name.length > 200 || /[\u0000-\u001f\u007f]/.test(file.name)) throw new Error("Nome de arquivo inválido.");
  const mediaType = MIME_TYPES[file.type as keyof typeof MIME_TYPES];
  if (!mediaType) throw new Error("Formato não suportado. Use MP4, JPEG ou PNG.");
  if (file.size <= 0) throw new Error("O arquivo está vazio.");
  if (file.size > MEDIA_LIMITS[mediaType]) throw new Error(`Arquivo maior que o limite de ${mediaType === "VIDEO" ? "300" : "20"} MiB.`);
  return mediaType;
}

export class Sha256 {
  private state = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
  private pending = new Uint8Array(0); private length = 0;
  update(input: Uint8Array) { this.length += input.length; const data=new Uint8Array(this.pending.length+input.length);data.set(this.pending);data.set(input,this.pending.length);let offset=0;for(;offset+64<=data.length;offset+=64)this.block(data.subarray(offset,offset+64));this.pending=data.slice(offset);return this; }
  digestHex(){const bitLength=this.length*8;const size=((this.pending.length+9+63)>>6)<<6;const tail=new Uint8Array(size);tail.set(this.pending);tail[this.pending.length]=0x80;const view=new DataView(tail.buffer);view.setUint32(size-4,bitLength>>>0);view.setUint32(size-8,Math.floor(bitLength/0x100000000));for(let i=0;i<size;i+=64)this.block(tail.subarray(i,i+64));return Array.from(this.state).map(v=>v.toString(16).padStart(8,"0")).join("");}
  private block(chunk:Uint8Array){const k=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];const w=new Uint32Array(64);const v=new DataView(chunk.buffer,chunk.byteOffset,64);for(let i=0;i<16;i++)w[i]=v.getUint32(i*4);for(let i=16;i<64;i++){const a=w[i-15],b=w[i-2];const s0=((a>>>7)|(a<<25))^((a>>>18)|(a<<14))^(a>>>3);const s1=((b>>>17)|(b<<15))^((b>>>19)|(b<<13))^(b>>>10);w[i]=(w[i-16]+s0+w[i-7]+s1)>>>0;}let[a,b,c,d,e,f,g,h]=this.state;for(let i=0;i<64;i++){const s1=((e>>>6)|(e<<26))^((e>>>11)|(e<<21))^((e>>>25)|(e<<7));const ch=(e&f)^(~e&g);const t1=(h+s1+ch+k[i]+w[i])>>>0;const s0=((a>>>2)|(a<<30))^((a>>>13)|(a<<19))^((a>>>22)|(a<<10));const maj=(a&b)^(a&c)^(b&c);const t2=(s0+maj)>>>0;h=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0;}[a,b,c,d,e,f,g,h].forEach((x,i)=>this.state[i]=(this.state[i]+x)>>>0);}
}

export async function hashFile(file:Blob, chunkSize=2*1024*1024){const hash=new Sha256();for(let offset=0;offset<file.size;offset+=chunkSize)hash.update(new Uint8Array(await file.slice(offset,offset+chunkSize).arrayBuffer()));return hash.digestHex();}
export function safeExtension(mime:string){return mime==="video/mp4"?"mp4":mime==="image/png"?"png":"jpg";}
