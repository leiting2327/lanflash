FROM node:24-alpine

WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8080
ENV DATA_DIR=/data

COPY package.json ./
COPY src ./src

RUN mkdir -p /data
VOLUME ["/data"]
EXPOSE 8080

CMD ["node", "src/server.mjs"]
