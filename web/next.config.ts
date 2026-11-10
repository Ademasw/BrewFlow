import type { NextConfig } from "next";

// Same origin for UI and /api/v1 (section 1): the web server proxies the API to the Spring Boot backend.
const backend = process.env.BREWFLOW_BACKEND_URL ?? "http://127.0.0.1:8080";

const nextConfig: NextConfig = {
  // SSE must not be buffered by compression
  compress: false,
  poweredByHeader: false,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async rewrites() {
    return [{ source: "/api/v1/:path*", destination: `${backend}/api/v1/:path*` }];
  },
  async headers() {
    return [
      {
        // no back-button cache of staff screens
        source: "/:path*",
        headers: [
          { key: "Cache-Control", value: "no-store" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "same-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
