@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title DevHub 一键启动

REM ============================================
REM DevHub dev startup (cmd)
REM usage: double-click or run in cmd
REM stop: close the two log windows
REM ============================================

set "JAVA_HOME=D:\java21"
set "MVN_CMD=D:\springboot\Maven\apache-maven-3.9.11\bin\mvn.cmd"
set "PATH=D:\DockerDesktop\resources/bin;%JAVA_HOME%\bin;D:\springboot\Maven\apache-maven-3.9.11\bin;%PATH%"

echo [1/4] 启动中间件容器...
docker compose -f D:\docker\middleware\docker-compose.yml up -d mysql redis milvus etcd minio
if errorlevel 1 (
    echo 中间件启动失败，请确认 Docker Desktop 正在运行
    pause
    exit /b 1
)

echo [2/4] 停掉占用 8080 的应用容器...
docker stop devhub-app 2>nul

echo [3/4] 读取 API Key 并构建后端...
set "OPENAI_API_KEY="
set "DEEPSEEK_API_KEY="
set "LLM_PRIMARY="
for /f "usebackq tokens=1,* delims==" %%a in ("D:\docker\middleware\.env") do (
    if /i "%%a"=="OPENAI_API_KEY" set "OPENAI_API_KEY=%%b"
    if /i "%%a"=="DEEPSEEK_API_KEY" set "DEEPSEEK_API_KEY=%%b"
    if /i "%%a"=="LLM_PRIMARY" set "LLM_PRIMARY=%%b"
)
if "!OPENAI_API_KEY!"=="" (
    echo 警告：未从 .env 读到 OPENAI_API_KEY，AI 问答不可用（其余功能正常）
    set "OPENAI_API_KEY=empty"
)
echo Key 前 6 位: !OPENAI_API_KEY:~0,6!...
if not "!DEEPSEEK_API_KEY!"=="" echo DeepSeek Key 前 6 位: !DEEPSEEK_API_KEY:~0,6!...
if not "!LLM_PRIMARY!"=="" (echo 对话主模型: !LLM_PRIMARY!) else (echo 对话主模型: glm-4-flash（默认）)

cd /d D:\kaifa\javaproject\project-devhub\devhub
call "%MVN_CMD%" -q package -DskipTests
if errorlevel 1 (
    echo 后端构建失败，请检查上方报错
    pause
    exit /b 1
)

echo [4/4] 启动后端与前端（各开一个日志窗口）...

set "BKEY=!OPENAI_API_KEY!"

REM 仅在 .env 配置了值时透传（空值传给 Spring 会覆盖默认值导致主模型解析失败）
set "EXTRA_ENV="
if not "!DEEPSEEK_API_KEY!"=="" set "EXTRA_ENV=!EXTRA_ENV!set DEEPSEEK_API_KEY=!DEEPSEEK_API_KEY!&& "
if not "!LLM_PRIMARY!"=="" set "EXTRA_ENV=!EXTRA_ENV!set LLM_PRIMARY=!LLM_PRIMARY!&& "

start "DevHub-Backend-8080" cmd /k "cd /d D:\kaifa\javaproject\project-devhub\devhub && set MYSQL_HOST=localhost&& set MYSQL_PORT=3307&& set MYSQL_DB=devhub&& set REDIS_HOST=localhost&& set MILVUS_HOST=localhost&& set OPENAI_API_KEY=%BKEY%&& set APP_ADMIN_TOKEN=mvp-token-2026&& %EXTRA_ENV%D:\java21\bin\java -jar target\devhub-server-0.1.0.jar"

start "DevHub-Frontend-5173" cmd /k "cd /d D:\kaifa\javaproject\project-devhub\devhub-web && if not exist node_modules npm install --no-fund --no-audit && npm run dev"

echo.
echo ============================================
echo   启动中：等约 30 秒
echo   前端入口:  http://localhost:5173
echo   后端健康:  http://localhost:8080/actuator/health
echo   登录账号:  admin / admin123
echo   停止方式:  关闭弹出的两个日志窗口
echo ============================================
echo 本窗口 10 秒后自动关闭
timeout /t 10 >nul
