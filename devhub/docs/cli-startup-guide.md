# DevHub 纯命令行启动指南（不用 IDEA）

> ⚠️ 重要修正（2026-10-18 实测）：`vite.config.js` 代理目标已改为 **8080**（本地后端）。此前写 8081（Docker 端口）导致本地启动时代理 502——`start-dev.sh` 会先停 devhub-app 容器释放 8080，本地后端必须占 8080，代理才通。Docker 交付模式（8082）不受影响（nginx 有自己的代理配置）。

> 前提：JDK 21（`D:\java21`）、Maven 3.9（`D:\springboot\Maven\apache-maven-3.9.11`）、Node 24 已就位。中间件继续跑 Docker。
> 三条路径按需选：**A. 一键脚本（推荐）** / B. 手动三条命令 / C. 保持 Docker 全容器交付模式。

---

## 路径 A：一键启动脚本（推荐日常开发用）

创建一个脚本 `D:\kaifa\javaproject\devhub\start-dev.sh`：

```bash
#!/bin/bash
set -e
export JAVA_HOME=/d/java21
export PATH="/d/springboot/Maven/apache-maven-3.9.11/bin:/d/DockerDesktop/resources/bin:$PATH"

echo "==> 1/4 中间件容器"
docker compose -f /d/docker/middleware/docker-compose.yml up -d mysql redis milvus etcd minio

echo "==> 2/4 停掉抢 8080 的应用容器"
docker stop agent-app devhub-app 2>/dev/null || true

echo "==> 3/4 后端（后台运行，日志写 build/devhub.log）"
cd /d/kaifa/javaproject/devhub
mvn -q package -DskipTests
MYSQL_HOST=localhost MYSQL_PORT=3307 MYSQL_DB=devhub \
REDIS_HOST=localhost MILVUS_HOST=localhost \
OPENAI_API_KEY=$(grep '^OPENAI_API_KEY' /d/docker/middleware/.env | cut -d= -f2) \
APP_ADMIN_TOKEN=mvp-token-2026 \
nohup $JAVA_HOME/bin/java -jar target/devhub-server-0.1.0.jar > build/devhub.log 2>&1 &
echo "   后端 PID=$! → 日志 tail -f build/devhub.log"

echo "==> 4/4 前端 Vite（后台运行，端口 5173）"
cd /d/kaifa/javaproject/devhub-web
[ -d node_modules ] || npm install --no-fund --no-audit
nohup npm run dev > .vite-dev.log 2>&1 &
echo "   前端 PID=$! → 日志 tail -f .vite-dev.log"
echo ""
echo "✅ 等待约 30 秒后访问 http://localhost:5173（admin / admin123）"
```

使用：

```bash
mkdir -p D:/kaifa/javaproject/devhub/build
# 把上面内容存成 start-dev.sh 后：
bash D:/kaifa/javaproject/devhub/start-dev.sh

# 看日志
tail -f D:/kaifa/javaproject/devhub/build/devhub.log      # 后端
tail -f D:/kaifa/javaproject/devhub-web/.vite-dev.log     # 前端

# 停止
kill $(ps | grep devhub-server | awk '{print $1}') 2>/dev/null   # 停后端
kill $(ps | grep vite | awk '{print $1}') 2>/dev/null            # 停前端
```

---

## 路径 B：手动三条命令（理解每一步在做什么）

### 1. 中间件 + 释放 8080

```bash
export PATH="/d/DockerDesktop/resources/bin:$PATH"
docker compose -f /d/docker/middleware/docker-compose.yml up -d mysql redis milvus etcd minio
docker stop agent-app devhub-app 2>/dev/null    # IDEA/本地后端要用 8080
```

### 2. 后端：打包 → 运行

```bash
cd /d/kaifa/javaproject/devhub
export JAVA_HOME=/d/java21
export PATH="/d/springboot/Maven/apache-maven-3.9.11/bin:$PATH"

mvn package -DskipTests        # 显式确认 BUILD SUCCESS

MYSQL_HOST=localhost MYSQL_PORT=3307 MYSQL_DB=devhub \
REDIS_HOST=localhost MILVUS_HOST=localhost \
OPENAI_API_KEY=你的智谱Key APP_ADMIN_TOKEN=mvp-token-2026 \
java -jar target/devhub-server-0.1.0.jar
```

看到 `Started DevHubApplication` 即成功（Ctrl+C 停止）。
后台运行：命令尾加 `> build/devhub.log 2>&1 &`。

> **为什么 3307**：MySQL 容器把 3306 映射到宿主机 3307。Key 值看 `D:\docker\middleware\.env`。

### 3. 前端：Vite dev server

```bash
cd /d/kaifa/javaproject/devhub-web
npm install        # 仅首次
npm run dev        # 输出 http://localhost:5173
```

浏览器开 **http://localhost:5173**，admin/admin123。`/api` 已代理到 8080，改代码热更新。

---

## 路径 C：Docker 交付模式（不做本地开发时）

什么都不改，维持现状：

```bash
export PATH="/d/DockerDesktop/resources/bin:$PATH"
docker compose -f /d/docker/middleware/docker-compose.yml up -d
# http://localhost:8082  Vue3 交付版（nginx + 后端）
# http://localhost:8081  单文件版
# http://localhost:8080  原 agent-platform
```

---

## 三种模式对比

| | A 一键脚本 | B 手动 | C Docker 交付 |
|---|---|---|---|
| 适用 | 日常开发 | 学习/排错 | 演示交付 |
| 后端 | 本地 jar 后台跑 | 前台跑（Ctrl+C 停）| 容器 8081 |
| 前端 | Vite 热更新 | Vite 热更新 | nginx 静态 8082 |
| 断点 | 加 `-agentlib:jdwp=...` | 同左 | 不支持 |
| 常驻 | 手动 kill | 关终端即停 | restart: unless-stopped |

**断点调试（不装 IDEA 也可以）**：后端运行命令换成

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar target/devhub-server-0.1.0.jar
```

然后用任意支持远程调试的编辑器（VS Code 装 Java 扩展包）Attach 到 5005。

---

## 停止与恢复速查

```bash
# 停本地前后端（Git Bash）
ps | grep -E "devhub-server|vite" | awk '{print $1}' | xargs -r kill

# 回到演示模式
docker compose -f /d/docker/middleware/docker-compose.yml up -d devhub devhub-web
```
