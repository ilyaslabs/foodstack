{{- define "foodstack.parentRefs" -}}
{{- $root := index . 0 -}}
- name: {{ include "foodstack.gatewayName" $root }}
  {{- if eq $root.Values.scheme "https" }}
  {{- range $i, $_ := fromJsonArray (include "foodstack.hostnames" $root) }}
  {{- if $i }}
- name: {{ include "foodstack.gatewayName" $root }}
  {{- end }}
  sectionName: https-{{ $i }}
  {{- end }}
  {{- else }}
  sectionName: http
  {{- end }}
{{- end }}

{{- define "foodstack.securityHeaders" -}}
- type: ResponseHeaderModifier
  responseHeaderModifier:
    set:
      {{- if eq .Values.scheme "https" }}
      - { name: Strict-Transport-Security, value: "max-age=31536000; includeSubDomains" }
      {{- end }}
      - { name: X-Content-Type-Options, value: nosniff }
      - { name: Referrer-Policy, value: strict-origin-when-cross-origin }
      - { name: X-Frame-Options, value: DENY }
{{- end }}
