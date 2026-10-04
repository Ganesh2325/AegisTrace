/** @type {import('next').NextConfig} */
const api = process.env.API_INTERNAL_URL || "http://localhost:8080";

const nextConfig = {
  output: "standalone",
  async rewrites() {
    return [{ source: "/backend/:path*", destination: `${api}/:path*` }];
  },
};

module.exports = nextConfig;
