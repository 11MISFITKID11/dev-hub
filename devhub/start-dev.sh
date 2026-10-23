#!/bin/bash
set -e
export JAVA_HOME=/d/java21
export PATH="/d/springboot/Maven/apache-maven-3.9.11/bin:/d/DockerDesktop/resources/bin:$PATH"

echo "==> 1/4 中间件容器"
docker compose -f /d/docker/middleware/docker-compose.yml up -d mysql redis milvus etcd minio

echo "==> 2/4 停掉抢 8080 的应用容器"
docker stop devhub-app 2>/dev/null || true

echo "==> 3/4 后端构建并后台运行"
cd /d/kaifa/javaproject/project-devhub/devhub
mvn -q package -DskipTests
# DeepSeek 切换：仅在 .env 配置了值时导出（空值会覆盖 Spring 默认主模型）
DEEPSEEK_API_KEY=$(grep '^DEEPSEEK_API_KEY=' /d/docker/middleware/.env | head -1 | cut -d= -f2 || true)
[ -n "$DEEPSEEK_API_KEY" ] && export DEEPSEEK_API_KEY
LLM_PRIMARY=$(grep '^LLM_PRIMARY=' /d/docker/middleware/.env | head -1 | cut -d= -f2 || true)
[ -n "$LLM_PRIMARY" ] && export LLM_PRIMARY
echo "   对话主模型: ${LLM_PRIMARY:-glm-4-flash（默认）}"
MYSQL_HOST=localhost MYSQL_PORT=3307 MYSQL_DB=devhub \
REDIS_HOST=localhost MILVUS_HOST=localhost \
OPENAI_API_KEY=$(grep '^OPENAI_API_KEY' /d/docker/middleware/.env | cut -d= -f2) \
APP_ADMIN_TOKEN=mvp-token-2026 \
nohup $JAVA_HOME/bin/java -jar target/devhub-server-0.1.0.jar > build/devhub.log 2>&1 &
echo "   后端 PID=$!  日志: tail -f build/devhub.log"

echo "==> 4/4 前端 Vite 后台运行"
cd /d/kaifa/javaproject/project-devhub/devhub-web
[ -d node_modules ] || npm install --no-fund --no-audit
nohup npm run dev > .vite-dev.log 2>&1 &
echo "   前端 PID=$!  日志: tail -f .vite-dev.log"
echo ""
echo "==> 等 30 秒后访问 http://localhost:5173 (admin/admin123)"
