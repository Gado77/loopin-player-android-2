import { defineConfig } from "vite";

export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("@zxing")) return "qr-scanner";
          if (id.includes("@supabase")) return "supabase";
          return undefined;
        },
      },
    },
  },
});
