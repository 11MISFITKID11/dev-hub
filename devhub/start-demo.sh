#!/bin/bash
# MCP 演示一键启动：中间件 → 后端(AGENT_MCP_ENABLED=true) → 前端
# 与 start-dev.sh 的区别：开启 MCP 客户端，其余一致
set -e
export JAVA_HOME=/d/java21
export PATH="/d/springboot/Maven/apache-maven-3.9.11/bin:/d/DockerDesktop/resources/bin:$PATH"

echo "==> 1/4 中间件容器"
docker compose -f /d/docker/middleware/docker-compose.yml up -d mysql redis milvus etcd minio

echo "==> 2/4 停掉抢 8080 的应用容器与旧进程"
docker stop devhub-app devhub-web 2>/dev/null || true
PID=$(netstat -ano | grep ":8080" | grep LISTENING | awk '{print $5}' | head -1)
[ -n "$PID" ] && taskkill //F //PID $PID || true

echo "==> 3/4 清理演示前的语义缓存（避免旧答案回放干扰演示）"
docker exec agent-redis redis-cli --scan --pattern "agent:rag:semantic*" \
  | xargs -r docker exec -i agent-redis redis-cli DEL > /dev/null || true

echo "==> 4/4 后端（MCP 开启）+ 前端"
cd /d/kaifa/javaproject/project-devhub/devhub
mvn -q package -DskipTests
# 从 .env 读密钥（文件在仓库外，密钥永不入库）
OPENAI_API_KEY=$(grep '^OPENAI_API_KEY=' /d/docker/middleware/.env | head -1 | cut -d= -f2 | tr -d '\r')
DEEPSEEK_API_KEY=$(grep '^DEEPSEEK_API_KEY=' /d/docker/middleware/.env | head -1 | cut -d= -f2 | tr -d '\r')
LLM_PRIMARY=$(grep '^LLM_PRIMARY=' /d/docker/middleware/.env | head -1 | cut -d= -f2 | tr -d '\r')
export OPENAI_API_KEY DEEPSEEK_API_KEY LLM_PRIMARY
export AGENT_MCP_ENABLED=true
export MYSQL_HOST=localhost MYSQL_PORT=3307 MYSQL_DB=devhub REDIS_HOST=localhost MILVUS_HOST=localhost APP_ADMIN_TOKEN=mvp-token-2026
[ -n "$DEEPSEEK_API_KEY" ] || unset DEEPSEEK_API_KEY
[ -z "$LLM_PRIMARY" ] && unset LLM_PRIMARY
nohup $JAVA_HOME/bin/java -jar target/devhub-server-0.1.0.jar > build/devhub.log 2>&1 &
echo "   后端 PID=$!  主模型: ${LLM_PRIMARY:-glm-4-flash（默认）}  日志: tail -f build/devhub.log"

cd /d/kaifa/javaproject/project-devhub/devhub-web
[ -d node_modules ] || npm install --no-fund --no-audit
nohup npm run dev > .vite-dev.log 2>&1 &
echo "   前端 PID=$!"
echo ""
echo "==> 等 30 秒后访问 http://localhost:5173 (admin/admin123)"
echo "    演示问题：我的订单 SO-2024-1001 发货了吗？什么时候能到？"
echo "    验证点：思考步骤出现 mcp_demo-order_query_order；回答含 顺丰 SF1234567890"
echo "    MCP 状态：curl -s localhost:8080/api/v1/mcp/status -H \"Authorization: Bearer <token>\""
