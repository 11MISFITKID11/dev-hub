#!/bin/bash
set -e
export JAVA_HOME=/d/java21
export PATH="/d/springboot/Maven/apache-maven-3.9.11/bin:/d/DockerDesktop/resources/bin:$PATH"

echo "==> 1/5 中间件容器"
docker compose -f /d/docker/middleware/docker-compose.yml up -d mysql redis milvus etcd minio

echo "==> 2/5 清理 8080 占用"
docker stop devhub-app 2>/dev/null || true
PID=$(netstat -ano | grep ":8080" | grep LISTENING | awk '{print $5}' | head -1)
[ -n "$PID" ] && taskkill //F //PID $PID || true

echo "==> 3/5 后端构建"
cd /d/kaifa/javaproject/project-devhub/devhub
mvn -q package -DskipTests
# DeepSeek 切换：仅在 .env 配置了值时导出（空值会覆盖 Spring 默认主模型）
DEEPSEEK_API_KEY=$(grep '^DEEPSEEK_API_KEY=' /d/docker/middleware/.env | head -1 | cut -d= -f2 | tr -d '\r')
[ -n "$DEEPSEEK_API_KEY" ] && export DEEPSEEK_API_KEY
LLM_PRIMARY=$(grep '^LLM_PRIMARY=' /d/docker/middleware/.env | head -1 | cut -d= -f2 | tr -d '\r')
[ -n "$LLM_PRIMARY" ] && export LLM_PRIMARY
AGENT_RAG_RERANK_PROVIDER=$(grep '^AGENT_RAG_RERANK_PROVIDER=' /d/docker/middleware/.env | head -1 | cut -d= -f2 | tr -d '\r')
[ -n "$AGENT_RAG_RERANK_PROVIDER" ] && export AGENT_RAG_RERANK_PROVIDER
echo "   对话主模型: ${LLM_PRIMARY:-glm-4-flash（默认）}"

echo "==> 4/5 等待 Milvus 就绪（刚启动时代理需 1-2 分钟，未就绪后端会启动失败）"
for i in $(seq 1 40); do
  if (echo > /dev/tcp/127.0.0.1/19530) >/dev/null 2>&1; then echo "   Milvus 已就绪"; break; fi
  if [ "$i" = 40 ]; then echo "   [警告] Milvus 120 秒未就绪，继续启动（失败请稍后重跑）"; fi
  sleep 3
done

MYSQL_HOST=localhost MYSQL_PORT=3307 MYSQL_DB=devhub \
REDIS_HOST=localhost MILVUS_HOST=localhost \
OPENAI_API_KEY=$(grep '^OPENAI_API_KEY' /d/docker/middleware/.env | cut -d= -f2 | tr -d '\r') \
APP_ADMIN_TOKEN=mvp-token-2026 \
nohup $JAVA_HOME/bin/java -jar target/devhub-server-0.1.0.jar > build/devhub.log 2>&1 &
echo "   后端 PID=$!  日志: tail -f build/devhub.log"

echo "==> 5/5 等待后端健康检查（最多 90 秒）"
for i in $(seq 1 30); do
  sleep 3
  if curl -s -m 2 http://localhost:8080/actuator/health | grep -q UP; then
    echo "   后端已就绪"; break
  fi
  if [ "$i" = 30 ]; then echo "   [警告] 后端未就绪：tail build/devhub.log 查看报错"; fi
done

cd /d/kaifa/javaproject/project-devhub/devhub-web
[ -d node_modules ] || npm install --no-fund --no-audit
nohup npm run dev > .vite-dev.log 2>&1 &
echo "   前端 PID=$!"
echo ""
echo "==> 访问 http://localhost:5173 (admin/admin123)"
