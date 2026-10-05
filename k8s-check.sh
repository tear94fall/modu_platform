#!/usr/bin/env bash
# config-repo 의 모든 파일이 kustomization.yaml 의 configMapGenerator files 에 들어 있는지 확인한다(kustomize 에 glob 이 없어서).
# 빠진 파일이 있으면 1 로 끝난다. CI(images.yml)와 사람이 둘 다 쓴다.
set -euo pipefail
cd "$(dirname "$0")"
missing=0
while IFS= read -r f; do
  grep -qF -- "- $f" kustomization.yaml || { echo "kustomization.yaml 에 없음: $f" >&2; missing=1; }
done < <(find config-repo -type f \( -name '*.yml' -o -name '*.yaml' -o -name '*.properties' \) | sort)
[ "$missing" = 0 ] && echo "config-repo 파일 $(find config-repo -type f | wc -l | tr -d ' ')개 모두 kustomization.yaml 에 있음"
exit $missing
