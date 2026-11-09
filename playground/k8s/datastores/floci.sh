#!/usr/bin/env bash
# Floci (local AWS): S3 for photos and files, and EKS for the cluster. The two buckets get what an
# operator sets up at Amazon (handbook guide 12): public reading on one, CORS for the two sites.
source "$(dirname "$0")/../lib.sh"
network
mkdir -p "$STATE_DIR"
run_container fsplay-floci "$FLOCI_IP" -p 127.0.0.1:4566:4566 -v /var/run/docker.sock:/var/run/docker.sock \
  -e FLOCI_SERVICES_EKS_DOCKER_NETWORK="$PLAYGROUND_NETWORK" "$FLOCI_IMAGE"
wait_for "fsplay-floci" 60 curl -fsS http://localhost:4566/_floci/health
sites="\"http://foodstack.localhost:$PLAYGROUND_PORT\", \"http://admin.foodstack.localhost:$PLAYGROUND_PORT\""
cat > "$STATE_DIR/cors.json" << JSON
{"CORSRules": [{"AllowedOrigins": [$sites], "AllowedMethods": ["GET", "PUT"], "AllowedHeaders": ["*"], "MaxAgeSeconds": 3000}]}
JSON
cat > "$STATE_DIR/public-read.json" << JSON
{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": "*", "Action": "s3:GetObject",
  "Resource": "arn:aws:s3:::foodstack-public/*"}]}
JSON
for bucket in foodstack-public foodstack-private; do
  aws_cli s3api head-bucket --bucket "$bucket" > /dev/null 2>&1 || aws_cli s3 mb "s3://$bucket" > /dev/null
  aws_cli s3api put-bucket-cors --bucket "$bucket" --cors-configuration file:///state/cors.json
done
aws_cli s3api put-bucket-policy --bucket foodstack-public --policy file:///state/public-read.json
info "Floci on http://localhost:4566: buckets foodstack-public (public) and foodstack-private"
