FROM node:24-alpine AS builder

WORKDIR /app

# Enable corepack so the project-local yarn version (pinned in package.json -> packageManager) is used.
RUN corepack enable

# Copy manifests first so yarn can leverage Docker layer caching for the install step.
COPY package.json yarn.lock .yarnrc.yml ./
RUN yarn install --immutable

COPY . .
RUN yarn build

FROM nginx:alpine

# OCI image labels — picked up by GHCR / GitHub releases UI when published via Actions.
ARG WEB_VERSION=dev
ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="msdnna-budget-frontend" \
      org.opencontainers.image.description="Vue 3 SPA + nginx for msdnna-budget." \
      org.opencontainers.image.source="https://github.com/msdnna/budget" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${WEB_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}"

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
