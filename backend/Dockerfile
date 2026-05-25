FROM golang:1.25-alpine AS builder

WORKDIR /app

RUN apk add --no-cache git

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o main . \
 && CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o create_user ./cmd/create_user \
 && CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o migrate ./cmd/migrate

FROM alpine:3.21

RUN apk add --no-cache ca-certificates tzdata font-dejavu

WORKDIR /app

COPY --from=builder /app/main .
COPY --from=builder /app/create_user .
# Schema/data migrations CLI — invoked as `docker compose exec backend /app/migrate`.
COPY --from=builder /app/migrate .

EXPOSE 8080

CMD ["./main"]
