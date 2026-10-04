FROM node:22-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
ARG API_INTERNAL_URL=http://control-plane:8080
ARG NEXT_PUBLIC_API_BASE=/backend
ARG NEXT_PUBLIC_JAEGER_URL=http://localhost:16686
ARG NEXT_PUBLIC_GRAFANA_URL=http://localhost:3001
ENV API_INTERNAL_URL=$API_INTERNAL_URL
ENV NEXT_PUBLIC_API_BASE=$NEXT_PUBLIC_API_BASE
ENV NEXT_PUBLIC_JAEGER_URL=$NEXT_PUBLIC_JAEGER_URL
ENV NEXT_PUBLIC_GRAFANA_URL=$NEXT_PUBLIC_GRAFANA_URL
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
