#!/usr/bin/env bash
# Bootstrap a fresh Raspberry Pi OS Lite (64-bit, Bookworm) host for running
# the budget-go stack. Idempotent — safe to re-run.
#
# Usage:  curl -fsSL .../rpi-bootstrap.sh | sudo bash
#    or:  sudo ./tools/rpi-bootstrap.sh
#
# What it does:
#   - apt update + upgrade
#   - installs Docker CE + compose plugin (official Docker repo, arm64)
#   - installs avahi-daemon (mDNS — exposes <hostname>.local on the LAN)
#   - installs unattended-upgrades (security patches only, no reboots)
#   - lowers vm.swappiness to 10 (Mongo is happier touching disk than swap)
#   - adds the invoking user to the `docker` group
#   - sets hostname to "budget" (so the Pi resolves as budget.local) — skip with
#     KEEP_HOSTNAME=1 if you already named it
#
# Deliberately does NOT touch the firewall (LAN-only deployment) and does NOT
# install any reverse proxy — the frontend nginx already terminates plain HTTP
# on :80 and proxies /api/.

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "rpi-bootstrap: must run as root (use sudo)" >&2
  exit 1
fi

TARGET_USER="${SUDO_USER:-${USER:-pi}}"
TARGET_HOSTNAME="${TARGET_HOSTNAME:-budget}"

log() { printf '\033[1;36m[rpi-bootstrap]\033[0m %s\n' "$*"; }

log "running as root; will configure docker for user: $TARGET_USER"

# ----- apt baseline --------------------------------------------------------
log "apt update + base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
  ca-certificates curl gnupg lsb-release \
  avahi-daemon \
  unattended-upgrades apt-listchanges \
  jq \
  cron

# ----- Docker CE (official repo) ------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  log "installing Docker CE from docker.com (arm64)"
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg \
    | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg

  CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
  cat >/etc/apt/sources.list.d/docker.list <<EOF
deb [arch=arm64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian ${CODENAME} stable
EOF
  apt-get update -qq
  apt-get install -y -qq \
    docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
else
  log "docker already installed; skipping repo setup"
fi

systemctl enable --now docker

# ----- user → docker group -------------------------------------------------
if id -nG "$TARGET_USER" | tr ' ' '\n' | grep -qx docker; then
  log "user $TARGET_USER already in docker group"
else
  log "adding $TARGET_USER to docker group (log out + back in to take effect)"
  usermod -aG docker "$TARGET_USER"
fi

# ----- mDNS via Avahi ------------------------------------------------------
log "enabling avahi-daemon (mDNS / .local resolution)"
systemctl enable --now avahi-daemon

# ----- hostname ------------------------------------------------------------
if [[ "${KEEP_HOSTNAME:-0}" != "1" ]]; then
  CURRENT_HOSTNAME="$(hostnamectl --static)"
  if [[ "$CURRENT_HOSTNAME" != "$TARGET_HOSTNAME" ]]; then
    log "setting hostname: $CURRENT_HOSTNAME → $TARGET_HOSTNAME (.local via mDNS)"
    hostnamectl set-hostname "$TARGET_HOSTNAME"
    # /etc/hosts 127.0.1.1 line keeps sudo from whining about resolution
    if grep -qE '^127\.0\.1\.1' /etc/hosts; then
      sed -i -E "s|^127\.0\.1\.1.*|127.0.1.1\t${TARGET_HOSTNAME}|" /etc/hosts
    else
      printf '127.0.1.1\t%s\n' "$TARGET_HOSTNAME" >> /etc/hosts
    fi
  fi
fi

# ----- swappiness ----------------------------------------------------------
SYSCTL_FILE=/etc/sysctl.d/99-budget.conf
if [[ ! -f $SYSCTL_FILE ]]; then
  log "writing $SYSCTL_FILE (vm.swappiness=10)"
  cat >"$SYSCTL_FILE" <<'EOF'
# Prefer page cache eviction over swapping out Mongo's hot working set.
vm.swappiness=10
EOF
  sysctl -q --system
fi

# ----- unattended security upgrades ---------------------------------------
log "enabling unattended-upgrades (security only, no auto-reboot)"
cat >/etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF

# ----- summary -------------------------------------------------------------
log "done."
cat <<EOF

  Next steps:
    1. log out and log back in (so the docker group sticks for $TARGET_USER), or:
         newgrp docker
    2. clone the repo:
         git clone https://github.com/msdnna/budget.git
    3. cd budget && cp .env.rpi.example .env  (then edit)
    4. echo "<GHCR_PAT>" | docker login ghcr.io -u <github-user> --password-stdin
         (only needed if the GHCR images are private; public images don't require login)
    5. make rpi-up
    6. point a browser at http://${TARGET_HOSTNAME}.local

  To install the daily backup:  sudo make rpi-backup-install
EOF
