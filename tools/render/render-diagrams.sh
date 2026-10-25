#!/usr/bin/env bash
# Renders docs/diagrams/*.mmd to PNG using mermaid-cli with the locally installed Chrome.
set +e
cd "$(dirname "$0")/../.."
mkdir -p docs/diagrams/png
for f in docs/diagrams/*.mmd; do
  n=$(basename "$f" .mmd)
  tools/render/node_modules/.bin/mmdc -p tools/render/puppeteer.json -i "$f" -o "docs/diagrams/png/$n.png" -s 2 -b white
done
