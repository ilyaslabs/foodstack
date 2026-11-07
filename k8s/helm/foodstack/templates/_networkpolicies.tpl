{{- define "foodstack.np.gateways" -}}
- podSelector:
    matchExpressions:
      - key: gateway.networking.k8s.io/gateway-name
        operator: In
        values: [{{ include "foodstack.gatewayName" . }}, {{ include "foodstack.internalGatewayName" . }}, {{ include "foodstack.waypointName" . }}]
{{- end }}
{{- define "foodstack.np.mesh" -}}
{{- if .Values.mesh.enabled }}
- ports: [{ port: 15008, protocol: TCP }]
- from: [{ ipBlock: { cidr: 169.254.7.127/32 } }]
{{- end }}
{{- end }}
{{- define "foodstack.np.pods" -}}
{{- $root := index . 0 -}}
- podSelector:
    matchExpressions:
      - { key: app.kubernetes.io/instance, operator: In, values: [{{ $root.Release.Name }}] }
      - { key: app.kubernetes.io/name, operator: In, values: {{ index . 1 | toJson }} }
{{- end }}
