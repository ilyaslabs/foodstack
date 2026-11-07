{{- define "obs.name" -}}{{ .Values.namePrefix }}{{- end -}}

{{- define "obs.labels" -}}
app.kubernetes.io/part-of: foodstack-observability
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* Selector labels of one component: (list $ "prometheus") */}}
{{- define "obs.selector" -}}
app.kubernetes.io/name: {{ index . 1 }}
app.kubernetes.io/instance: {{ (index . 0).Release.Name }}
{{- end -}}

{{/* A PersistentVolumeClaim: (list $ "loki" "20Gi") */}}
{{- define "obs.pvc" -}}
{{- $ := index . 0 }}
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: {{ include "obs.name" $ }}-{{ index . 1 }}
  labels: {{- include "obs.labels" $ | nindent 4 }}
  annotations: { helm.sh/resource-policy: keep }
spec:
  accessModes: [ReadWriteOnce]
  {{- with $.Values.storageClass }}
  storageClassName: {{ . }}
  {{- end }}
  resources: { requests: { storage: {{ index . 2 }} } }
{{- end -}}

{{/* Pod security shared by every component. */}}
{{- define "obs.podSecurity" -}}
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001
  seccompProfile: { type: RuntimeDefault }
{{- end -}}

{{- define "obs.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: [ALL] }
{{- end -}}
