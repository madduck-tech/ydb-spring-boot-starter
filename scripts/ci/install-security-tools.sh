#!/usr/bin/env bash
set -euo pipefail

tool=${1:?Usage: install-security-tools.sh osv-scanner|gitleaks directory}
destination=${2:?Provide an installation directory}
mkdir -p "$destination"
case "$(uname -s)/$(uname -m)/$tool" in
  Linux/x86_64/osv-scanner)
    asset=osv-scanner_linux_amd64
    checksum=ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108 ;;
  Darwin/arm64/osv-scanner)
    asset=osv-scanner_darwin_arm64
    checksum=98c460dcd37de25819babd757d04542045b6243113e209edcd4d89fedb0256b4 ;;
  Linux/x86_64/gitleaks)
    asset=gitleaks_8.30.1_linux_x64.tar.gz
    checksum=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb ;;
  Darwin/arm64/gitleaks)
    asset=gitleaks_8.30.1_darwin_arm64.tar.gz
    checksum=b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5 ;;
  *) echo "Unsupported platform or tool: $tool" >&2; exit 1 ;;
esac
case "$tool" in
  osv-scanner) url="https://github.com/google/osv-scanner/releases/download/v2.6.0/$asset" ;;
  gitleaks) url="https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/$asset" ;;
esac
curl --fail --silent --show-error --location --retry 3 "$url" --output "$destination/$asset"
printf '%s  %s\n' "$checksum" "$destination/$asset" | shasum -a 256 --check
if [[ "$tool" == gitleaks ]]; then
  tar -xzf "$destination/$asset" -C "$destination" gitleaks
else
  cp "$destination/$asset" "$destination/osv-scanner"
fi
chmod +x "$destination/$tool"
