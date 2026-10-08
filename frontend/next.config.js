/** @type {import('next').NextConfig} */
const api = process.env.API_INTERNAL_URL || "http://localhost:8080";

const nextConfig = {
  output: "standalone",
  async headers() {
    return [{
      source: "/:path*",
      headers: [
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "Referrer-Policy", value: "no-referrer" },
        { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Content-Security-Policy", value: "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'" },
      ],
    }];
  },
  async rewrites() {
    return [{ source: "/backend/:path*", destination: `${api}/:path*` }];
  },
};

module.exports = nextConfig;
