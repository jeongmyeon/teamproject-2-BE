#!/bin/bash

# Monitoring Stack Deployment Script for K3s
# This script deploys Prometheus and Grafana to the biddy-monitoring namespace

set -e

echo "================================================"
echo "Biddy Monitoring Stack Deployment"
echo "================================================"
echo ""
echo "⚠️  중요: 배포 전에 Worker 노드에 Swap을 추가했는지 확인하세요!"
echo ""
echo "Swap 추가 방법 (Worker 노드에서 실행):"
echo "  sudo fallocate -l 2G /swapfile"
echo "  sudo chmod 600 /swapfile"
echo "  sudo mkswap /swapfile"
echo "  sudo swapon /swapfile"
echo "  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab"
echo ""
read -p "Swap 추가 완료했습니까? (y/n): " confirm
if [[ ! $confirm =~ ^[Yy]$ ]]; then
    echo "❌ Swap을 먼저 추가해주세요."
    exit 1
fi
echo ""

# Check if kubectl is configured
if ! kubectl cluster-info &> /dev/null; then
    echo "❌ Error: kubectl is not configured or cluster is not accessible"
    echo "Please ensure you're connected to the K3s cluster"
    exit 1
fi

echo "✅ Connected to cluster"
echo ""

# 1. Create namespace
echo "📦 Creating biddy-monitoring namespace..."
kubectl apply -f namespace.yaml
echo ""

# Wait for namespace to be ready
sleep 2

# 2. Deploy Alert Rules
echo "🚨 Deploying Alert Rules..."
kubectl apply -f alerting-rules.yaml
echo ""

# 3. Deploy exporters
echo "🖥️  Deploying Node Exporter..."
kubectl apply -f node-exporter.yaml
echo ""

echo "☸️  Deploying kube-state-metrics..."
kubectl apply -f kube-state-metrics.yaml
echo ""

# 4. Deploy Prometheus
echo "📊 Deploying Prometheus..."
kubectl apply -f prometheus.yaml
kubectl rollout restart deployment/prometheus -n biddy-monitoring
echo ""

# Wait for Prometheus to start creating resources
sleep 5

# Check Prometheus PVC status
echo "🔍 Checking Prometheus PVC status..."
kubectl get pvc -n biddy-monitoring prometheus-pvc
echo ""

# 5. Deploy Grafana
echo "📈 Deploying Grafana..."
kubectl apply -f grafana.yaml
echo ""

# Wait for Grafana to start creating resources
sleep 5

# Check Grafana PVC status
echo "🔍 Checking Grafana PVC status..."
kubectl get pvc -n biddy-monitoring grafana-pvc
echo ""

echo "================================================"
echo "Deployment Status"
echo "================================================"
echo ""

# Show all resources in monitoring namespace
echo "📋 All resources in biddy-monitoring namespace:"
kubectl get all -n biddy-monitoring -o wide
echo ""

echo "💾 Storage:"
kubectl get pvc -n biddy-monitoring
echo ""

echo "🖥️  Pod 배포 위치 확인:"
kubectl get pods -n biddy-monitoring -o custom-columns=NAME:.metadata.name,NODE:.spec.nodeName,STATUS:.status.phase
echo ""

echo "================================================"
echo "Monitoring Stack Deployment Completed!"
echo "================================================"
echo ""
echo "📝 Next Steps:"
echo ""
echo "1. Check Prometheus status:"
echo "   kubectl get pods -n biddy-monitoring -l app=prometheus"
echo ""
echo "2. Check Grafana status:"
echo "   kubectl get pods -n biddy-monitoring -l app=grafana"
echo ""
echo "3. Access Prometheus (port-forward):"
echo "   kubectl port-forward -n biddy-monitoring svc/prometheus 9090:9090"
echo "   Then open: http://localhost:9090"
echo ""
echo "4. Access Grafana (LoadBalancer):"
echo "   kubectl get svc -n biddy-monitoring grafana"
echo "   Username: admin"
echo "   Password: admin1234"
echo ""
echo "5. Monitor pod startup:"
echo "   kubectl logs -n biddy-monitoring -l app=prometheus -f"
echo "   kubectl logs -n biddy-monitoring -l app=grafana -f"
echo ""
echo "📊 배포 완료 정보:"
echo ""
echo "   🎯 작업 위치: Master 노드 (kubectl 실행)"
echo "   🖥️  실행 위치: Worker 노드 (Pod 배포)"
echo ""
echo "   💾 예상 메모리 사용:"
echo "   - Worker: 5.0GB → ~6.5GB / 7.6GB (85%)"
echo "   - Master: 2.4GB 유지 (65%, 변화 없음)"
echo ""
echo "   ✅ Swap 2GB 활성화 시 안정적으로 운영 가능!"
echo ""
