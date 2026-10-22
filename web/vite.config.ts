import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In dev, the Java fog server on :8787 serves the API; in production it also serves web/dist.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": { target: "http://localhost:8787", changeOrigin: true } } },
});
