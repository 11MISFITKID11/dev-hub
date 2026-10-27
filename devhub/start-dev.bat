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
set "PATH=D:\DockerDesktop\resources\bin;%JAVA_HOME%\bin;D:\springboot\Maven\apache-maven-3.9.11\bin;%PATH%"

echo [1/5] 启动中间件容器...
docker compose -f D:\docker\middleware\docker-compose.yml up -d mysql redis milvus etcd minio
if errorlevel 1 (
    echo 中间件启动失败，请确认 Docker Desktop 正在运行
    pause
    exit /b 1
)

echo [2/5] 清理 8080 占用（应用容器与残留 java 进程）...
docker stop devhub-app 2>nul
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
    echo   结束占用进程 PID %%p
    taskkill /F /PID %%p >nul 2>&1
)

echo [3/5] 读取 API Key 并构建后端...
set "OPENAI_API_KEY="
set "DEEPSEEK_API_KEY="
set "LLM_PRIMARY="
for /f "usebackq tokens=1,* delims==" %%a in ("D:\docker\middleware\.env") do (
    if /i "%%a"=="OPENAI_API_KEY" set "OPENAI_API_KEY=%%b"
    if /i "%%a"=="DEEPSEEK_API_KEY" set "DEEPSEEK_API_KEY=%%b"
    if /i "%%a"=="LLM_PRIMARY" set "LLM_PRIMARY=%%b"
    if /i "%%a"=="AGENT_RAG_RERANK_PROVIDER" set "AGENT_RAG_RERANK_PROVIDER=%%b"
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

echo [4/5] 等待 Milvus 就绪（容器刚启动时代理需 1-2 分钟，未就绪时后端会启动失败）...
set /a MILVUS_TRIES=0
:wait_milvus
powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient('localhost',19530);$c.Close();exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto milvus_ok
set /a MILVUS_TRIES+=1
if !MILVUS_TRIES! GEQ 40 (
    echo   [警告] Milvus 120 秒未就绪，仍继续启动后端（若失败请稍后重跑本脚本）
    goto milvus_done
)
ping -n 4 127.0.0.1 >nul
goto wait_milvus
:milvus_ok
echo   Milvus 已就绪
:milvus_done

echo [5/5] 启动后端与前端（各开一个日志窗口）...

set "BKEY=!OPENAI_API_KEY!"

REM 仅在 .env 配置了值时透传（空值传给 Spring 会覆盖默认值导致主模型解析失败）
set "EXTRA_ENV="
if not "!DEEPSEEK_API_KEY!"=="" set "EXTRA_ENV=!EXTRA_ENV!set DEEPSEEK_API_KEY=!DEEPSEEK_API_KEY!&& "
if not "!LLM_PRIMARY!"=="" set "EXTRA_ENV=!EXTRA_ENV!set LLM_PRIMARY=!LLM_PRIMARY!&& "
if not "!AGENT_RAG_RERANK_PROVIDER!"=="" set "EXTRA_ENV=!EXTRA_ENV!set AGENT_RAG_RERANK_PROVIDER=!AGENT_RAG_RERANK_PROVIDER!&& "

start "DevHub-Backend-8080" cmd /k "cd /d D:\kaifa\javaproject\project-devhub\devhub && set MYSQL_HOST=localhost&& set MYSQL_PORT=3307&& set MYSQL_DB=devhub&& set REDIS_HOST=localhost&& set MILVUS_HOST=localhost&& set OPENAI_API_KEY=%BKEY%&& set APP_ADMIN_TOKEN=mvp-token-2026&& %EXTRA_ENV%D:\java21\bin\java -jar target\devhub-server-0.1.0.jar"

start "DevHub-Frontend-5173" cmd /k "cd /d D:\kaifa\javaproject\project-devhub\devhub-web && if not exist node_modules npm install --no-fund --no-audit && npm run dev"

echo   等待后端健康检查（最多 90 秒）...
set /a BE_TRIES=0
:wait_backend
ping -n 4 127.0.0.1 >nul
powershell -NoProfile -Command "try{(Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health -TimeoutSec 2).StatusCode;exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto backend_ok
set /a BE_TRIES+=1
if !BE_TRIES! GEQ 30 (
    echo   [警告] 后端 90 秒未就绪：请查看 DevHub-Backend-8080 窗口的报错，处理后重跑本脚本
    goto backend_done
)
goto wait_backend
:backend_ok
echo   后端已就绪
:backend_done

echo.
echo ============================================
echo   前端入口:  http://localhost:5173
echo   后端健康:  http://localhost:8080/actuator/health
echo   登录账号:  admin / admin123
echo   停止方式:  关闭弹出的两个日志窗口
echo ============================================
echo 本窗口 10 秒后自动关闭
ping -n 11 127.0.0.1 >nul
