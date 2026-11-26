#!/usr/bin/env bash
# One-shot install of Pocket Agent on a fresh Ubuntu server (Oracle Always Free, ARM or x86).
# Run as the default user (ubuntu):  bash setup.sh
# Afterwards, put the secrets in place (see the end of this script) and start the services.
set -euo pipefail

sudo apt-get update -y
sudo apt-get install -y curl git ca-certificates

# Node.js 22 (needed for --env-file-if-exists)
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs

# the app
if [ ! -d ~/pocket-agent ]; then git clone https://github.com/varsansri/pocket-agent.git ~/pocket-agent; fi
cd ~/pocket-agent && git pull --ff-only || true
npm ci || npm install
npx playwright install --with-deps chromium

# cloudflared (reuses the hanubees tunnel, so no DNS change is needed)
ARCH=$(dpkg --print-architecture)   # arm64 or amd64
curl -fsSL -o /tmp/cloudflared.deb "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-${ARCH}.deb"
sudo dpkg -i /tmp/cloudflared.deb
mkdir -p ~/.cloudflared

# a little swap so Chromium never runs the box out of memory
if [ ! -f /swapfile ]; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

# services: both restart on crash and on reboot
sudo tee /etc/systemd/system/pocket-agent.service >/dev/null <<UNIT
[Unit]
Description=Pocket Agent
After=network-online.target
[Service]
User=$USER
WorkingDirectory=/home/$USER/pocket-agent
ExecStart=/usr/bin/npm start
Restart=always
RestartSec=3
[Install]
WantedBy=multi-user.target
UNIT

sudo tee /etc/systemd/system/pocket-tunnel.service >/dev/null <<UNIT
[Unit]
Description=Cloudflare tunnel for hanubees.com
After=network-online.target
[Service]
User=$USER
ExecStart=/usr/bin/cloudflared tunnel --no-autoupdate --config /home/$USER/.cloudflared/config.yml run
Restart=always
RestartSec=3
[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable pocket-agent pocket-tunnel

echo
echo "Installed. Still needed before 'sudo systemctl start pocket-agent pocket-tunnel':"
echo "  ~/pocket-agent/.env           GEMINI_API_KEY=...   (the owner types this)"
echo "  ~/pocket-agent/access-key.txt the access key"
echo "  ~/.cloudflared/config.yml + the tunnel credentials .json"
