{{/* Every FoodStack module, in start order. */}}
{{- define "foodstack.modules" -}}
identity settings media geo store search promotion cart payment order rider dispatch notification ledger review
{{- end -}}

{{/* Which module owns which public path prefix: the same map as ModuleRoutes in the integration
     tests and the server's controllers (TDD 5.2). */}}
{{- define "foodstack.prefixes" -}}
identity: [/api/v1/auth, /api/v1/me, /api/v1/users, /api/v1/audit, /oauth2, /.well-known]
settings: [/api/v1/settings]
media: [/api/v1/media]
geo: [/api/v1/geo, /api/v1/zones]
store: [/api/v1/stores, /api/v1/brands]
search: [/api/v1/search]
promotion: [/api/v1/promotions, /api/v1/coupons]
cart: [/api/v1/carts]
payment: [/api/v1/payments, /api/v1/payment-webhooks]
order: [/api/v1/orders]
rider: [/api/v1/riders, /api/v1/rider]
dispatch: [/api/v1/deliveries, /api/v1/dispatch]
notification: [/api/v1/notifications, /api/v1/devices]
ledger: [/api/v1/ledger, /api/v1/payouts]
review: [/api/v1/reviews]
{{- end -}}

{{/* Live streams (SSE): routed without a request timeout. Regular expressions. */}}
{{- define "foodstack.streams" -}}
order: ['/api/v1/orders/[^/]+/stream', '/api/v1/orders/stores/[^/]+/stream']
dispatch: ['/api/v1/dispatch/rider/stream', '/api/v1/dispatch/live/stream']
rider: ['/api/v1/riders/live/stream', '/api/v1/riders/tracking/[^/]+/stream']
{{- end -}}

{{- define "foodstack.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 40 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 40 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "foodstack.labels" -}}
app.kubernetes.io/part-of: foodstack
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/* Pods join the ambient mesh one by one, so the namespace needs no label. */}}
{{- define "foodstack.podMeshLabels" -}}
{{- if .Values.mesh.enabled }}
istio.io/dataplane-mode: ambient
{{- end }}
{{- end -}}

{{- define "foodstack.image" -}}
{{- $root := index . 0 -}}{{- $name := index . 1 -}}
{{- printf "%s/%s/%s:%s" $root.Values.image.registry $root.Values.image.repository $name (default $root.Chart.AppVersion $root.Values.image.tag) -}}
{{- end -}}

{{- define "foodstack.secretName" -}}
{{- default (printf "%s-secrets" (include "foodstack.fullname" .)) .Values.secrets.existingSecret -}}
{{- end -}}

{{- define "foodstack.datastoreSecretName" -}}
{{- default (printf "%s-datastores" (include "foodstack.fullname" .)) .Values.datastores.existingSecret -}}
{{- end -}}

{{- define "foodstack.url" -}}
{{- $root := index . 0 -}}{{- $host := index . 1 -}}
{{- printf "%s://%s" $root.Values.scheme $host -}}
{{- end -}}

{{/* The hostnames, each once. */}}
{{- define "foodstack.hostnames" -}}
{{- list .Values.hosts.web .Values.hosts.admin .Values.hosts.api | uniq | toJson -}}
{{- end -}}

{{/*
  The server Deployments for the chosen topology, as a YAML map:
    <name>: { modules: [...], replicas, autoscaling, pdb, resources, kafkaConcurrency, env, ... }
  Fails with a clear message when a module is unknown, listed twice or not run anywhere.
*/}}
{{- define "foodstack.deployments" -}}
{{- $all := splitList " " (include "foodstack.modules" .) -}}
{{- $groups := dict -}}
{{- if eq .Values.topology "monolith" -}}
{{- $_ := set $groups "core" (dict "modules" $all) -}}
{{- else if eq .Values.topology "split" -}}
{{- $own := list "order" "dispatch" "search" -}}
{{- $core := list -}}
{{- range $all }}{{ if not (has . $own) }}{{ $core = append $core . }}{{ end }}{{ end -}}
{{- $_ := set $groups "core" (dict "modules" $core) -}}
{{- range $own }}{{ $_ := set $groups . (dict "modules" (list .)) }}{{ end -}}
{{- else if eq .Values.topology "microservices" -}}
{{- range $all }}{{ $_ := set $groups . (dict "modules" (list .)) }}{{ end -}}
{{- else if eq .Values.topology "custom" -}}
{{- if not .Values.server.deployments -}}
{{- fail "topology=custom needs server.deployments: a map of Deployment name to its modules" -}}
{{- end -}}
{{- range $name, $d := .Values.server.deployments }}{{ $_ := set $groups $name (deepCopy $d) }}{{ end -}}
{{- else -}}
{{- fail (printf "topology must be monolith, split, microservices or custom, not %q" .Values.topology) -}}
{{- end -}}
{{- /* checks */ -}}
{{- $seen := dict -}}
{{- range $name, $g := $groups -}}
{{- if not (regexMatch "^[a-z][a-z0-9-]{0,20}$" $name) -}}
{{- fail (printf "server Deployment name %q must be lower case letters, digits and dashes (at most 21)" $name) -}}
{{- end -}}
{{- if not $g.modules -}}{{- fail (printf "server Deployment %q lists no modules" $name) -}}{{- end -}}
{{- range $g.modules -}}
{{- if not (has . $all) -}}{{- fail (printf "server Deployment %q lists unknown module %q (known: %s)" $name . (join ", " $all)) -}}{{- end -}}
{{- if hasKey $seen . -}}{{- fail (printf "module %q is listed in both %q and %q" . (get $seen .) $name) -}}{{- end -}}
{{- $_ := set $seen . $name -}}
{{- end -}}
{{- end -}}
{{- range $all -}}
{{- if not (hasKey $seen .) -}}{{- fail (printf "module %q is not run by any server Deployment; every module is needed" .) -}}{{- end -}}
{{- end -}}
{{- /* sizing: defaults <- overrides.<name> <- custom entry */ -}}
{{- $out := dict -}}
{{- range $name, $g := $groups -}}
{{- $merged := deepCopy $.Values.server.defaults -}}
{{- $merged = mustMergeOverwrite $merged (deepCopy (default dict (get $.Values.server.overrides $name))) -}}
{{- $merged = mustMergeOverwrite $merged (deepCopy (omit $g "modules")) -}}
{{- $_ := set $merged "modules" $g.modules -}}
{{- $maxPods := int $merged.replicas -}}
{{- if $merged.autoscaling.enabled }}{{ $maxPods = max $maxPods (int $merged.autoscaling.max) | int }}{{ end -}}
{{- $partitions := int $.Values.datastores.kafka.topicPartitions -}}
{{- $concurrency := min (int $merged.kafkaConcurrency) (div $partitions (max 1 $maxPods)) | int -}}
{{- $_ := set $merged "kafkaConcurrency" (max 1 $concurrency) -}}
{{- $_ := set $merged "maxPods" $maxPods -}}
{{- $_ := set $out $name $merged -}}
{{- end -}}
{{- toYaml $out -}}
{{- end -}}

{{/* The Deployment that runs a module locally. */}}
{{- define "foodstack.ownerOf" -}}
{{- $deployments := index . 0 -}}{{- $module := index . 1 -}}
{{- range $name, $d := $deployments }}{{ if has $module $d.modules }}{{ $name }}{{ end }}{{ end -}}
{{- end -}}

{{- define "foodstack.serverName" -}}
{{- $root := index . 0 -}}{{- $name := index . 1 -}}
{{- printf "%s-%s" (include "foodstack.fullname" $root) $name -}}
{{- end -}}

{{- define "foodstack.serverHost" -}}
{{- $root := index . 0 -}}{{- $name := index . 1 -}}
{{- printf "%s.%s.svc.cluster.local" (include "foodstack.serverName" (list $root $name)) $root.Release.Namespace -}}
{{- end -}}

{{/* Service accounts of every server Deployment, as SPIFFE-style principals for Istio policies. */}}
{{- define "foodstack.serverPrincipals" -}}
{{- $root := . -}}
{{- $deployments := include "foodstack.deployments" . | fromYaml -}}
{{- $out := list -}}
{{- range $name, $_ := $deployments -}}
{{- $out = append $out (printf "*/ns/%s/sa/%s" $root.Release.Namespace (include "foodstack.serverName" (list $root $name))) -}}
{{- end -}}
{{- $out = append $out (printf "*/ns/%s/sa/%s-migrations" $root.Release.Namespace (include "foodstack.fullname" $root)) -}}
{{- toJson $out -}}
{{- end -}}

{{- define "foodstack.principal" -}}
{{- $root := index . 0 -}}{{- $sa := index . 1 -}}
{{- printf "*/ns/%s/sa/%s" $root.Release.Namespace $sa -}}
{{- end -}}

{{/* Istio names the Deployment and service account of a Gateway <name>-<className>. */}}
{{- define "foodstack.gatewayName" -}}{{ include "foodstack.fullname" . }}{{- end -}}
{{- define "foodstack.internalGatewayName" -}}{{ include "foodstack.fullname" . }}-internal{{- end -}}
{{- define "foodstack.waypointName" -}}{{ include "foodstack.fullname" . }}-waypoint{{- end -}}
{{- define "foodstack.gatewaySa" -}}{{ include "foodstack.gatewayName" . }}-{{ .Values.gateway.className }}{{- end -}}
{{- define "foodstack.internalGatewaySa" -}}{{ include "foodstack.internalGatewayName" . }}-{{ .Values.gateway.className }}{{- end -}}

{{/* Datastore environment shared by the server and the migrations Job. */}}
{{- define "foodstack.datastoreEnv" -}}
{{- $ds := include "foodstack.datastoreSecretName" . -}}
- name: MONGODB_URI
  valueFrom: { secretKeyRef: { name: {{ $ds }}, key: MONGODB_URI } }
- name: REDIS_URL
  valueFrom: { secretKeyRef: { name: {{ $ds }}, key: REDIS_URL } }
- name: ELASTICSEARCH_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ $ds }}, key: ELASTICSEARCH_PASSWORD } }
- name: S3_ACCESS_KEY
  valueFrom: { secretKeyRef: { name: {{ $ds }}, key: S3_ACCESS_KEY } }
- name: S3_SECRET_KEY
  valueFrom: { secretKeyRef: { name: {{ $ds }}, key: S3_SECRET_KEY } }
{{- $s := include "foodstack.secretName" . }}
{{- range list "FOODSTACK_SIGNING_KEYS" "FOODSTACK_PII_KEY" "FOODSTACK_INTERNAL_TOKEN" "FOODSTACK_SERVICE_CLIENT_SECRET" "FOODSTACK_ADMIN_PASSWORD" }}
- name: {{ . }}
  valueFrom: { secretKeyRef: { name: {{ $s }}, key: {{ . }} } }
{{- end }}
{{- end -}}

{{/* Plain settings shared by the server and the migrations Job (application.yaml reads them). */}}
{{- define "foodstack.serverConfig" -}}
{{- $ds := .Values.datastores -}}
FOODSTACK_ENVIRONMENT: {{ ternary "development" "production" .Values.dev.enabled | quote }}
FOODSTACK_PUBLIC_URL: {{ include "foodstack.url" (list . .Values.hosts.api) | quote }}
FOODSTACK_WEB_URL: {{ include "foodstack.url" (list . .Values.hosts.web) | quote }}
FOODSTACK_ADMIN_URL: {{ include "foodstack.url" (list . .Values.hosts.admin) | quote }}
FOODSTACK_ADMIN_EMAIL: {{ .Values.secrets.adminEmail | quote }}
FOODSTACK_REFRESH_COOKIE_SECURE: {{ eq .Values.scheme "https" | quote }}
KAFKA_BOOTSTRAP: {{ ternary (printf "%s-kafka:9092" (include "foodstack.fullname" .)) $ds.kafka.bootstrap .Values.dev.enabled | quote }}
KAFKA_TOPIC_PARTITIONS: {{ $ds.kafka.topicPartitions | quote }}
KAFKA_REPLICATION_FACTOR: {{ ternary 1 $ds.kafka.replicationFactor .Values.dev.enabled | quote }}
ELASTICSEARCH_URIS: {{ ternary (printf "http://%s-elasticsearch:9200" (include "foodstack.fullname" .)) $ds.elasticsearch.uris .Values.dev.enabled | quote }}
ELASTICSEARCH_USERNAME: {{ $ds.elasticsearch.username | quote }}
SEARCH_REPLICAS: {{ ternary 0 $ds.elasticsearch.replicas .Values.dev.enabled | quote }}
FOODSTACK_MONGODB_SHARDING_ENABLED: {{ $ds.mongodb.sharding | quote }}
{{- if .Values.dev.enabled }}
S3_ENDPOINT: {{ printf "http://%s-storage:9000" (include "foodstack.fullname" .) | quote }}
S3_PUBLIC_ENDPOINT: {{ include "foodstack.url" (list . .Values.hosts.api) | quote }}
S3_CREATE_BUCKETS: "true"
SMTP_HOST: {{ printf "%s-mailpit" (include "foodstack.fullname" .) | quote }}
SMTP_PORT: "1025"
SMTP_FROM: no-reply@{{ .Values.hosts.web }}
{{- else }}
S3_ENDPOINT: {{ $ds.s3.endpoint | quote }}
S3_PUBLIC_ENDPOINT: {{ default (include "foodstack.url" (list . .Values.hosts.api)) $ds.s3.publicEndpoint | quote }}
S3_CREATE_BUCKETS: {{ $ds.s3.createBuckets | quote }}
{{- end }}
S3_PUBLIC_BUCKET: {{ $ds.s3.publicBucket | quote }}
S3_PRIVATE_BUCKET: {{ $ds.s3.privateBucket | quote }}
{{- range $k, $v := .Values.server.env }}
{{ $k }}: {{ $v | quote }}
{{- end }}
{{- end -}}

{{/* Secrets exist before the migrations hook Job runs, and survive helm uninstall. */}}
{{- define "foodstack.hookAnnotations" -}}
helm.sh/hook: pre-install,pre-upgrade
helm.sh/hook-weight: "-10"
helm.sh/hook-delete-policy: before-hook-creation
{{- end -}}

{{/* The migrations hook Job runs only against external datastores; in-cluster trial datastores
     start with the release, so there the server migrates at start (TDD 6.4: dev and tests). */}}
{{- define "foodstack.migrationsJob" -}}
{{- and .Values.migrations.enabled (not .Values.dev.enabled) -}}
{{- end -}}
