{{/* A stateless front-end Deployment with Service, ServiceAccount, HPA and PDB (web, backoffice). */}}
{{- define "foodstack.frontend" -}}
{{- $root := index . 0 -}}{{- $name := index . 1 -}}{{- $port := index . 2 -}}{{- $env := index . 3 -}}{{- $health := index . 4 -}}{{- $uid := index . 5 -}}
{{- $v := index $root.Values $name -}}
{{- $full := printf "%s-%s" (include "foodstack.fullname" $root) $name -}}
{{- $labels := dict "app.kubernetes.io/name" (printf "foodstack-%s" $name) "app.kubernetes.io/instance" $root.Release.Name -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ $full }}
  labels: {{- include "foodstack.labels" $root | nindent 4 }}
automountServiceAccountToken: false
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $full }}
  labels: {{- include "foodstack.labels" $root | nindent 4 }}
spec:
  selector: {{- toYaml $labels | nindent 4 }}
  ports:
    - { name: http, port: {{ $port }}, targetPort: http, appProtocol: http }
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $full }}
  labels: {{- include "foodstack.labels" $root | nindent 4 }}
spec:
  {{- if not $v.autoscaling.enabled }}
  replicas: {{ $v.replicas }}
  {{- end }}
  revisionHistoryLimit: 3
  selector:
    matchLabels: {{- toYaml $labels | nindent 6 }}
  strategy:
    rollingUpdate: { maxSurge: 25%, maxUnavailable: 0 }
  template:
    metadata:
      labels:
        {{- toYaml $labels | nindent 8 }}
        {{- include "foodstack.podMeshLabels" $root | nindent 8 }}
    spec:
      serviceAccountName: {{ $full }}
      automountServiceAccountToken: false
      {{- with $root.Values.image.pullSecrets }}
      imagePullSecrets: {{- toYaml . | nindent 8 }}
      {{- end }}
      securityContext:
        runAsNonRoot: true
        runAsUser: {{ $uid }}
        seccompProfile: { type: RuntimeDefault }
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels: {{- toYaml $labels | nindent 14 }}
      containers:
        - name: {{ $name }}
          image: {{ include "foodstack.image" (list $root $v.image) }}
          imagePullPolicy: {{ $root.Values.image.pullPolicy }}
          ports:
            - { name: http, containerPort: {{ $port }} }
          env:
            {{- range $k, $val := merge (deepCopy $env) $v.env }}
            - { name: {{ $k }}, value: {{ $val | quote }} }
            {{- end }}
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: { drop: [ALL] }
          resources: {{- toYaml $v.resources | nindent 12 }}
          readinessProbe:
            httpGet: { path: {{ $health }}, port: http }
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: {{ $health }}, port: http }
            periodSeconds: 10
            failureThreshold: 3
{{- if $v.autoscaling.enabled }}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ $full }}
  labels: {{- include "foodstack.labels" $root | nindent 4 }}
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: {{ $full }} }
  minReplicas: {{ $v.autoscaling.min }}
  maxReplicas: {{ $v.autoscaling.max }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: {{ $v.autoscaling.targetCPU }} }
{{- end }}
{{- if $v.pdb }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ $full }}
  labels: {{- include "foodstack.labels" $root | nindent 4 }}
spec:
  minAvailable: {{ $v.pdb.minAvailable }}
  selector:
    matchLabels: {{- toYaml $labels | nindent 6 }}
{{- end }}
{{- end -}}
