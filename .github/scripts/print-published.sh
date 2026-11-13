#!/usr/bin/env bash
# Prints what a workflow run published, on the log and in the run's summary page.
#   print-published.sh <maven version> "<image tags>" [release]
# The Maven artifacts are the ones foodstack-bom lists (every published module), plus the parent POM.
set -euo pipefail
version=$1 tags=$2 release=${3:-}
artifacts=$(sed -n 's#.*<artifactId>\(foodstack-[^<]*\)</artifactId>.*#\1#p' foodstack-bom/pom.xml | sort -u)
{
  echo "## Published"
  echo
  echo "### Maven Central ($(echo "$artifacts" | wc -l | tr -d ' ') artifacts)"
  echo
  echo '```'
  echo "io.github.ilyaslabs:foodstack:$version (parent POM)"
  for a in $artifacts; do echo "io.github.ilyaslabs:$a:$version"; done
  echo '```'
  echo
  echo "### Images"
  echo
  echo '```'
  for part in server web backoffice; do
    for tag in $tags; do
      echo "docker.io/ilyasdotdev/ilyaslabs-foodstack:$part-$tag"
      echo "ghcr.io/ilyaslabs/foodstack:$part-$tag"
    done
  done
  echo '```'
  if [[ -n $release ]]; then
    echo
    echo "### Helm charts and the Docker Compose files"
    echo
    echo '```'
    echo "oci://ghcr.io/ilyaslabs/charts/foodstack:$version"
    echo "oci://ghcr.io/ilyaslabs/charts/foodstack-observability:$version"
    echo "https://github.com/ilyaslabs/foodstack/releases/download/$release/foodstack-compose-$version.zip"
    echo '```'
  fi
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
