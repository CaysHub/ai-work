#!/bin/zsh
set -e
cd "${0:A:h}"
if ! command -v node >/dev/null || ! command -v npm >/dev/null; then
  for node_bin in ~/.nvm/versions/node/*/bin(NOn); do
    if [[ -x "$node_bin/node" && -x "$node_bin/npm" ]] && "$node_bin/node" -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && minor >= 12) ? 0 : 1)'; then
      export PATH="$node_bin:$PATH"
      break
    fi
  done
fi
if ! command -v node >/dev/null || ! command -v npm >/dev/null; then
  print -u2 '请先安装 Node.js 22.12 或更高版本。'
  exit 1
fi
if ! node -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && minor >= 12) ? 0 : 1)'; then
  print -u2 '请先切换到 Node.js 22.12 或更高版本。'
  exit 1
fi
if [[ ! -d node_modules/vite || ! -d node_modules/express || ! -d node_modules/react || ! -d node_modules/react-dom || ! -d node_modules/@vitejs/plugin-react ]]; then
  npm ci --no-audit --no-fund
fi
exec node server/index.js "$@"
