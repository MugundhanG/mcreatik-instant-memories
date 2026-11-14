#!/usr/bin/env bash
# One-time (and safe to re-run) setup of a fresh Oracle Always Free VM for the Instant Memories backend:
# Docker, Caddy, 2 GB swap (if there is none), the OS firewall opened for 80/443, and ~/mcreatik-gallery.
# Works on Ubuntu and Oracle Linux. Run as the normal login user (opc / ubuntu); it uses sudo.
#
#   curl -fsSL https://raw.githubusercontent.com/mugundhang/mcreatik-instant-memories/main/deploy/oracle/setup-vm.sh | bash
#   (or from a clone: deploy/oracle/setup-vm.sh)
#
# NOT done here: the Oracle VCN security list. In the Oracle console (Networking -> VCN -> Security List) add ingress
# rules for TCP 80 and 443 from 0.0.0.0/0, otherwise Caddy cannot get its certificate and nothing reaches the VM.
set -euo pipefail

[[ $EUID -ne 0 ]] || { echo "Run as your normal user, not root (the script uses sudo)."; exit 1; }
[[ -r /etc/os-release ]] || { echo "Cannot detect the operating system."; exit 1; }
# shellcheck disable=SC1091
. /etc/os-release
case " ${ID:-} ${ID_LIKE:-} " in
  *" ubuntu "* | *" debian "*) FAMILY=debian ;;
  *" rhel "* | *" fedora "* | *" ol "*) FAMILY=rhel ;;
  *) echo "Unsupported OS (${PRETTY_NAME:-unknown}): this script supports Ubuntu and Oracle Linux."; exit 1 ;;
esac
echo "==> Detected ${PRETTY_NAME:-$ID} ($FAMILY family)"

# ---- Swap: 2 GB if the machine has none (the 1 GB E2.1.Micro needs it to start the backend) ----
if [[ -z "$(swapon --show --noheadings 2>/dev/null)" ]]; then
  echo "==> Adding 2 GB swap at /swapfile"
  if [[ ! -f /swapfile ]]; then
    sudo fallocate -l 2G /swapfile 2>/dev/null || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile >/dev/null
  fi
  sudo swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
else
  echo "==> Swap already present, leaving it"
fi

# ---- Docker ----
if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker"
  if [[ $FAMILY == debian ]]; then
    curl -fsSL https://get.docker.com | sudo sh
  else
    sudo dnf install -y dnf-plugins-core
    sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
    sudo dnf install -y docker-ce docker-ce-cli containerd.io --allowerasing
  fi
else
  echo "==> Docker already installed"
fi
sudo systemctl enable --now docker
id -nG "$USER" | tr ' ' '\n' | grep -qx docker || { sudo usermod -aG docker "$USER"; echo "    (added $USER to the docker group: log out and back in once)"; }

# ---- Caddy ----
if ! command -v caddy >/dev/null 2>&1; then
  echo "==> Installing Caddy"
  if [[ $FAMILY == debian ]]; then
    sudo apt-get update -y
    sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gpg
    curl -fsSL 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -fsSL 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
    sudo apt-get update -y
    sudo apt-get install -y caddy
  else
    sudo dnf install -y 'dnf-command(copr)'
    sudo dnf copr enable -y @caddy/caddy
    sudo dnf install -y caddy
  fi
else
  echo "==> Caddy already installed"
fi
sudo systemctl enable --now caddy

# ---- OS firewall: 80 and 443 ----
echo "==> Opening ports 80 and 443 in the VM firewall"
if command -v firewall-cmd >/dev/null 2>&1 && sudo systemctl is-active --quiet firewalld; then
  sudo firewall-cmd --permanent --add-service=http --add-service=https >/dev/null
  sudo firewall-cmd --reload >/dev/null
else
  # Oracle's Ubuntu images ship iptables rules that reject everything but SSH: insert ours before the REJECT rule.
  for port in 80 443; do
    sudo iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 5 -p tcp --dport "$port" -j ACCEPT
  done
  if [[ $FAMILY == debian ]]; then
    command -v netfilter-persistent >/dev/null 2>&1 || sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
    sudo netfilter-persistent save >/dev/null
  elif [[ -d /etc/sysconfig ]]; then
    sudo sh -c 'iptables-save > /etc/sysconfig/iptables'
  fi
fi

# ---- Working folder ----
mkdir -p "$HOME/mcreatik-gallery"
chmod 700 "$HOME/mcreatik-gallery"

cat <<'MSG'

==> VM is ready. Still to do by hand:
  1. Oracle console: VCN security list must allow TCP 80 and 443 ingress (the VM firewall is already open).
  2. Cloudflare DNS: the API host's A record -> this VM's public IP, DNS-only (grey cloud), so Caddy can get its certificate.
  3. Put the Caddy block for this environment in /etc/caddy/Caddyfile (deploy/oracle/Caddyfile.snippet for production,
     Caddyfile.beta.snippet for beta), then: sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
  4. Copy gallery.env.example (or gallery.env.beta.example) to ~/mcreatik-gallery/gallery.env, fill it in, chmod 600 it.
  5. Clone the repo and deploy (see backend/src/main/resources/docs/deployment.md, "Environments: beta and production").
MSG
