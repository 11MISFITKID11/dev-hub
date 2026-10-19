# Arthas 诊断工具中文手册

来源：阿里巴巴开源项目 Arthas 官方文档（github.com/alibaba/arthas，Apache License 2.0 公开文档）。Arthas 是 Alibaba 开源的 Java 诊断工具，采用命令行交互模式，提供 dashboard、thread、jad、watch、trace 等命令来观测 JVM 运行状态、反编译类、监控方法执行。


## 第1章 Arthas 总体介绍

# 简介

![](/images/arthas.png)

Arthas 是一款线上监控诊断产品，通过全局视角实时查看应用 load、内存、gc、线程的状态信息，并能在不修改应用代码的情况下，对业务问题进行诊断，包括查看方法调用的出入参、异常，监测方法执行耗时，类加载信息等，大大提升线上问题排查效率。

## 背景

通常，本地开发环境无法访问生产环境。如果在生产环境中遇到问题，则无法使用 IDE 远程调试。更糟糕的是，在生产环境中调试是不可接受的，因为它会暂停所有线程，导致服务暂停。

开发人员可以尝试在测试环境或者预发环境中复现生产环境中的问题。但是，某些问题无法在不同的环境中轻松复现，甚至在重新启动后就消失了。

如果您正在考虑在代码中添加一些日志以帮助解决问题，您将必须经历以下阶段：测试、预发，然后生产。这种方法效率低下，更糟糕的是，该问题可能无法解决，因为一旦 JVM 重新启动，它可能无法复现，如上文所述。

Arthas 旨在解决这些问题。开发人员可以在线解决生产问题。无需 JVM 重启，无需代码更改。 Arthas 作为观察者永远不会暂停正在运行的线程。

## Arthas（阿尔萨斯）能为你做什么？

`Arthas` 是 Alibaba 开源的 Java 诊断工具，深受开发者喜爱。

当你遇到以下类似问题而束手无策时，`Arthas`可以帮助你解决：

0. 这个类从哪个 jar 包加载的？为什么会报各种类相关的 Exception？
1. 我改的代码为什么没有执行到？难道是我没 commit？分支搞错了？
2. 遇到问题无法在线上 debug，难道只能通过加日志再重新发布吗？
3. 线上遇到某个用户的数据处理有问题，但线上同样无法 debug，线下无法重现！
4. 是否有一个全局视角来查看系统的运行状况？
5. 有什么办法可以监控到 JVM 的实时运行状态？
6. 怎么快速定位应用的热点，生成火焰图？
7. 怎样直接从 JVM 内查找某个类的实例？

`Arthas` 4.x 支持 JDK 8+，包括 JDK 17、JDK 21 和 JDK 25，支持 Linux/Mac/Windows，采用命令行交互模式，同时提供丰富的 `Tab` 自动补全功能，进一步方便进行问题的定位和诊断。

**如果您在使用 Arthas，请让我们知道，您的使用对我们非常重要：[查看](https://github.com/alibaba/arthas/issues/111)**

## Contributors

[![](https://opencollective.com/arthas/contributors.svg?width=890&button=false)](https://github.com/alibaba/arthas/graphs/contributors)

## 第2章 进阶使用

# 其他特性

## Arthas 后台异步任务

当需要排查一个问题，但是这个问题的出现时间不能确定，那我们就可以把检测命令挂在后台运行，并将保存到输出日志。

- [Arthas 后台异步任务](async.md)

## 执行结果存日志

所有执行记录的结果完整保存在日志文件中，便于后续进行分析。

- [执行结果存日志](save-log.md)

## Docker

Arthas 在 docker 容器中使用配置参考。

- [Docker](docker.md)

## Web Console

通过 websocket 连接 Arthas。

- [Web Console](web-console.md)

## Arthas Tunnel

通过 Arthas Tunnel Server/Client 来远程管理/连接多个服务器下的Java服务。

- [Arthas Tunnel](tunnel.md)

## ognl 表达式用法

- [OGNL 复杂表达式使用技巧](ognl-express.md)
- [ognl 表达式的用法说明](https://github.com/alibaba/arthas/issues/11)

## IDEA Plugin

IntelliJ IDEA 编译器中更加快捷构建 arhtas 命令。

- [IDEA Plugin](idea-plugin.md)

## Arthas Properties

Arthas 支持配置项参考。

- [Arthas Properties](arthas-properties.md)

## 以 java agent 方式启动

- [以 java agent 方式启动](agent.md)

## Arthas Spring Boot Starter

随应用一起启动。

- [Arthas Spring Boot Starter](spring-boot-starter.md)

## 加载外部命令

在 Arthas 启动时加载外部 command jar，把团队内部诊断动作扩展为新的 Arthas 命令。

- [加载外部命令](external-command.md)

## HTTP API

Http API 提供结构化的数据，支持更复杂的交互功能，方便自定义界面集成 arthas。

- [HTTP API](http-api.md)

## 批处理功能

方便自定义脚本一次性批量运行多个命令，可结合 `--select` 参数可以指定进程名字一起使用。

- [批处理功能](batch-support.md)

## as.sh 和 arthas-boot 技巧

- 通过`select`功能选择 attach 的进程。

正常情况下，每次执行`as.sh`/`arthas-boot.jar`需要选择，或者指定 PID。这样会比较麻烦，因为每次启动应用，它的 PID 会变化。

比如，已经启动了`math-game.jar`，使用`jps`命令查看：

```bash
$ jps
58883 math-game.jar
58884 Jps
```

通过`select`参数可以指定进程名字，非常方便。

```bash
$ ./as.sh --select math-game
Arthas script version: 3.3.6
[INFO] JAVA_HOME: /tmp/java/8.0.222-zulu
Arthas home: /Users/admin/.arthas/lib/3.3.6/arthas
Calculating attach execution time...
Attaching to 59161 using version /Users/admin/.arthas/lib/3.3.6/arthas...

real	0m0.572s
user	0m0.281s
sys	0m0.039s
Attach success.
telnet connecting to arthas server... current timestamp is 1594280799
Trying 127.0.0.1...
Connected to localhost.
Escape character is '^]'.
  ,---.  ,------. ,--------.,--.  ,--.  ,---.   ,---.
 /  O  \ |  .--. ''--.  .--'|  '--'  | /  O  \ '   .-'
|  .-.  ||  '--'.'   |  |   |  .--.  ||  .-.  |`.  `-.
|  | |  ||  |\  \    |  |   |  |  |  ||  | |  |.-'    |
`--' `--'`--' '--'   `--'   `--'  `--'`--' `--'`-----'


wiki      https://arthas.aliyun.com/doc
version   3.3.6
pid       58883
```

## 用户数据回报

在`3.1.4`版本后，增加了用户数据回报功能，方便统一做安全或者历史数据统计。

在启动时，指定`stat-url`，就会回报执行的每一行命令，比如： `./as.sh --stat-url 'http://192.168.10.11:8080/api/stat'`

在 tunnel server 里有一个示例的回报代码，用户可以自己在服务器上实现。

[StatController.java](https://github.com/alibaba/arthas/blob/master/tunnel-server/src/main/java/com/alibaba/arthas/tunnel/server/app/web/StatController.java)

## 第3章 dashboard 命令（实时数据面板）

# dashboard

::: tip
当前系统的实时数据面板，按 ctrl+c 退出。
:::

当运行在 Ali-tomcat 时，会显示当前 tomcat 的实时信息，如 HTTP 请求的 qps, rt, 错误数, 线程池信息等等。

## 参数说明

| 参数名称 | 参数说明                                 |
| -------: | :--------------------------------------- |
|     [i:] | 刷新实时数据的时间间隔 (ms)，默认 5000ms |
|     [n:] | 刷新实时数据的次数                       |

## 使用参考

```
$ dashboard
ID   NAME                           GROUP           PRIORITY   STATE     %CPU      DELTA_TIME TIME      INTERRUPTE DAEMON
-1   C2 CompilerThread0             -               -1         -         1.55      0.077      0:8.684   false      true
53   Timer-for-arthas-dashboard-07b system          5          RUNNABLE  0.08      0.004      0:0.004   false      true
22   scheduling-1                   main            5          TIMED_WAI 0.06      0.003      0:0.287   false      false
-1   C1 CompilerThread0             -               -1         -         0.06      0.003      0:2.171   false      true
-1   VM Periodic Task Thread        -               -1         -         0.03      0.001      0:0.092   false      true
49   arthas-NettyHttpTelnetBootstra system          5          RUNNABLE  0.02      0.001      0:0.156   false      true
16   Catalina-utility-1             main            1          TIMED_WAI 0.0       0.000      0:0.029   false      false
-1   G1 Young RemSet Sampling       -               -1         -         0.0       0.000      0:0.019   false      true
17   Catalina-utility-2             main            1          WAITING   0.0       0.000      0:0.025   false      false
34   http-nio-8080-ClientPoller     main            5          RUNNABLE  0.0       0.000      0:0.016   false      true
23   http-nio-8080-BlockPoller      main            5          RUNNABLE  0.0       0.000      0:0.011   false      true
-1   VM Thread                      -               -1         -         0.0       0.000      0:0.032   false      true
-1   Service Thread                 -               -1         -         0.0       0.000      0:0.006   false      true
-1   GC Thread#5                    -               -1         -         0.0       0.000      0:0.043   false      true
Memory                     used     total    max      usage    GC
heap                       36M      70M      4096M    0.90%    gc.g1_young_generation.count   12
g1_eden_space              6M       18M      -1       33.33%                                  86
g1_old_gen                 30M      50M      4096M    0.74%    gc.g1_old_generation.count     0
g1_survivor_space          491K     2048K    -1       24.01%   gc.g1_old_generation.time(ms)  0
nonheap                    66M      69M      -1       96.56%
codeheap_'non-nmethods'    1M       2M       5M       22.39%
metaspace                  46M      47M      -1       98.01%
Runtime
os.name                                                        Mac OS X
os.version                                                     10.15.4
java.version                                                   15
java.home                                                      /Library/Java/JavaVirtualMachines/jdk-15.jdk/Contents/Home
systemload.average                                             10.68
processors                                                     8
uptime                                                         272s
```

## 数据说明

- ID: Java 级别的线程 ID，注意这个 ID 不能跟 jstack 中的 nativeID 一一对应。
- NAME: 线程名
- GROUP: 线程组名
- PRIORITY: 线程优先级, 1~10 之间的数字，越大表示优先级越高
- STATE: 线程的状态
- CPU%: 线程的 cpu 使用率。比如采样间隔 1000ms，某个线程的增量 cpu 时间为 100ms，则 cpu 使用率=100/1000=10%
- DELTA_TIME: 上次采样之后线程运行增量 CPU 时间，数据格式为`秒`
- TIME: 线程运行总 CPU 时间，数据格式为`分:秒`
- INTERRUPTED: 线程当前的中断位状态
- DAEMON: 是否是 daemon 线程

### JVM 内部线程

Java 8 之后支持获取 JVM 内部线程 CPU 时间，这些线程只有名称和 CPU 时间，没有 ID 及状态等信息（显示 ID 为-1）。
通过内部线程可以观测到 JVM 活动，如 GC、JIT 编译等占用 CPU 情况，方便了解 JVM 整体运行状况。

- 当 JVM 堆(heap)/元数据(metaspace)空间不足或 OOM 时，可以看到 GC 线程的 CPU 占用率明显高于其他的线程。
- 当执行`trace/watch/tt/redefine`等命令后，可以看到 JIT 线程活动变得更频繁。因为 JVM 热更新 class 字节码时清除了此 class 相关的 JIT 编译结果，需要重新编译。

JVM 内部线程包括下面几种：

- JIT 编译线程: 如 `C1 CompilerThread0`, `C2 CompilerThread0`
- GC 线程: 如`GC Thread0`, `G1 Young RemSet Sampling`
- 其它内部线程: 如`VM Periodic Task Thread`, `VM Thread`, `Service Thread`

## 截图展示

![](/images/dashboard.png "dashboard")

## 第4章 thread 命令（线程分析）

# thread

::: tip
查看当前线程信息，查看线程的堆栈
:::

## 参数说明

|      参数名称 | 参数说明                                                |
| ------------: | :------------------------------------------------------ |
|          _id_ | 线程 id                                                 |
|          [n:] | 指定最忙的前 N 个线程并打印堆栈                         |
|           [b] | 找出当前阻塞其他线程的线程                              |
| [i `<value>`] | 指定 cpu 使用率统计的采样间隔，单位为毫秒，默认值为 200 |
|       [--all] | 显示所有匹配的线程                                      |

## cpu 使用率是如何统计出来的？

这里的 cpu 使用率与 linux 命令`top -H -p <pid>` 的线程`%CPU`类似，一段采样间隔时间内，当前 JVM 里各个线程的增量 cpu 时间与采样间隔时间的比例。

### 工作原理说明：

- 首先第一次采样，获取所有线程的 CPU 时间(调用的是`java.lang.management.ThreadMXBean#getThreadCpuTime()`及`sun.management.HotspotThreadMBean.getInternalThreadCpuTimes()`接口)
- 然后睡眠等待一个间隔时间（默认为 200ms，可以通过`-i`指定间隔时间）
- 再次第二次采样，获取所有线程的 CPU 时间，对比两次采样数据，计算出每个线程的增量 CPU 时间
- 线程 CPU 使用率 = 线程增量 CPU 时间 / 采样间隔时间 \* 100%

::: warning
注意： 这个统计也会产生一定的开销（JDK 这个接口本身开销比较大），因此会看到 as 的线程占用一定的百分比，为了降低统计自身的开销带来的影响，可以把采样间隔拉长一些，比如 5000 毫秒。
:::

::: tip
另外一种查看 Java 进程的线程 cpu 使用率方法：可以使用[`show-busy-java-threads`](https://github.com/oldratlee/useful-scripts/blob/dev-2.x/docs/java.md#-show-busy-java-threads)这个脚本。
:::

## 使用参考

### 支持一键展示当前最忙的前 N 个线程并打印堆栈：

```shell
$ thread -n 3
"C1 CompilerThread0" [Internal] cpuUsage=1.63% deltaTime=3ms time=1170ms


"arthas-command-execute" Id=23 cpuUsage=0.11% deltaTime=0ms time=401ms RUNNABLE
    at java.management@11.0.7/sun.management.ThreadImpl.dumpThreads0(Native Method)
    at java.management@11.0.7/sun.management.ThreadImpl.getThreadInfo(ThreadImpl.java:466)
    at com.taobao.arthas.core.command.monitor200.ThreadCommand.processTopBusyThreads(ThreadCommand.java:199)
    at com.taobao.arthas.core.command.monitor200.ThreadCommand.process(ThreadCommand.java:122)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl.process(AnnotatedCommandImpl.java:82)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl.access$100(AnnotatedCommandImpl.java:18)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl$ProcessHandler.handle(AnnotatedCommandImpl.java:111)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl$ProcessHandler.handle(AnnotatedCommandImpl.java:108)
    at com.taobao.arthas.core.shell.system.impl.ProcessImpl$CommandProcessTask.run(ProcessImpl.java:385)
    at java.base@11.0.7/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:515)
    at java.base@11.0.7/java.util.concurrent.FutureTask.run(FutureTask.java:264)
    at java.base@11.0.7/java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.run(ScheduledThreadPoolExecutor.java:304)
    at java.base@11.0.7/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
    at java.base@11.0.7/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
    at java.base@11.0.7/java.lang.Thread.run(Thread.java:834)


"VM Periodic Task Thread" [Internal] cpuUsage=0.07% deltaTime=0ms time=584ms
```

- 没有线程 ID，包含`[Internal]`表示为 JVM 内部线程，参考[dashboard](dashboard.md)命令的介绍。
- `cpuUsage`为采样间隔时间内线程的 CPU 使用率，与[dashboard](dashboard.md)命令的数据一致。
- `deltaTime`为采样间隔时间内线程的增量 CPU 时间，小于 1ms 时被取整显示为 0ms。
- `time` 线程运行总 CPU 时间。

注意：线程栈为第二采样结束时获取，不能表明采样间隔时间内该线程都是在处理相同的任务。建议间隔时间不要太长，可能间隔时间越大越不准确。
可以根据具体情况尝试指定不同的间隔时间，观察输出结果。

### 当没有参数时，显示第一页线程的信息

默认按照 CPU 增量时间降序排列，只显示第一页数据。

```shell
$ thread
Threads Total: 33, NEW: 0, RUNNABLE: 9, BLOCKED: 0, WAITING: 3, TIMED_WAITING: 4, TERMINATED: 0, Internal threads: 17
ID   NAME                           GROUP          PRIORITY  STATE     %CPU      DELTA_TIME TIME      INTERRUPT DAEMON
-1   C2 CompilerThread0             -              -1        -         5.06      0.010      0:0.973   false     true
-1   C1 CompilerThread0             -              -1        -         0.95      0.001      0:0.603   false     true
23   arthas-command-execute         system         5         RUNNABLE  0.17      0.000      0:0.226   false     true
-1   VM Periodic Task Thread        -              -1        -         0.05      0.000      0:0.094   false     true
-1   Sweeper thread                 -              -1        -         0.04      0.000      0:0.011   false     true
-1   G1 Young RemSet Sampling       -              -1        -         0.02      0.000      0:0.025   false     true
12   Attach Listener                system         9         RUNNABLE  0.0       0.000      0:0.022   false     true
11   Common-Cleaner                 InnocuousThrea 8         TIMED_WAI 0.0       0.000      0:0.000   false     true
3    Finalizer                      system         8         WAITING   0.0       0.000      0:0.000   false     true
2    Reference Handler              system         10        RUNNABLE  0.0       0.000      0:0.000   false     true
4    Signal Dispatcher              system         9         RUNNABLE  0.0       0.000      0:0.000   false     true
15   arthas-NettyHttpTelnetBootstra system         5         RUNNABLE  0.0       0.000      0:0.029   false     true
22   arthas-NettyHttpTelnetBootstra system         5         RUNNABLE  0.0       0.000      0:0.196   false     true
24   arthas-NettyHttpTelnetBootstra system         5         RUNNABLE  0.0       0.000      0:0.038   false     true
16   arthas-NettyWebsocketTtyBootst system         5         RUNNABLE  0.0       0.000      0:0.001   false     true
17   arthas-NettyWebsocketTtyBootst system         5         RUNNABLE  0.0       0.000      0:0.001   false     true
```

### thread --all, 显示所有匹配的线程

显示所有匹配线程信息，有时需要获取全部 JVM 的线程数据进行分析。

### thread id, 显示指定线程的运行堆栈

```shell
$ thread 1
"main" Id=1 WAITING on java.util.concurrent.CountDownLatch$Sync@29fafb28
    at sun.misc.Unsafe.park(Native Method)
    -  waiting on java.util.concurrent.CountDownLatch$Sync@29fafb28
    at java.util.concurrent.locks.LockSupport.park(LockSupport.java:175)
    at java.util.concurrent.locks.AbstractQueuedSynchronizer.parkAndCheckInterrupt(AbstractQueuedSynchronizer.java:836)
    at java.util.concurrent.locks.AbstractQueuedSynchronizer.doAcquireSharedInterruptibly(AbstractQueuedSynchronizer.java:997)
    at java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireSharedInterruptibly(AbstractQueuedSynchronizer.java:1304)
    at java.util.concurrent.CountDownLatch.await(CountDownLatch.java:231)
```

### thread -b, 找出当前阻塞其他线程的线程

有时候我们发现应用卡住了， 通常是由于某个线程拿住了某个锁， 并且其他线程都在等待这把锁造成的。 为了排查这类问题， arthas 提供了`thread -b`， 一键找出那个罪魁祸首。

```bash
$ thread -b
"http-bio-8080-exec-4" Id=27 TIMED_WAITING
    at java.lang.Thread.sleep(Native Method)
    at test.arthas.TestThreadBlocking.doGet(TestThreadBlocking.java:22)
    -  locked java.lang.Object@725be470 <---- but blocks 4 other threads!
    at javax.servlet.http.HttpServlet.service(HttpServlet.java:624)
    at javax.servlet.http.HttpServlet.service(HttpServlet.java:731)
    at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:303)
    at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:208)
    at org.apache.tomcat.websocket.server.WsFilter.doFilter(WsFilter.java:52)
    at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:241)
    at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:208)
    at test.filter.TestDurexFilter.doFilter(TestDurexFilter.java:46)
    at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:241)
    at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:208)
    at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:220)
    at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:122)
    at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:505)
    at com.taobao.tomcat.valves.ContextLoadFilterValve$FilterChainAdapter.doFilter(ContextLoadFilterValve.java:191)
    at com.taobao.eagleeye.EagleEyeFilter.doFilter(EagleEyeFilter.java:81)
    at com.taobao.tomcat.valves.ContextLoadFilterValve.invoke(ContextLoadFilterValve.java:150)
    at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:170)
    at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:103)
    at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:116)
    at org.apache.catalina.connector.CoyoteAdapter.service(CoyoteAdapter.java:429)
    at org.apache.coyote.http11.AbstractHttp11Processor.process(AbstractHttp11Processor.java:1085)
    at org.apache.coyote.AbstractProtocol$AbstractConnectionHandler.process(AbstractProtocol.java:625)
    at org.apache.tomcat.util.net.JIoEndpoint$SocketProcessor.run(JIoEndpoint.java:318)
    -  locked org.apache.tomcat.util.net.SocketWrapper@7127ee12
    at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142)
    at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617)
    at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61)
    at java.lang.Thread.run(Thread.java:745)

    Number of locked synchronizers = 1
    - java.util.concurrent.ThreadPoolExecutor$Worker@31a6493e
```

::: warning
注意， 目前只支持找出 synchronized 关键字阻塞住的线程， 如果是`java.util.concurrent.Lock`， 目前还不支持。
:::

### thread -i, 指定采样时间间隔

- `thread -i 1000` : 统计最近 1000ms 内的线程 CPU 时间。

- `thread -n 3 -i 1000` : 列出 1000ms 内最忙的 3 个线程栈

```bash
$ thread -n 3 -i 1000
"as-command-execute-daemon" Id=4759 cpuUsage=23% RUNNABLE
    at sun.management.ThreadImpl.dumpThreads0(Native Method)
    at sun.management.ThreadImpl.getThreadInfo(ThreadImpl.java:440)
    at com.taobao.arthas.core.command.monitor200.ThreadCommand.processTopBusyThreads(ThreadCommand.java:133)
    at com.taobao.arthas.core.command.monitor200.ThreadCommand.process(ThreadCommand.java:79)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl.process(AnnotatedCommandImpl.java:96)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl.access$100(AnnotatedCommandImpl.java:27)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl$ProcessHandler.handle(AnnotatedCommandImpl.java:125)
    at com.taobao.arthas.core.shell.command.impl.AnnotatedCommandImpl$ProcessHandler.handle(AnnotatedCommandImpl.java:122)
    at com.taobao.arthas.core.shell.system.impl.ProcessImpl$CommandProcessTask.run(ProcessImpl.java:332)
    at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1142)
    at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617)
    at java.lang.Thread.run(Thread.java:756)

    Number of locked synchronizers = 1
    - java.util.concurrent.ThreadPoolExecutor$Worker@546aeec1
...
```

### thread --state ，查看指定状态的线程

```bash
[arthas@28114]$ thread --state WAITING
Threads Total: 16, NEW: 0, RUNNABLE: 9, BLOCKED: 0, WAITING: 3, TIMED_WAITING: 4, TERMINATED: 0
ID   NAME                           GROUP           PRIORITY   STATE     %CPU      DELTA_TIME TIME      INTERRUPTE DAEMON
3    Finalizer                      system          8          WAITING   0.0       0.000      0:0.000   false      true
20   arthas-UserStat                system          9          WAITING   0.0       0.000      0:0.001   false      true
14   arthas-timer                   system          9          WAITING   0.0       0.000      0:0.000   false      true
```

## 第5章 jvm 命令（JVM 信息查看）

# jvm

::: tip
查看当前 JVM 信息
:::

## 使用参考

```
$ jvm
RUNTIME
--------------------------------------------------------------------------------------------------------------
 MACHINE-NAME                   37@ff267334bb65
 JVM-START-TIME                 2020-07-23 07:50:36
 MANAGEMENT-SPEC-VERSION        1.2
 SPEC-NAME                      Java Virtual Machine Specification
 SPEC-VENDOR                    Oracle Corporation
 SPEC-VERSION                   1.8
 VM-NAME                        Java HotSpot(TM) 64-Bit Server VM
 VM-VENDOR                      Oracle Corporation
 VM-VERSION                     25.201-b09
 INPUT-ARGUMENTS                []
 CLASS-PATH                     demo-arthas-spring-boot.jar
 BOOT-CLASS-PATH                /usr/lib/jvm/java-8-oracle/jre/lib/resources.jar:/usr/lib/jvm/java-8-oracle/j
                                re/lib/rt.jar:/usr/lib/jvm/java-8-oracle/jre/lib/sunrsasign.jar:/usr/lib/jvm/
                                java-8-oracle/jre/lib/jsse.jar:/usr/lib/jvm/java-8-oracle/jre/lib/jce.jar:/us
                                r/lib/jvm/java-8-oracle/jre/lib/charsets.jar:/usr/lib/jvm/java-8-oracle/jre/l
                                ib/jfr.jar:/usr/lib/jvm/java-8-oracle/jre/classes
 LIBRARY-PATH                   /usr/java/packages/lib/amd64:/usr/lib64:/lib64:/lib:/usr/lib

--------------------------------------------------------------------------------------------------------------
 CLASS-LOADING
--------------------------------------------------------------------------------------------------------------
 LOADED-CLASS-COUNT             7529
 TOTAL-LOADED-CLASS-COUNT       7529
 UNLOADED-CLASS-COUNT           0
 IS-VERBOSE                     false

--------------------------------------------------------------------------------------------------------------
 COMPILATION
--------------------------------------------------------------------------------------------------------------
 NAME                           HotSpot 64-Bit Tiered Compilers
 TOTAL-COMPILE-TIME             14921(ms)

--------------------------------------------------------------------------------------------------------------
 GARBAGE-COLLECTORS
--------------------------------------------------------------------------------------------------------------
 PS Scavenge                            name : PS Scavenge
 [count/time (ms)]                      collectionCount : 7
                                        collectionTime : 68

 PS MarkSweep                           name : PS MarkSweep
 [count/time (ms)]                      collectionCount : 1
                                        collectionTime : 47

--------------------------------------------------------------------------------------------------------------
 MEMORY-MANAGERS
--------------------------------------------------------------------------------------------------------------
 CodeCacheManager               Code Cache

 Metaspace Manager              Metaspace
                                Compressed Class Space

 Copy                           Eden Space
                                Survivor Space

 MarkSweepCompact               Eden Space
                                Survivor Space
                                Tenured Gen


--------------------------------------------------------------------------------------------------------------
 MEMORY
--------------------------------------------------------------------------------------------------------------
 HEAP-MEMORY-USAGE                      init : 268435456(256.0 MiB)
 [memory in bytes]                      used : 18039504(17.2 MiB)
                                        committed : 181403648(173.0 MiB)
                                        max : 3817865216(3.6 GiB)

 NO-HEAP-MEMORY-USAGE                   init : 2555904(2.4 MiB)
 [memory in bytes]                      used : 33926216(32.4 MiB)
                                        committed : 35176448(33.5 MiB)
                                        max : -1(-1 B)

--------------------------------------------------------------------------------------------------------------
 OPERATING-SYSTEM
--------------------------------------------------------------------------------------------------------------
 OS                             Linux
 ARCH                           amd64
 PROCESSORS-COUNT               3
 LOAD-AVERAGE                   29.53
 VERSION                        4.15.0-52-generic

--------------------------------------------------------------------------------------------------------------
 THREAD
--------------------------------------------------------------------------------------------------------------
 COUNT                          30
 DAEMON-COUNT                   24
 PEAK-COUNT                     31
 STARTED-COUNT                  36
 DEADLOCK-COUNT                 0

--------------------------------------------------------------------------------------------------------------
 FILE-DESCRIPTOR
--------------------------------------------------------------------------------------------------------------
 MAX-FILE-DESCRIPTOR-COUNT      1048576
 OPEN-FILE-DESCRIPTOR-COUNT     100
Affect(row-cnt:0) cost in 88 ms.
```

## THREAD 相关

- COUNT: JVM 当前活跃的线程数
- DAEMON-COUNT: JVM 当前活跃的守护线程数
- PEAK-COUNT: 从 JVM 启动开始曾经活着的最大线程数
- STARTED-COUNT: 从 JVM 启动开始总共启动过的线程次数
- DEADLOCK-COUNT: JVM 当前死锁的线程数

## 文件描述符相关

- MAX-FILE-DESCRIPTOR-COUNT：JVM 进程最大可以打开的文件描述符数
- OPEN-FILE-DESCRIPTOR-COUNT：JVM 当前打开的文件描述符数

## 第6章 jad 命令（反编译）

# jad

::: tip
反编译指定已加载类的源码
:::

`jad` 命令将 JVM 中实际运行的 class 的 byte code 反编译成 java 代码，便于你理解业务逻辑；如需批量下载指定包的目录的 class 字节码可以参考 [dump](/doc/dump.md)。

- 在 Arthas Console 上，反编译出来的源码是带语法高亮的，阅读更方便
- 当然，反编译出来的 java 代码可能会存在语法错误，但不影响你进行阅读理解

## 参数说明

|              参数名称 | 参数说明                                   |
| --------------------: | :----------------------------------------- |
|       _class-pattern_ | 类名表达式匹配                             |
|                `[c:]` | 类所属 ClassLoader 的 hashcode             |
| `[classLoaderClass:]` | 指定执行表达式的 ClassLoader 的 class name |
|                   [E] | 开启正则表达式匹配，默认为通配符匹配       |

## 使用参考

### 反编译`java.lang.String`

```java
$ jad java.lang.String

ClassLoader:

Location:


        /*
         * Decompiled with CFR.
         */
        package java.lang;

        import java.io.ObjectStreamField;
        import java.io.Serializable;
...
        public final class String
        implements Serializable,
        Comparable<String>,
        CharSequence {
            private final char[] value;
            private int hash;
            private static final long serialVersionUID = -6849794470754667710L;
            private static final ObjectStreamField[] serialPersistentFields = new ObjectStreamField[0];
            public static final Comparator<String> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();
...
            public String(byte[] byArray, int n, int n2, Charset charset) {
/*460*/         if (charset == null) {
                    throw new NullPointerException("charset");
                }
/*462*/         String.checkBounds(byArray, n, n2);
/*463*/         this.value = StringCoding.decode(charset, byArray, n, n2);
            }
...
```

### 反编译时只显示源代码

默认情况下，反编译结果里会带有`ClassLoader`信息，通过`--source-only`选项，可以只打印源代码。方便和[mc](mc.md)/[retransform](retransform.md)命令结合使用。

```java
$ jad --source-only demo.MathGame
/*
 * Decompiled with CFR 0_132.
 */
package demo;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class MathGame {
    private static Random random = new Random();
    public int illegalArgumentCount = 0;
...
```

### 反编译指定的函数

```java
$ jad demo.MathGame main

ClassLoader:
+-sun.misc.Launcher$AppClassLoader@232204a1
  +-sun.misc.Launcher$ExtClassLoader@7f31245a

Location:
/private/tmp/math-game.jar

       public static void main(String[] args) throws InterruptedException {
           MathGame game = new MathGame();
           while (true) {
/*16*/         game.run();
/*17*/         TimeUnit.SECONDS.sleep(1L);
           }
       }
```

### 反编译时不显示行号

`--lineNumber` 参数默认值为 true，显示指定为 false 则不打印行号。

```java
$ jad demo.MathGame main --lineNumber false

ClassLoader:
+-sun.misc.Launcher$AppClassLoader@232204a1
  +-sun.misc.Launcher$ExtClassLoader@7f31245a

Location:
/private/tmp/math-game.jar

public static void main(String[] args) throws InterruptedException {
    MathGame game = new MathGame();
    while (true) {
        game.run();
        TimeUnit.SECONDS.sleep(1L);
    }
}
```

### 反编译时指定 ClassLoader

::: tip
当有多个 `ClassLoader` 都加载了这个类时，`jad` 命令会输出对应 `ClassLoader` 实例的 `hashcode`，然后你只需要重新执行 `jad` 命令，并使用参数 `-c <hashcode>` 就可以反编译指定 ClassLoader 加载的那个类了；
:::

```java
$ jad org.apache.log4j.Logger

Found more than one class for: org.apache.log4j.Logger, Please use jad -c hashcode org.apache.log4j.Logger
HASHCODE  CLASSLOADER
69dcaba4  +-monitor's ModuleClassLoader
6e51ad67  +-java.net.URLClassLoader@6e51ad67
            +-sun.misc.Launcher$AppClassLoader@6951a712
            +-sun.misc.Launcher$ExtClassLoader@6fafc4c2
2bdd9114  +-pandora-qos-service's ModuleClassLoader
4c0df5f8  +-pandora-framework's ModuleClassLoader

Affect(row-cnt:0) cost in 38 ms.
$ jad org.apache.log4j.Logger -c 69dcaba4

ClassLoader:
+-monitor's ModuleClassLoader

Location:
/Users/admin/app/log4j-1.2.14.jar

package org.apache.log4j;

import org.apache.log4j.spi.*;

public class Logger extends Category
{
    private static final String FQCN;

    protected Logger(String name)
    {
        super(name);
    }

...

Affect(row-cnt:1) cost in 190 ms.
```

对于只有唯一实例的 ClassLoader 还可以通过`--classLoaderClass`指定 class name，使用起来更加方便：

`--classLoaderClass` 的值是 ClassLoader 的类名，只有匹配到唯一的 ClassLoader 实例时才能工作，目的是方便输入通用命令，而`-c <hashcode>`是动态变化的。

### 反编译时指定dump class文件目录

`jad`反编译需要将class dump到文件，默认会dump到logback.xml中配置的log目录下，使用`-d/--directory`可以将文件dump到指定目录。

```java
$ jad demo.MathGame -d /tmp/jad/dump
```

## 第7章 sc 命令（查找类）

# sc

::: tip
查看 JVM 已加载的类信息
:::

“Search-Class” 的简写，这个命令能搜索出所有已经加载到 JVM 中的 Class 信息，这个命令支持的参数有 `[d]`、`[E]`、`[f]` 和 `[x:]`。

## 参数说明

|              参数名称 | 参数说明                                                                                                                                              |
| --------------------: | :---------------------------------------------------------------------------------------------------------------------------------------------------- |
|       _class-pattern_ | 类名表达式匹配                                                                                                                                        |
|      _method-pattern_ | 方法名表达式匹配                                                                                                                                      |
|                   [d] | 输出当前类的详细信息，包括这个类所加载的原始文件来源、类的声明、加载的 ClassLoader 等详细信息。<br/>如果一个类被多个 ClassLoader 所加载，则会出现多次 |
|                   [E] | 开启正则表达式匹配，默认为通配符匹配                                                                                                                  |
|                   [f] | 输出当前类的成员变量信息（需要配合参数-d 一起使用）                                                                                                   |
|                  [x:] | 指定输出静态变量时属性的遍历深度，默认为 0，即直接使用 `toString` 输出                                                                                |
|                `[c:]` | 指定 class 的 ClassLoader 的 hashcode                                                                                                                 |
| `[classLoaderClass:]` | 指定执行表达式的 ClassLoader 的 class name                                                                                                            |
|                `[n:]` | 具有详细信息的匹配类的最大数量（默认为 100）                                                                                                          |
|          `[cs <arg>]` | 指定 class 的 ClassLoader#toString() 返回值。长格式`[classLoaderStr <arg>]`                                                                           |

::: tip
class-pattern 支持全限定名，如 com.taobao.test.AAA，也支持 com/taobao/test/AAA 这样的格式，这样，我们从异常堆栈里面把类名拷贝过来的时候，不需要在手动把`/`替换为`.`啦。
:::

::: tip
sc 默认开启了子类匹配功能，也就是说所有当前类的子类也会被搜索出来，想要精确的匹配，请打开`options disable-sub-class true`开关
:::

## 使用参考

- 模糊搜索

  ```bash
  $ sc demo.*
  demo.MathGame
  Affect(row-cnt:1) cost in 55 ms.
  ```

- 打印类的详细信息

  ```bash
  $ sc -d demo.MathGame
  class-info        demo.MathGame
  code-source       /private/tmp/math-game.jar
  name              demo.MathGame
  isInterface       false
  isAnnotation      false
  isEnum            false
  isAnonymousClass  false
  isArray           false
  isLocalClass      false
  isMemberClass     false
  isPrimitive       false
  isSynthetic       false
  simple-name       MathGame
  modifier          public
  annotation
  interfaces
  super-class       +-java.lang.Object
  class-loader      +-sun.misc.Launcher$AppClassLoader@3d4eac69
                      +-sun.misc.Launcher$ExtClassLoader@66350f69
  classLoaderHash   3d4eac69

  Affect(row-cnt:1) cost in 875 ms.
  ```

- 打印出类的 Field 信息

  ```bash
  $ sc -d -f demo.MathGame
  class-info        demo.MathGame
  code-source       /private/tmp/math-game.jar
  name              demo.MathGame
  isInterface       false
  isAnnotation      false
  isEnum            false
  isAnonymousClass  false
  isArray           false
  isLocalClass      false
  isMemberClass     false
  isPrimitive       false
  isSynthetic       false
  simple-name       MathGame
  modifier          public
  annotation
  interfaces
  super-class       +-java.lang.Object
  class-loader      +-sun.misc.Launcher$AppClassLoader@3d4eac69
                      +-sun.misc.Launcher$ExtClassLoader@66350f69
  classLoaderHash   3d4eac69
  fields            modifierprivate,static
                    type    java.util.Random
                    name    random
                    value   java.util.Random@522b4
                            08a

                    modifierprivate
                    type    int
                    name    illegalArgumentCount


  Affect(row-cnt:1) cost in 19 ms.
  ```

- 通过 ClassLoader#toString 查找类（前提：有一个 toString()返回值是`apo`的类加载器，加载的类中包含`demo.MathGame`, `demo.MyBar`,` demo.MyFoo`3 个类）

  ```bash
  $ sc -cs apo *demo*
  demo.MathGame
  demo.MyBar
  demo.MyFoo
  Affect(row-cnt:3) cost in 56 ms.
  ```

## 第8章 watch 命令（方法执行观测）

# watch

::: tip
函数执行数据观测
:::

让你能方便的观察到指定函数的调用情况。能观察到的范围为：`返回值`、`抛出异常`、`入参`，通过编写 OGNL 表达式进行对应变量的查看。

## 参数说明

watch 的参数比较多，主要是因为它能在 4 个不同的场景观察对象

|            参数名称 | 参数说明                                                           |
| ------------------: | :----------------------------------------------------------------- |
|     _class-pattern_ | 类名表达式匹配                                                     |
|    _method-pattern_ | 函数名表达式匹配                                                   |
|           _express_ | 观察表达式，默认值：`{params, target, returnObj}`                  |
| _condition-express_ | 条件表达式                                                         |
|                 [b] | 在**函数调用之前**观察                                             |
|                 [e] | 在**函数异常之后**观察                                             |
|                 [s] | 在**函数返回之后**观察                                             |
|                 [f] | 在**函数结束之后**(正常返回和异常返回)观察                         |
|                 [E] | 开启正则表达式匹配，默认为通配符匹配                               |
|                [x:] | 指定输出结果的属性遍历深度，默认为 1，最大值是 4                   |
|                [c:] | 指定 classloader hash，只增强该 classloader 加载的类               |
|         `[m <arg>]` | 指定 Class 最大匹配数量，默认值为 50。长格式为`[maxMatch <arg>]`。 |

这里重点要说明的是观察表达式，观察表达式的构成主要由 ognl 表达式组成，所以你可以这样写`"{params,returnObj}"`，只要是一个合法的 ognl 表达式，都能被正常支持。

观察的维度也比较多，主要体现在参数 `advice` 的数据结构上。`Advice` 参数最主要是封装了通知节点的所有信息。请参考[表达式核心变量](advice-class.md)中关于该节点的描述。

- [OGNL 复杂表达式使用技巧](ognl-express.md)

**特别说明**：

- watch 命令定义了 4 个观察事件点，即 `-b` 函数调用前，`-e` 函数异常后，`-s` 函数返回后，`-f` 函数结束后
- 4 个观察事件点 `-b`、`-e`、`-s` 默认关闭，`-f` 默认打开，当指定观察点被打开后，在相应事件点会对观察表达式进行求值并输出
- 这里要注意`函数入参`和`函数出参`的区别，有可能在中间被修改导致前后不一致，除了 `-b` 事件点 `params` 代表函数入参外，其余事件都代表函数出参
- 当使用 `-b` 时，由于观察事件点是在函数调用前，此时返回值或异常均不存在
- 在 watch 命令的结果里，会打印出`location`信息。`location`有三种可能值：`AtEnter`，`AtExit`，`AtExceptionExit`。对应函数入口，函数正常 return，函数抛出异常。

## 使用参考

### 启动 Demo

启动[快速入门](quick-start.md)里的`math-game`。

### 观察函数调用返回时的参数、this 对象和返回值

::: tip
观察表达式，默认值是`{params, target, returnObj}`
:::

```bash
$ watch demo.MathGame primeFactors -x 2
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 32 ms, listenerId: 5
method=demo.MathGame.primeFactors location=AtExceptionExit
ts=2021-08-31 15:22:57; [cost=0.220625ms] result=@ArrayList[
    @Object[][
        @Integer[-179173],
    ],
    @MathGame[
        random=@Random[java.util.Random@31cefde0],
        illegalArgumentCount=@Integer[44],
    ],
    null,
]
method=demo.MathGame.primeFactors location=AtExit
ts=2021-08-31 15:22:58; [cost=1.020982ms] result=@ArrayList[
    @Object[][
        @Integer[1],
    ],
    @MathGame[
        random=@Random[java.util.Random@31cefde0],
        illegalArgumentCount=@Integer[44],
    ],
    @ArrayList[
        @Integer[2],
        @Integer[2],
        @Integer[26947],
    ],
]
```

- 上面的结果里，说明函数被执行了两次，第一次结果是`location=AtExceptionExit`，说明函数抛出异常了，因此`returnObj`是 null
- 在第二次结果里是`location=AtExit`，说明函数正常返回，因此可以看到`returnObj`结果是一个 ArrayList

### 指定 Class 最大匹配数量

```bash
$ watch demo.MathGame primeFactors -m 1
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 302 ms, listenerId: 3
method=demo.MathGame.primeFactors location=AtExceptionExit
ts=2022-12-25 19:58:41; [cost=0.222419ms] result=@ArrayList[
    @Object[][isEmpty=false;size=1],
    @MathGame[demo.MathGame@3bf400],
    null,
]
method=demo.MathGame.primeFactors location=AtExceptionExit
ts=2022-12-25 19:58:51; [cost=0.046928ms] result=@ArrayList[
    @Object[][isEmpty=false;size=1],
    @MathGame[demo.MathGame@3bf400],
    null,
]
```

### 指定 ClassLoader 增强

当同名类被多个 classloader 加载时，可以先用 `sc -d` 查看 classloader hash，然后用 `-c` 指定增强的 classloader：

```bash
sc -d com.example.Foo
watch -c 3d4eac69 com.example.Foo bar '{params,returnObj}'
```

### 观察函数调用入口的参数和返回值

```bash
$ watch demo.MathGame primeFactors "{params,returnObj}" -x 2 -b
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 50 ms.
ts=2018-12-03 19:23:23; [cost=0.0353ms] result=@ArrayList[
    @Object[][
        @Integer[-1077465243],
    ],
    null,
]
```

- 对比前一个例子，返回值为空（事件点为函数执行前，因此获取不到返回值）

### 同时观察函数调用前和函数返回后

```bash
$ watch demo.MathGame primeFactors "{params,target,returnObj}" -x 2 -b -s -n 2
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 46 ms.
ts=2018-12-03 19:29:54; [cost=0.01696ms] result=@ArrayList[
    @Object[][
        @Integer[1],
    ],
    @MathGame[
        random=@Random[java.util.Random@522b408a],
        illegalArgumentCount=@Integer[13038],
    ],
    null,
]
ts=2018-12-03 19:29:54; [cost=4.277392ms] result=@ArrayList[
    @Object[][
        @Integer[1],
    ],
    @MathGame[
        random=@Random[java.util.Random@522b408a],
        illegalArgumentCount=@Integer[13038],
    ],
    @ArrayList[
        @Integer[2],
        @Integer[2],
        @Integer[2],
        @Integer[5],
        @Integer[5],
        @Integer[73],
        @Integer[241],
        @Integer[439],
    ],
]
```

- 参数里`-n 2`，表示只执行两次

- 这里输出结果中，第一次输出的是函数调用前的观察表达式的结果，第二次输出的是函数返回后的表达式的结果

- 结果的输出顺序和事件发生的先后顺序一致，和命令中 `-s -b` 的顺序无关

### 调整`-x`的值，观察具体的函数参数值

```bash
$ watch demo.MathGame primeFactors "{params,target}" -x 3
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 58 ms.
ts=2018-12-03 19:34:19; [cost=0.587833ms] result=@ArrayList[
    @Object[][
        @Integer[1],
    ],
    @MathGame[
        random=@Random[
            serialVersionUID=@Long[3905348978240129619],
            seed=@AtomicLong[3133719055989],
            multiplier=@Long[25214903917],
            addend=@Long[11],
            mask=@Long[281474976710655],
            DOUBLE_UNIT=@Double[1.1102230246251565E-16],
            BadBound=@String[bound must be positive],
            BadRange=@String[bound must be greater than origin],
            BadSize=@String[size must be non-negative],
            seedUniquifier=@AtomicLong[-3282039941672302964],
            nextNextGaussian=@Double[0.0],
            haveNextNextGaussian=@Boolean[false],
            serialPersistentFields=@ObjectStreamField[][isEmpty=false;size=3],
            unsafe=@Unsafe[sun.misc.Unsafe@2eaa1027],
            seedOffset=@Long[24],
        ],
        illegalArgumentCount=@Integer[13159],
    ],
]
```

- `-x`表示遍历深度，可以调整来打印具体的参数和结果内容，默认值是 1。
- `-x`最大值是 4，防止展开结果占用太多内存。用户可以在`ognl`表达式里指定更具体的 field。

### 条件表达式的例子

```bash
$ watch demo.MathGame primeFactors "{params[0],target}" "params[0]<0"
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 68 ms.
ts=2018-12-03 19:36:04; [cost=0.530255ms] result=@ArrayList[
    @Integer[-18178089],
    @MathGame[demo.MathGame@41cf53f9],
]
```

- 只有满足条件的调用，才会有响应。

### 观察异常信息的例子

```bash
$ watch demo.MathGame primeFactors "{params[0],throwExp}" -e -x 2
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 62 ms.
ts=2018-12-03 19:38:00; [cost=1.414993ms] result=@ArrayList[
    @Integer[-1120397038],
    java.lang.IllegalArgumentException: number is: -1120397038, need >= 2
	at demo.MathGame.primeFactors(MathGame.java:46)
	at demo.MathGame.run(MathGame.java:24)
	at demo.MathGame.main(MathGame.java:16)
,
]
```

- `-e`表示抛出异常时才触发
- express 中，表示异常信息的变量是`throwExp`

### 按照耗时进行过滤

```bash
$ watch demo.MathGame primeFactors '{params, returnObj}' '#cost>200' -x 2
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 66 ms.
ts=2018-12-03 19:40:28; [cost=2112.168897ms] result=@ArrayList[
    @Object[][
        @Integer[1],
    ],
    @ArrayList[
        @Integer[5],
        @Integer[428379493],
    ],
]
```

- `#cost>200`(单位是`ms`)表示只有当耗时大于 200ms 时才会输出，过滤掉执行时间小于 200ms 的调用

### 观察当前对象中的属性

如果想查看函数运行前后，当前对象中的属性，可以使用`target`关键字，代表当前对象

```bash
$ watch demo.MathGame primeFactors 'target'
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 52 ms.
ts=2018-12-03 19:41:52; [cost=0.477882ms] result=@MathGame[
    random=@Random[java.util.Random@522b408a],
    illegalArgumentCount=@Integer[13355],
]
```

然后使用`target.field_name`访问当前对象的某个属性

```bash
$ watch demo.MathGame primeFactors 'target.illegalArgumentCount'
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 67 ms.
ts=2018-12-03 20:04:34; [cost=131.303498ms] result=@Integer[8]
ts=2018-12-03 20:04:35; [cost=0.961441ms] result=@Integer[8]
```

### 获取类的静态字段、调用类的静态函数的例子

```bash
watch demo.MathGame * '{params,@demo.MathGame@random.nextInt(100)}' -v -n 1 -x 2
[arthas@6527]$ watch demo.MathGame * '{params,@demo.MathGame@random.nextInt(100)}' -n 1 -x 2
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 5) cost in 34 ms, listenerId: 3
ts=2021-01-05 21:35:20; [cost=0.173966ms] result=@ArrayList[
    @Object[][
        @Integer[-138282],
    ],
    @Integer[89],
]
```

- 注意这里使用 `Thread.currentThread().getContextClassLoader()` 加载,使用精确`classloader` [ognl](ognl.md)更好。

### 排除掉指定的类

::: tip
watch/trace/monitor/stack/tt 命令都支持 `--exclude-class-pattern` 参数
:::

使用 `--exclude-class-pattern` 参数可以排除掉指定的类，比如：

```bash
watch javax.servlet.Filter * --exclude-class-pattern com.demo.TestFilter
```

### 不匹配子类

默认情况下 watch/trace/monitor/stack/tt 命令都会匹配子类。如果想不匹配，可以通过全局参数关掉。

```bash
options disable-sub-class true
```

### 使用 -v 参数打印更多信息

::: tip
watch/trace/monitor/stack/tt 命令都支持 `-v` 参数
:::

当命令执行之后，没有输出结果。有两种可能：

1. 匹配到的函数没有被执行
2. 条件表达式结果是 false

但用户区分不出是哪种情况。

使用 `-v`选项，则会打印`Condition express`的具体值和执行结果，方便确认。

比如：

```
$ watch -v -x 2 demo.MathGame print 'params' 'params[0] > 100000'
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 29 ms, listenerId: 11
Condition express: params[0] > 100000 , result: false
Condition express: params[0] > 100000 , result: false
Condition express: params[0] > 100000 , result: true
ts=2020-12-02 22:38:56; [cost=0.060843ms] result=@Object[][
    @Integer[200033],
    @ArrayList[
        @Integer[200033],
    ],
]
Condition express: params[0] > 100000 , result: true
ts=2020-12-02 22:38:57; [cost=0.052877ms] result=@Object[][
    @Integer[123047],
    @ArrayList[
        @Integer[29],
        @Integer[4243],
    ],
]
```

## 第9章 trace 命令（方法调用链路）

# trace

::: tip
方法内部调用路径，并输出方法路径上的每个节点上耗时
:::

`trace` 命令能主动搜索 `class-pattern`／`method-pattern` 对应的方法调用路径，渲染和统计整个调用链路上的所有性能开销和追踪调用链路。

## 参数说明

|            参数名称 | 参数说明                                                           |
| ------------------: | :----------------------------------------------------------------- |
|     _class-pattern_ | 类名表达式匹配                                                     |
|    _method-pattern_ | 方法名表达式匹配                                                   |
| _condition-express_ | 条件表达式                                                         |
|                 [E] | 开启正则表达式匹配，默认为通配符匹配                               |
|              `[n:]` | 命令执行次数，默认值为 100。                                       |
|             `#cost` | 方法执行耗时                                                       |
|              `[c:]` | 指定 classloader hash，只增强该 classloader 加载的类               |
|         `[m <arg>]` | 指定 Class 最大匹配数量，默认值为 50。长格式为`[maxMatch <arg>]`。 |

这里重点要说明的是`条件表达式`，`条件表达式`的构成主要由 ognl 表达式组成，所以你可以这样写`"params[0]<0"`，只要是一个合法的 ognl 表达式，都能被正常支持。

请参考[表达式核心变量](advice-class.md)中关于该节点的描述。

- [OGNL 复杂表达式使用技巧](ognl-express.md)

很多时候我们只想看到某个方法的 rt 大于某个时间之后的 trace 结果，现在 Arthas 可以按照方法执行的耗时来进行过滤了，例如`trace *StringUtils isBlank '#cost>100'`表示当执行时间超过 100ms 的时候，才会输出 trace 的结果。

::: tip
watch/stack/trace 这个三个命令都支持`#cost`
:::

## 注意事项

- `trace` 能方便的帮助你定位和发现因 RT 高而导致的性能问题缺陷，但其每次只能跟踪一级方法的调用链路。

  参考：[Trace 命令的实现原理](https://github.com/alibaba/arthas/issues/597)

- 3.3.0 版本后，可以使用动态 Trace 功能，不断增加新的匹配类，参考下面的示例。

- 目前不支持 `trace java.lang.Thread getName`，参考 issue: [#1610](https://github.com/alibaba/arthas/issues/1610) ，考虑到不是非常必要场景，且修复有一定难度，因此当前暂不修复

## 使用参考

### 启动 Demo

启动[快速入门](quick-start.md)里的`math-game`。

### trace 函数

```bash
$ trace demo.MathGame run
Press Q or Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 28 ms.
`---ts=2019-12-04 00:45:08;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[0.617465ms] demo.MathGame:run()
        `---[0.078946ms] demo.MathGame:primeFactors() #24 [throws Exception]

`---ts=2019-12-04 00:45:09;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[1.276874ms] demo.MathGame:run()
        `---[0.03752ms] demo.MathGame:primeFactors() #24 [throws Exception]
```

::: tip
结果里的 `#24`，表示在 run 函数里，在源文件的第`24`行调用了`primeFactors()`函数。
:::

### 指定 Class 匹配的最大数量

```bash
$ trace demo.MathGame run -m 1
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 412 ms, listenerId: 4
`---ts=2022-12-25 21:00:00;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@b4aac2
    `---[0.762093ms] demo.MathGame:run()
        `---[30.21% 0.230241ms] demo.MathGame:primeFactors() #46 [throws Exception]

`---ts=2022-12-25 21:00:10;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@b4aac2
    `---[0.315298ms] demo.MathGame:run()
        `---[13.95% 0.043995ms] demo.MathGame:primeFactors() #46 [throws Exception]
```

### 指定 ClassLoader 增强

当同名类被多个 classloader 加载时，可以先用 `sc -d` 查看 classloader hash，然后用 `-c` 指定增强的 classloader：

```bash
sc -d com.example.Foo
trace -c 3d4eac69 com.example.Foo bar
```

### trace 次数限制

如果方法调用的次数很多，那么可以用`-n`参数指定捕捉结果的次数。比如下面的例子里，捕捉到一次调用就退出命令。

```bash
$ trace demo.MathGame run -n 1
Press Q or Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 20 ms.
`---ts=2019-12-04 00:45:53;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[0.549379ms] demo.MathGame:run()
        +---[0.059839ms] demo.MathGame:primeFactors() #24
        `---[0.232887ms] demo.MathGame:print() #25

Command execution times exceed limit: 1, so command will exit. You can set it with -n option.
```

### 包含 jdk 的函数

- `--skipJDKMethod <value> ` skip jdk method trace, default value true.

默认情况下，trace 不会包含 jdk 里的函数调用，如果希望 trace jdk 里的函数，需要显式设置`--skipJDKMethod false`。

```bash
$ trace --skipJDKMethod false demo.MathGame run
Press Q or Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 60 ms.
`---ts=2019-12-04 00:44:41;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[1.357742ms] demo.MathGame:run()
        +---[0.028624ms] java.util.Random:nextInt() #23
        +---[0.045534ms] demo.MathGame:primeFactors() #24 [throws Exception]
        +---[0.005372ms] java.lang.StringBuilder:<init>() #28
        +---[0.012257ms] java.lang.Integer:valueOf() #28
        +---[0.234537ms] java.lang.String:format() #28
        +---[min=0.004539ms,max=0.005778ms,total=0.010317ms,count=2] java.lang.StringBuilder:append() #28
        +---[0.013777ms] java.lang.Exception:getMessage() #28
        +---[0.004935ms] java.lang.StringBuilder:toString() #28
        `---[0.06941ms] java.io.PrintStream:println() #28

`---ts=2019-12-04 00:44:42;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[3.030432ms] demo.MathGame:run()
        +---[0.010473ms] java.util.Random:nextInt() #23
        +---[0.023715ms] demo.MathGame:primeFactors() #24 [throws Exception]
        +---[0.005198ms] java.lang.StringBuilder:<init>() #28
        +---[0.006405ms] java.lang.Integer:valueOf() #28
        +---[0.178583ms] java.lang.String:format() #28
        +---[min=0.011636ms,max=0.838077ms,total=0.849713ms,count=2] java.lang.StringBuilder:append() #28
        +---[0.008747ms] java.lang.Exception:getMessage() #28
        +---[0.019768ms] java.lang.StringBuilder:toString() #28
        `---[0.076457ms] java.io.PrintStream:println() #28
```

### 根据调用耗时过滤

```bash
$ trace demo.MathGame run '#cost > 10'
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 41 ms.
`---ts=2018-12-04 01:12:02;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[12.033735ms] demo.MathGame:run()
        +---[0.006783ms] java.util.Random:nextInt()
        +---[11.852594ms] demo.MathGame:primeFactors()
        `---[0.05447ms] demo.MathGame:print()
```

::: tip
只会展示耗时大于 10ms 的调用路径，有助于在排查问题的时候，只关注异常情况
:::

- 是不是很眼熟，没错，在 JProfiler 等收费软件中你曾经见识类似的功能，这里你将可以通过命令就能打印出指定调用路径。 友情提醒下，`trace` 在执行的过程中本身是会有一定的性能开销，在统计的报告中并未像 JProfiler 一样预先减去其自身的统计开销。所以这统计出来有些许的不准，渲染路径上调用的类、方法越多，性能偏差越大。但还是能让你看清一些事情的。
- [12.033735ms] 的含义，`12.033735` 的含义是：当前节点在当前步骤的耗时，单位为毫秒
- [0,0,0ms,11]xxx:yyy() [throws Exception]，对该方法中相同的方法调用进行了合并，`0,0,0ms,11` 表示方法调用耗时，`min,max,total,count`；`throws Exception` 表明该方法调用中存在异常返回
- 这里存在一个统计不准确的问题，就是所有方法耗时加起来可能会小于该监测方法的总耗时，这个是由于 Arthas 本身的逻辑会有一定的耗时

### trace 多个类或者多个函数

trace 命令只会 trace 匹配到的函数里的子调用，并不会向下 trace 多层。因为 trace 是代价比较贵的，多层 trace 可能会导致最终要 trace 的类和函数非常多。

可以用正则表匹配路径上的多个类和函数，一定程度上达到多层 trace 的效果。

```bash
trace -E com.test.ClassA|org.test.ClassB method1|method2|method3
```

### 排除掉指定的类

使用 `--exclude-class-pattern` 参数可以排除掉指定的类，比如：

```bash
trace javax.servlet.Filter * --exclude-class-pattern com.demo.TestFilter
```

## 动态 trace

::: tip
3.3.0 版本后支持。
:::

打开终端 1，trace 上面 demo 里的`run`函数，可以看到打印出 `listenerId: 1`：

```bash
[arthas@59161]$ trace demo.MathGame run
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 112 ms, listenerId: 1
`---ts=2020-07-09 16:48:11;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[1.389634ms] demo.MathGame:run()
        `---[0.123934ms] demo.MathGame:primeFactors() #24 [throws Exception]

`---ts=2020-07-09 16:48:12;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[3.716391ms] demo.MathGame:run()
        +---[3.182813ms] demo.MathGame:primeFactors() #24
        `---[0.167786ms] demo.MathGame:print() #25
```

现在想要深入子函数`primeFactors`，可以打开一个新终端 2，使用`telnet localhost 3658`连接上 arthas，再 trace `primeFactors`时，指定`listenerId`。

```bash
[arthas@59161]$ trace demo.MathGame primeFactors --listenerId 1
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 34 ms, listenerId: 1
```

这时终端 2 打印的结果，说明已经增强了一个函数：`Affect(class count: 1 , method count: 1)`，但不再打印更多的结果。

再查看终端 1，可以发现 trace 的结果增加了一层，打印了`primeFactors`函数里的内容：

```bash
`---ts=2020-07-09 16:49:29;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[0.492551ms] demo.MathGame:run()
        `---[0.113929ms] demo.MathGame:primeFactors() #24 [throws Exception]
            `---[0.061462ms] demo.MathGame:primeFactors()
                `---[0.001018ms] throw:java.lang.IllegalArgumentException() #46

`---ts=2020-07-09 16:49:30;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    `---[0.409446ms] demo.MathGame:run()
        +---[0.232606ms] demo.MathGame:primeFactors() #24
        |   `---[0.1294ms] demo.MathGame:primeFactors()
        `---[0.084025ms] demo.MathGame:print() #25
```

通过指定`listenerId`的方式动态 trace，可以不断深入。另外 `watch`/`tt`/`monitor`等命令也支持类似的功能。

## trace 结果时间不准确问题

比如下面的结果里：`0.705196 > (0.152743 + 0.145825)`

```bash
$ trace demo.MathGame run -n 1
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 66 ms, listenerId: 1
`---ts=2021-02-08 11:27:36;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@232204a1
    `---[0.705196ms] demo.MathGame:run()
        +---[0.152743ms] demo.MathGame:primeFactors() #24
        `---[0.145825ms] demo.MathGame:print() #25
```

那么其它的时间消耗在哪些地方？

1. 没有被 trace 到的函数。比如`java.*` 下的函数调用默认会忽略掉。通过增加`--skipJDKMethod false`参数可以打印出来。

   ```bash
   $ trace demo.MathGame run --skipJDKMethod false
   Press Q or Ctrl+C to abort.
   Affect(class count: 1 , method count: 1) cost in 35 ms, listenerId: 2
   `---ts=2021-02-08 11:27:48;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@232204a1
       `---[0.810591ms] demo.MathGame:run()
           +---[0.034568ms] java.util.Random:nextInt() #23
           +---[0.119367ms] demo.MathGame:primeFactors() #24 [throws Exception]
           +---[0.017407ms] java.lang.StringBuilder:<init>() #28
           +---[0.127922ms] java.lang.String:format() #57
           +---[min=0.01419ms,max=0.020221ms,total=0.034411ms,count=2] java.lang.StringBuilder:append() #57
           +---[0.021911ms] java.lang.Exception:getMessage() #57
           +---[0.015643ms] java.lang.StringBuilder:toString() #57
           `---[0.086622ms] java.io.PrintStream:println() #57
   ```

2. 非函数调用的指令消耗。比如 `i++`, `getfield`等指令。

3. 在代码执行过程中，JVM 可能出现停顿，比如 GC，进入同步块等。

### 使用 -v 参数打印更多信息

::: tip
watch/trace/monitor/stack/tt 命令都支持 `-v` 参数
:::

当命令执行之后，没有输出结果。有两种可能：

1. 匹配到的函数没有被执行
2. 条件表达式结果是 false

但用户区分不出是哪种情况。

使用 `-v`选项，则会打印`Condition express`的具体值和执行结果，方便确认。

## 第10章 stack 命令（调用路径）

# stack

::: tip
输出当前方法被调用的调用路径
:::

很多时候我们都知道一个方法被执行，但这个方法被执行的路径非常多，或者你根本就不知道这个方法是从那里被执行了，此时你需要的是 stack 命令。

## 参数说明

|            参数名称 | 参数说明                                                           |
| ------------------: | :----------------------------------------------------------------- |
|     _class-pattern_ | 类名表达式匹配                                                     |
|    _method-pattern_ | 方法名表达式匹配                                                   |
| _condition-express_ | 条件表达式                                                         |
|                 [E] | 开启正则表达式匹配，默认为通配符匹配                               |
|              `[n:]` | 执行次数限制                                                       |
|              `[c:]` | 指定 classloader hash，只增强该 classloader 加载的类               |
|         `[m <arg>]` | 指定 Class 最大匹配数量，默认值为 50。长格式为`[maxMatch <arg>]`。 |

这里重点要说明的是观察表达式，观察表达式的构成主要由 ognl 表达式组成，所以你可以这样写`"{params,returnObj}"`，只要是一个合法的 ognl 表达式，都能被正常支持。

观察的维度也比较多，主要体现在参数 `advice` 的数据结构上。`Advice` 参数最主要是封装了通知节点的所有信息。

请参考[表达式核心变量](advice-class.md)中关于该节点的描述。

- [OGNL 复杂表达式使用技巧](ognl-express.md)

## 使用例子

### 启动 Demo

启动[快速入门](quick-start.md)里的`math-game`。

### stack

```bash
$ stack demo.MathGame primeFactors
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 36 ms.
ts=2018-12-04 01:32:19;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    @demo.MathGame.run()
        at demo.MathGame.main(MathGame.java:16)
```

### 指定 Class 最大匹配数量

```bash
$ stack demo.MathGame primeFactors -m 1
Press Q or Ctrl+C to abort.
Affect(class count:1 , method count:1) cost in 561 ms, listenerId: 5.
ts=2022-12-25 21:07:07;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@b4aac2
    @demo.MathGame.primeFactors()
        at demo.MathGame.run(MathGame.java:46)
        at demo.MathGame.main(MathGame.java:38)
```

### 指定 ClassLoader 增强

当同名类被多个 classloader 加载时，可以先用 `sc -d` 查看 classloader hash，然后用 `-c` 指定增强的 classloader：

```bash
sc -d com.example.Foo
stack -c 3d4eac69 com.example.Foo bar
```

### 据条件表达式来过滤

```bash
$ stack demo.MathGame primeFactors 'params[0]<0' -n 2
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 30 ms.
ts=2018-12-04 01:34:27;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    @demo.MathGame.run()
        at demo.MathGame.main(MathGame.java:16)

ts=2018-12-04 01:34:30;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    @demo.MathGame.run()
        at demo.MathGame.main(MathGame.java:16)

Command execution times exceed limit: 2, so command will exit. You can set it with -n option.
```

### 据执行时间来过滤

```bash
$ stack demo.MathGame primeFactors '#cost>5'
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 35 ms.
ts=2018-12-04 01:35:58;thread_name=main;id=1;is_daemon=false;priority=5;TCCL=sun.misc.Launcher$AppClassLoader@3d4eac69
    @demo.MathGame.run()
        at demo.MathGame.main(MathGame.java:16)
```

## 第11章 tt 命令（时间隧道）

# tt

::: tip
方法执行数据的时空隧道，记录下指定方法每次调用的入参和返回信息，并能对这些不同的时间下调用进行观测
:::

`watch` 虽然很方便和灵活，但需要提前想清楚观察表达式的拼写，这对排查问题而言要求太高，因为很多时候我们并不清楚问题出自于何方，只能靠蛛丝马迹进行猜测。

这个时候如果能记录下当时方法调用的所有入参和返回值、抛出的异常会对整个问题的思考与判断非常有帮助。

于是乎，TimeTunnel 命令就诞生了。

## 注意事项

- tt 命令的实现是：把函数的入参/返回值等，保存到一个`Map<Integer, TimeFragment>`里，默认的大小是 100。
- tt 相关功能在使用完之后，需要手动释放内存，否则长时间可能导致OOM。退出 arthas 不会自动清除 tt 的缓存 map。

## 使用参考

### 启动 Demo

启动[快速入门](quick-start.md)里的`math-game`。

### 记录调用

对于一个最基本的使用来说，就是记录下当前方法的每次调用环境现场。

```bash
$ tt -t demo.MathGame primeFactors
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 66 ms.
 INDEX   TIMESTAMP            COST(ms)  IS-RET  IS-EXP   OBJECT         CLASS                          METHOD
-------------------------------------------------------------------------------------------------------------------------------------
 1000    2018-12-04 11:15:38  1.096236  false   true     0x4b67cf4d     MathGame                       primeFactors
 1001    2018-12-04 11:15:39  0.191848  false   true     0x4b67cf4d     MathGame                       primeFactors
 1002    2018-12-04 11:15:40  0.069523  false   true     0x4b67cf4d     MathGame                       primeFactors
 1003    2018-12-04 11:15:41  0.186073  false   true     0x4b67cf4d     MathGame                       primeFactors
 1004    2018-12-04 11:15:42  17.76437  true    false    0x4b67cf4d     MathGame                       primeFactors
```

### 指定 Class 最大匹配数量

```bash
$ tt -t -m 1 demo.MathGame primeFactors
Press Q or Ctrl+C to abort.
Affect(class count:1 , method count:1) cost in 130 ms, listenerId: 1.
 INDEX   TIMESTAMP            COST(ms)  IS-RET  IS-EXP   OBJECT         CLASS                          METHOD
-------------------------------------------------------------------------------------------------------------------------------------
 1000    2022-12-25 19:41:45  2.629929  true    false    0x3bf400       MathGame                       primeFactors
 1001    2022-12-25 19:41:55  0.146161  false   true     0x3bf400       MathGame                       primeFactors
```

- 命令参数解析
  - `-t`

    tt 命令有很多个主参数，`-t` 就是其中之一。这个参数的表明希望记录下类 `demo.MathGame` 的 `primeFactors` 方法的每次执行情况。

  - `-n 3`

    当你执行一个调用量不高的方法时可能你还能有足够的时间用 `CTRL+C` 中断 tt 命令记录的过程，但如果遇到调用量非常大的方法，瞬间就能将你的 JVM 内存撑爆。

    此时你可以通过 `-n` 参数指定你需要记录的次数，当达到记录次数时 Arthas 会主动中断 tt 命令的记录过程，避免人工操作无法停止的情况。

  - `-m 1`

    通过 `-m` 参数指定 Class 匹配的最大数量，防止匹配到的 Class 数量太多导致 JVM 挂起，默认值是 50。

  - `-c <classloader hash>`

    当同名类被多个 classloader 加载时，可以用 `-c` 指定只增强某个 classloader 加载的类。可以先用 `sc -d className` 查看具体的 classloader hash。

- 表格字段说明

| 表格字段  | 字段解释                                                                                                                          |
| --------- | --------------------------------------------------------------------------------------------------------------------------------- |
| INDEX     | 时间片段记录编号，每一个编号代表着一次调用，后续 tt 还有很多命令都是基于此编号指定记录操作，非常重要。                            |
| TIMESTAMP | 方法执行的本机时间，记录了这个时间片段所发生的本机时间                                                                            |
| COST(ms)  | 方法执行的耗时                                                                                                                    |
| IS-RET    | 方法是否以正常返回的形式结束                                                                                                      |
| IS-EXP    | 方法是否以抛异常的形式结束                                                                                                        |
| OBJECT    | 执行对象的`hashCode()`，注意，曾经有人误认为是对象在 JVM 中的内存地址，但很遗憾他不是。但他能帮助你简单的标记当前执行方法的类实体 |
| CLASS     | 执行的类名                                                                                                                        |
| METHOD    | 执行的方法名                                                                                                                      |

- 条件表达式

  不知道大家是否有在使用过程中遇到以下困惑
  - Arthas 似乎很难区分出重载的方法
  - 我只需要观察特定参数，但是 tt 却全部都给我记录了下来

  条件表达式也是用 `OGNL` 来编写，核心的判断对象依然是 `Advice` 对象。除了 `tt` 命令之外，`watch`、`trace`、`stack` 命令也都支持条件表达式。

- 解决方法重载

  `tt -t *Test print params.length==1`

  通过制定参数个数的形式解决不同的方法签名，如果参数个数一样，你还可以这样写

  `tt -t *Test print 'params[1] instanceof Integer'`

- 解决指定参数

  `tt -t *Test print params[0].mobile=="13989838402"`

- 构成条件表达式的 `Advice` 对象

  前边看到了很多条件表达式中，都使用了 `params[0]`，有关这个变量的介绍，请参考[表达式核心变量](advice-class.md)

  复杂的筛选、投影、空值处理及 AI Agent 生成建议参见 [OGNL 复杂表达式使用技巧](ognl-express.md)。

### 检索调用记录

当你用 `tt` 记录了一大片的时间片段之后，你希望能从中筛选出自己需要的时间片段，这个时候你就需要对现有记录进行检索。

假设我们有这些记录

```bash
$ tt -l
 INDEX   TIMESTAMP            COST(ms)  IS-RET  IS-EXP   OBJECT         CLASS                          METHOD
-------------------------------------------------------------------------------------------------------------------------------------
 1000    2018-12-04 11:15:38  1.096236  false   true     0x4b67cf4d     MathGame                       primeFactors
 1001    2018-12-04 11:15:39  0.191848  false   true     0x4b67cf4d     MathGame                       primeFactors
 1002    2018-12-04 11:15:40  0.069523  false   true     0x4b67cf4d     MathGame                       primeFactors
 1003    2018-12-04 11:15:41  0.186073  false   true     0x4b67cf4d     MathGame                       primeFactors
 1004    2018-12-04 11:15:42  17.76437  true    false    0x4b67cf4d     MathGame                       primeFactors
                              9
 1005    2018-12-04 11:15:43  0.4776    false   true     0x4b67cf4d     MathGame                       primeFactors
Affect(row-cnt:6) cost in 4 ms.
```

我需要筛选出 `primeFactors` 方法的调用信息

```bash
$ tt -s 'method.name=="primeFactors"'
 INDEX   TIMESTAMP            COST(ms)  IS-RET  IS-EXP   OBJECT         CLASS                          METHOD
-------------------------------------------------------------------------------------------------------------------------------------
 1000    2018-12-04 11:15:38  1.096236  false   true     0x4b67cf4d     MathGame                       primeFactors
 1001    2018-12-04 11:15:39  0.191848  false   true     0x4b67cf4d     MathGame                       primeFactors
 1002    2018-12-04 11:15:40  0.069523  false   true     0x4b67cf4d     MathGame                       primeFactors
 1003    2018-12-04 11:15:41  0.186073  false   true     0x4b67cf4d     MathGame                       primeFactors
 1004    2018-12-04 11:15:42  17.76437  true    false    0x4b67cf4d     MathGame                       primeFactors
                              9
 1005    2018-12-04 11:15:43  0.4776    false   true     0x4b67cf4d     MathGame                       primeFactors
Affect(row-cnt:6) cost in 607 ms.
```

你需要一个 `-s` 参数。<span style="color:red;">同样的，搜索表达式的核心对象依旧是 `Advice` 对象。</span>

### 查看调用信息

对于具体一个时间片的信息而言，你可以通过 `-i` 参数后边跟着对应的 `INDEX` 编号查看到他的详细信息。

```bash
$ tt -i 1003
 INDEX            1003
 GMT-CREATE       2018-12-04 11:15:41
 COST(ms)         0.186073
 OBJECT           0x4b67cf4d
 CLASS            demo.MathGame
 METHOD           primeFactors
 IS-RETURN        false
 IS-EXCEPTION     true
 PARAMETERS[0]    @Integer[-564322413]
 THROW-EXCEPTION  java.lang.IllegalArgumentException: number is: -564322413, need >= 2
                      at demo.MathGame.primeFactors(MathGame.java:46)
                      at demo.MathGame.run(MathGame.java:24)
                      at demo.MathGame.main(MathGame.java:16)

Affect(row-cnt:1) cost in 11 ms.
```

### 重做一次调用

当你稍稍做了一些调整之后，你可能需要前端系统重新触发一次你的调用，此时得求爷爷告奶奶的需要前端配合联调的同学再次发起一次调用。而有些场景下，这个调用不是这么好触发的。

`tt` 命令由于保存了当时调用的所有现场信息，所以我们可以自己主动对一个 `INDEX` 编号的时间片自主发起一次调用，从而解放你的沟通成本。此时你需要 `-p` 参数。通过 `--replay-times` 指定
调用次数，通过 `--replay-interval` 指定多次调用间隔(单位 ms, 默认 1000ms)

```bash
$ tt -i 1004 -p
 RE-INDEX       1004
 GMT-REPLAY     2018-12-04 11:26:00
 OBJECT         0x4b67cf4d
 CLASS          demo.MathGame
 METHOD         primeFactors
 PARAMETERS[0]  @Integer[946738738]
 IS-RETURN      true
 IS-EXCEPTION   false
 COST(ms)         0.186073
 RETURN-OBJ     @ArrayList[
                    @Integer[2],
                    @Integer[11],
                    @Integer[17],
                    @Integer[2531387],
                ]
Time fragment[1004] successfully replayed.
Affect(row-cnt:1) cost in 14 ms.
```

你会发现结果虽然一样，但调用的路径发生了变化，由原来的程序发起变成了 Arthas 自己的内部线程发起的调用了。

### 观察表达式

`-w, --watch-express` 观察时空隧道使用`ognl` 表达式

- 使用[表达式核心变量](advice-class.md)中所有变量作为已知条件编写表达式。

```bash
[arthas@10718]$ tt -t demo.MathGame run -n 5
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 56 ms, listenerId: 1
 INDEX      TIMESTAMP                   COST(ms)     IS-RET     IS-EXP      OBJECT              CLASS                                     METHOD
----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 1000       2021-01-08 21:54:17         0.901091     true       false       0x7699a589          MathGame                                  run
[arthas@10718]$ tt -w 'target.illegalArgumentCount'  -x 1 -i 1000
@Integer[60]
Affect(row-cnt:1) cost in 7 ms.
```

- 获取类的静态字段、调用类的静态方法

```bash
[arthas@10718]$ tt -t demo.MathGame run -n 5
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 56 ms, listenerId: 1
 INDEX      TIMESTAMP                   COST(ms)     IS-RET     IS-EXP      OBJECT              CLASS                                     METHOD
----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 1000       2021-01-08 21:54:17         0.901091     true       false       0x7699a589          MathGame                                  run
[arthas@10718]$ tt -w '@demo.MathGame@random.nextInt(100)'  -x 1 -i 1000
@Integer[46]
```

注意这里使用 `com.taobao.arthas.core.advisor.Advice#getLoader`加载,使用精确`classloader` [ognl](ognl.md)更好。

高级用法 [获取 spring context 调用 bean 方法](https://github.com/alibaba/arthas/issues/482)

- 需要强调的点
  1. **ThreadLocal 信息丢失**

     很多框架偷偷的将一些环境变量信息塞到了发起调用线程的 ThreadLocal 中，由于调用线程发生了变化，这些 ThreadLocal 线程信息无法通过 Arthas 保存，所以这些信息将会丢失。

     一些常见的 CASE 比如：鹰眼的 TraceId 等。

  2. **引用的对象**

     需要强调的是，`tt` 命令是将当前环境的对象引用保存起来，但仅仅也只能保存一个引用而已。如果方法内部对入参进行了变更，或者返回的对象经过了后续的处理，那么在 `tt` 查看的时候将无法看到当时最准确的值。这也是为什么 `watch` 命令存在的意义。

### 通过索引删除指定的 tt 记录

```
tt -d -i 1001
```

### 清除所有的 tt 记录

```
tt --delete-all
```

## 第12章 ognl 命令（表达式执行）

# ognl

::: tip
执行 ognl 表达式
:::

从 3.0.5 版本增加

## 参数说明

|              参数名称 | 参数说明                                                         |
| --------------------: | :--------------------------------------------------------------- |
|             _express_ | 执行的表达式                                                     |
|                `[c:]` | 执行表达式的 ClassLoader 的 hashcode，默认值是 SystemClassLoader |
| `[classLoaderClass:]` | 指定执行表达式的 ClassLoader 的 class name                       |
|                   [x] | 结果对象的展开层次，默认值 1                                     |

## 使用参考

- [OGNL 复杂表达式使用技巧](ognl-express.md)，包含集合筛选、投影、分组、空值处理以及 AI Agent 生成与验证建议

调用静态函数：

```bash
$ ognl '@java.lang.System@out.println("hello")'
null
```

获取静态类的静态字段：

```bash
$ ognl '@demo.MathGame@random'
@Random[
    serialVersionUID=@Long[3905348978240129619],
    seed=@AtomicLong[125451474443703],
    multiplier=@Long[25214903917],
    addend=@Long[11],
    mask=@Long[281474976710655],
    DOUBLE_UNIT=@Double[1.1102230246251565E-16],
    BadBound=@String[bound must be positive],
    BadRange=@String[bound must be greater than origin],
    BadSize=@String[size must be non-negative],
    seedUniquifier=@AtomicLong[-3282039941672302964],
    nextNextGaussian=@Double[0.0],
    haveNextNextGaussian=@Boolean[false],
    serialPersistentFields=@ObjectStreamField[][isEmpty=false;size=3],
    unsafe=@Unsafe[sun.misc.Unsafe@28ea5898],
    seedOffset=@Long[24],
]
```

通过 hashcode 指定 ClassLoader：

```bash
$ classloader -t
+-BootstrapClassLoader
+-jdk.internal.loader.ClassLoaders$PlatformClassLoader@301ec38b
  +-com.taobao.arthas.agent.ArthasClassloader@472067c7
  +-jdk.internal.loader.ClassLoaders$AppClassLoader@4b85612c
    +-org.springframework.boot.loader.LaunchedURLClassLoader@7f9a81e8

$ ognl -c 7f9a81e8 @org.springframework.boot.SpringApplication@logger
@Slf4jLocationAwareLog[
    FQCN=@String[org.apache.commons.logging.LogAdapter$Slf4jLocationAwareLog],
    name=@String[org.springframework.boot.SpringApplication],
    logger=@Logger[Logger[org.springframework.boot.SpringApplication]],
]
$
```

注意 hashcode 是变化的，需要先查看当前的 ClassLoader 信息，提取对应 ClassLoader 的 hashcode。

对于只有唯一实例的 ClassLoader 可以通过 class name 指定，使用起来更加方便：

```bash
$ ognl --classLoaderClass org.springframework.boot.loader.LaunchedURLClassLoader  @org.springframework.boot.SpringApplication@logger
@Slf4jLocationAwareLog[
    FQCN=@String[org.apache.commons.logging.LogAdapter$Slf4jLocationAwareLog],
    name=@String[org.springframework.boot.SpringApplication],
    logger=@Logger[Logger[org.springframework.boot.SpringApplication]],
]
```

执行多行表达式，赋值给临时变量，返回一个 List：

```bash
$ ognl '#value1=@System@getProperty("java.home"), #value2=@System@getProperty("java.runtime.name"), {#value1, #value2}'
@ArrayList[
    @String[/opt/java/8.0.181-zulu/jre],
    @String[OpenJDK Runtime Environment],
]
```

## 第13章 monitor 命令（方法执行统计）

# monitor

::: tip
方法执行监控
:::

对匹配 `class-pattern`／`method-pattern`／`condition-express`的类、方法的调用进行监控。

`monitor` 命令是一个非实时返回命令.

实时返回命令是输入之后立即返回，而非实时返回的命令，则是不断的等待目标 Java 进程返回信息，直到用户输入 `Ctrl+C` 为止。

服务端是以任务的形式在后台跑任务，植入的代码随着任务的中止而不会被执行，所以任务关闭后，不会对原有性能产生太大影响，而且原则上，任何 Arthas 命令不会引起原有业务逻辑的改变。

## 监控的维度说明

|    监控项 | 说明                       |
| --------: | :------------------------- |
| timestamp | 时间戳                     |
|     class | Java 类                    |
|    method | 方法（构造方法、普通方法） |
|     total | 调用次数                   |
|   success | 成功次数                   |
|      fail | 失败次数                   |
|        rt | 平均 RT                    |
| fail-rate | 失败率                     |

## 参数说明

方法拥有一个命名参数 `[c:]`，意思是统计周期（cycle of output），拥有一个整型的参数值

|            参数名称 | 参数说明                                                           |
| ------------------: | :----------------------------------------------------------------- |
|     _class-pattern_ | 类名表达式匹配                                                     |
|    _method-pattern_ | 方法名表达式匹配                                                   |
| _condition-express_ | 条件表达式                                                         |
|                 [E] | 开启正则表达式匹配，默认为通配符匹配                               |
|              `[c:]` | 统计周期，默认值为 60 秒                                           |
|     `--classloader` | 指定 classloader hash，只增强该 classloader 加载的类               |
|                 [b] | 在**方法调用之前**计算 condition-express                           |
|         `[m <arg>]` | 指定 Class 最大匹配数量，默认值为 50。长格式为`[maxMatch <arg>]`。 |

`condition-express` 使用 OGNL 语法。可用变量参见[表达式核心变量](advice-class.md)，复杂条件的写法与排错方法参见 [OGNL 复杂表达式使用技巧](ognl-express.md)。

## 使用参考

```bash
$ monitor -c 5 demo.MathGame primeFactors
Press Ctrl+C to abort.
Affect(class-cnt:1 , method-cnt:1) cost in 94 ms.
 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:38  demo.MathGame  primeFactors  5      1        4     1.15        80.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:43  demo.MathGame  primeFactors  5      3        2     42.29       40.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:48  demo.MathGame  primeFactors  5      3        2     67.92       40.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:53  demo.MathGame  primeFactors  5      2        3     0.25        60.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:06:58  demo.MathGame  primeFactors  1      1        0     0.45        0.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2018-12-03 19:07:03  demo.MathGame  primeFactors  2      2        0     3182.72     0.00%
```

### 指定 Class 最大匹配数量

```bash
$ monitor -c 1 -m 1 demo.MathGame primeFactors
Press Q or Ctrl+C to abort.
Affect(class count:1 , method count:1) cost in 384 ms, listenerId: 6.
 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2022-12-25 21:12:58  demo.MathGame  primeFactors  1      1        0     0.18        0.00%

 timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2022-12-25 21:12:59  demo.MathGame  primeFactors  0      0        0     0.00       0.00%
```

### 指定 ClassLoader 增强

当同名类被多个 classloader 加载时，可以先用 `sc -d` 查看 classloader hash，然后用 `--classloader` 指定增强的 classloader（注意 `-c` 在 monitor 里表示统计周期）：

```bash
sc -d com.example.Foo
monitor --classloader 3d4eac69 com.example.Foo bar
```

### 计算条件表达式过滤统计结果(方法执行完毕之后)

```bash
monitor -c 5 demo.MathGame primeFactors "params[0] <= 2"
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 19 ms, listenerId: 5
 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
-----------------------------------------------------------------------------------------------
 2020-09-02 09:42:36  demo.MathGame  primeFactors    5       3       2      0.09       40.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:41  demo.MathGame  primeFactors    5       2       3      0.11       60.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:46  demo.MathGame  primeFactors    5       1       4      0.06       80.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:51  demo.MathGame  primeFactors    5       1       4      0.12       80.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:56  demo.MathGame  primeFactors    5       3       2      0.15       40.00%
```

### 计算条件表达式过滤统计结果(方法执行完毕之前)

```bash
monitor -b -c 5 com.test.testes.MathGame primeFactors "params[0] <= 2"
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 21 ms, listenerId: 4
 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:41:57  demo.MathGame  primeFactors    1       0        1      0.10      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:02  demo.MathGame  primeFactors    3       0        3      0.06      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:07  demo.MathGame  primeFactors    2       0        2      0.06      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:12  demo.MathGame  primeFactors    1       0        1      0.05      100.00%

 timestamp            class          method         total  success  fail  avg-rt(ms)  fail-rate
----------------------------------------------------------------------------------------------
 2020-09-02 09:42:17  demo.MathGame  primeFactors    2       0        2      0.10      100.00%
```

## 第14章 heapdump 命令（堆快照）

# heapdump

::: tip
dump java heap, 类似 jmap 命令的 heap dump 功能。
:::

## 使用参考

### dump 到指定文件

```bash
[arthas@58205]$ heapdump arthas-output/dump.hprof
Dumping heap to arthas-output/dump.hprof ...
Heap dump file created
```

::: tip
生成文件在`arthas-output`目录，可以通过浏览器下载： http://localhost:8563/arthas-output/
:::

### 只 dump live 对象

```bash
[arthas@58205]$ heapdump --live /tmp/dump.hprof
Dumping heap to /tmp/dump.hprof ...
Heap dump file created
```

## dump 到临时文件

```bash
[arthas@58205]$ heapdump
Dumping heap to /var/folders/my/wy7c9w9j5732xbkcyt1mb4g40000gp/T/heapdump2019-09-03-16-385121018449645518991.hprof...
Heap dump file created
```

## 第15章 profiler 命令（性能剖析）

# profiler

::: tip
使用[async-profiler](https://github.com/jvm-profiling-tools/async-profiler)生成火焰图
:::

`profiler` 命令支持生成应用热点的火焰图。本质上是通过不断的采样，然后把收集到的采样结果生成火焰图。

`profiler` 命令基本运行结构是 `profiler action [actionArg]`

`profiler` 命令的格式基本与上游项目 [async-profiler](https://github.com/async-profiler/async-profiler) 保持一致，详细的使用方式可参考上游项目的 README、Github Disscussions 以及其他文档资料。

## 参数说明

|    参数名称 | 参数说明                                                        |
| ----------: | :-------------------------------------------------------------- |
|    _action_ | 要执行的操作                                                    |
| _actionArg_ | 属性名模式                                                      |
|        [i:] | 采样间隔（单位：ns）（默认值：10'000'000，即 10 ms）            |
|        [f:] | 将输出转储到指定路径                                            |
|        [d:] | 运行评测指定秒                                                  |
|        [e:] | 要跟踪哪个事件（cpu, alloc, lock, cache-misses 等），默认是 cpu |

## 启动 profiler

```
$ profiler start
Started [cpu] profiling
```

::: tip
默认情况下，生成的是 cpu 的火焰图，即 event 为`cpu`。可以用`--event`参数指定其他性能分析模式，见下文。
:::

## 获取已采集的 sample 的数量

```
$ profiler getSamples
23
```

## 查看 profiling 状态

```bash
$ profiler status
[cpu] profiling is running for 4 seconds
```

可以查看当前 profiler 在采样哪种`event`和采样时间。

## 查看 profiler 自身的内存占用

```
$ profiler meminfo
Call trace storage:   10244 KB
      Dictionaries:      72 KB
        Code cache:   12890 KB
------------------------------
             Total:   23206 KB
```

## 停止 profiler

### 生成火焰图格式结果

默认情况下，结果是 [Flame Graph](https://github.com/BrendanGregg/FlameGraph) 格式的 `html` 文件，也可以用 `-o` 或 `--format` 参数指定其他内容格式，包括 flat、traces、collapsed、flamegraph、tree、jfr、md。

### 生成 LLM 友好的 Markdown 报告

当 `--format md`（或 `md=N`）时，Arthas 会基于 async-profiler 的 `collapsed` 输出生成结构化的 Markdown，便于直接复制到 LLM 或在终端里 grep。报告包含：

- Top N Hotspots（self）
- Call Tree（调用树，按采样占比排序截断）
- Function Details（每个热点函数的 Top stacks）

```bash
$ profiler stop --format md
```

指定输出文件（可选）：

```bash
$ profiler stop --format md --file /tmp/profile.md
```

如果 `--file` 以 `.md` 结尾且未指定 `--format`，将自动推断为 Markdown 输出：

```bash
$ profiler stop --file /tmp/profile.md
```

指定 TopN（可选，默认 10）：

```bash
$ profiler stop --format md=20
```

```bash
$ profiler stop --format flamegraph
profiler output file: /tmp/test/arthas-output/20211207-111550.html
OK
```

在`--file`参数指定的文件名后缀为 `html` 或 `jfr` 时，文件格式可以被推断出来。比如`--file /tmp/result.html` 将自动生成火焰图。

## 通过浏览器查看 arthas-output 下面的 profiler 结果

默认情况下，arthas 使用 3658 端口，则可以打开： [http://localhost:3658/arthas-output/](http://localhost:3658/arthas-output/) 查看到`arthas-output`目录下面的 profiler 结果：

![](/images/arthas-output.jpg)

点击可以查看具体的结果：

![](/images/arthas-output-svg.jpg)

::: tip
如果是 chrome 浏览器，可能需要多次刷新。
:::

## profiler 支持的 events

在不同的平台，不同的 OS 下面，支持的 events 各有不同。比如在 macos 下面：

```bash
$ profiler list
Basic events:
  cpu
  alloc
  lock
  wall
  itimer
  ctimer
```

在 linux 下面

```bash
$ profiler list
Basic events:
  cpu
  alloc
  lock
  wall
  itimer
  ctimer
Java method calls:
  ClassName.methodName
Perf events:
  page-faults
  context-switches
  cycles
  instructions
  cache-references
  cache-misses
  branch-instructions
  branch-misses
  bus-cycles
  L1-dcache-load-misses
  LLC-load-misses
  dTLB-load-misses
  rNNN
  pmu/event-descriptor/
  mem:breakpoint
  trace:tracepoint
  kprobe:func
  uprobe:path
```

如果遇到 OS 本身的权限/配置问题，然后缺少部分 event，可以参考 [async-profiler 的文档](https://github.com/jvm-profiling-tools/async-profiler)。

可以用`--event`参数指定要采样的事件，比如 `alloc` 表示分析内存分配情况：

```bash
$ profiler start --event alloc
```

## 恢复采样

```bash
$ profiler resume
Started [cpu] profiling
```

`start`和`resume`的区别是：`start`会清除已有的分析结果重新开始，`resume`则会保留已有的结果，将新的分析结果附加到已有结果中。

通过执行`profiler getSamples`可以查看 samples 的数量来验证。

## Dump 分析结果

```bash
$ profiler dump
OK
```

`dump` action 将性能分析的结果保存到默认文件或指定的文件中，但 profiling 过程不会停止。例如，如果使用 `start` action 启动 profiling，5 秒后执行 `dump` action，2 秒后再次执行 `dump` action，将会得到 2 个结果文件，第一个文件包括 0\~5 秒的分析结果，第二个文件包括 0\~7 秒的分析结果。

## 使用`execute`来执行复杂的命令

比如开始采样：

```bash
profiler execute 'start,framebuf=5000000'
```

停止采样，并保存到指定文件里：

```bash
profiler execute 'stop,file=/tmp/result.html'
```

具体的格式参考： [arguments.cpp](https://github.com/async-profiler/async-profiler/blob/v4.4/src/arguments.cpp#L52)

## 查看所有支持的 action

```bash
$ profiler actions
Supported Actions: [resume, dumpCollapsed, getSamples, start, list, version, execute, meminfo, stop, load, dumpFlat, dump, actions, dumpTraces, status]
```

## 查看版本

```bash
$ profiler version
async-profiler 4.4
Copyright The async-profiler authors
```

## 配置 Java 栈深度

可以使用 `-j` 或 `--jstackdepth` 选项指定最大 Java 栈深度。如果指定值大于默认值 2048，该选项会被忽略。当你不希望看到特别深的栈轨迹的时候，这个选项会很有用，以下是一个使用样例：

```bash
profiler start -j 256
```

## 各线程分别进行 profiling

可以使用 `-t` 或 `--threads` 标志选项令 profiling 对各线程分别进行，每个栈轨迹都会以指示单个线程的帧结束。

```bash
profiler start -t
```

## 配置 include/exclude 来过滤数据

如果应用比较复杂，生成的内容很多，想只关注部分 stack traces，可以通过 `--include/--exclude` 过滤 stack traces，`--include` 表示定义的匹配表达式必须出现在 stack traces，相反 `--exclude` 表示定义的匹配表达式一定不会出现在 stack traces。 匹配表达式可以以`*`开始或者结束,`*` 表示任何（可能为空）字符序列。 比如

```bash
profiler stop --include 'java/*' --include 'com/demo/*' --exclude '*Unsafe.park*'
```

> `--include/--exclude` 都支持多次设置，但是需要配置在命令行的最后。也可使用短参数格式 `-I/-X`。
> 注意`--include/--exclude`只支持在`stop`action或者带有`-d`/`--duration`参数的`start`action中指定，否则不生效。

## 指定执行时间

比如，希望 profiler 执行 300 秒自动结束，可以用 `-d`/`--duration` 参数为 start action 指定时间：

```bash
profiler start --duration 300
```

## 生成 jfr 格式结果

> 注意，jfr 只支持在 `start`时配置。如果是在`stop`时指定，则不会生效。

```
profiler start --file /tmp/test.jfr
profiler start -o jfr
```

`file`参数支持一些变量：

- 时间戳： `--file /tmp/test-%t.jfr`
- 进程 ID： `--file /tmp/test-%p.jfr`

生成的结果可以用支持 jfr 格式的工具来查看。比如：

- JDK Mission Control ： https://github.com/openjdk/jmc
- JProfiler ： https://github.com/alibaba/arthas/issues/1416

## 控制分析结果的格式

使用 `-s` 选项将结果中的 Fully qualified name 替换为简单名称，如 `demo.MathGame.main` 替换为 `MathGame.main`。使用 `-g` 选项指定输出方法签名，如 `demo.MathGame.main` 替换为 `demo.MathGame.main([Ljava/lang/String;)V`。此外还有许多可调整分析结果格式的选项，可参考 [async-profiler 的 README 文档](https://github.com/async-profiler/async-profiler#readme) 以及 [async-profiler 的 Github Discussions](https://github.com/async-profiler/async-profiler/discussions) 等材料。

例如，以下命令中，`-s` 将输出中的类名称指定为简短格式，`-g` 显示方法的完整签名，`-a` 标注出 Java 方法，`-l` 为原生方法增加库名称，`--title` 为生成火焰图页面指定标题，`--minwidth` 将过滤火焰图中宽度为 15% 以下的帧，`--reverse` 将火焰图倒置。

```
profiler stop -s -g -a -l --title <flametitle> --minwidth 15 --reverse
```

## 生成的火焰图里的 unknown

- https://github.com/jvm-profiling-tools/async-profiler/discussions/409

## 配置 locks/allocations 模式的阈值

当使用 lock 或 alloc event 进行 profiling 时，可以使用 `--lock` 或 `--alloc` 配置阈值，比如下列命令：

```bash
profiler start -e lock --lock 10ms
profiler start -e alloc --alloc 2m
```

会记录竞争时间超过 10ms 的锁（如果不指定时间单位，则使用 ns 为单位），或者以 2MB 的单位记录对内存的分配。

## 配置 JFR 块

当使用 JFR 作为输出格式时，可以使用 `--chunksize` 或 `--chunktime` 配置单个 JFR 块的大致容量（以 byte 为单位，默认 100 MB）和时间限制（默认值为 1 小时），比如：

```bash
profiler start -f profile.jfr --chunksize 100m --chunktime 1h
```

## 将线程按照调度策略分组

可以使用 `--sched` 标志选项将输出结果按照 Linux 线程调度策略分组，策略包括 BATCH/IDLE/OTHER。例如：

```bash
profiler start --sched
```

火焰图的倒数第二行会标记不同的调度策略。

## 仅用未销毁对象构建内存分析结果

使用 `--live` 标志选项在内存分析结果中仅保留那些在分析过程结束时仍未被 JVM 回收的对象。该选项在排查 Java 堆内存泄露问题时比较有用。

```bash
profiler start --live
```

## 配置收集 C 栈帧的方法

使用 `--cstack MODE` 配置收集 native 帧的方法。候选模式有 fp (Frame Pointer), dwarf (DWARF unwind info), vm、vmx (HotSpot VM Structs)，以及 no (不收集 native 栈帧).

默认情况下，C 栈帧会出现在 cpu、ctimer、wall-clock、perf-events 模式中，而 Java 级别的 event 比如 alloc 和 lock 只收集 Java stack。

```bash
profiler --cstack fp
```

此命令将收集 native 栈帧的 Frame Pointer 信息。

## 当指定 Native 函数执行时开始/停止 Profiling

使用 `--begin function` 和 `--end function` 选项，可以在指定的 Native 函数被执行时启动或终止性能分析。主要用途是分析特定的 JVM 阶段，比如 GC 和 Safepoint。需要使用特定 JVM 实现中的 Native 函数名，比如在 HotSpot JVM 中的 SafepointSynchronize::begin 和 SafepointSynchronize::end。

### Time-to-Safepoint Profiling

选项 `--ttsp` 实际上是 `--begin SafepointSynchronize::begin --end RuntimeService::record_safepoint_synchronized` 的一个别名。它是一种约束，而不是独立的事件类型。无论选择哪种事件，Profiler 都可以正常工作，但只有在 VM 操作和 Safepoint 请求之间的事件会被记录下来。

现在，当使用 `--ttsp` 选项并指定 JFR 输出格式时，`profiler` 会在生成的 JFR 文件中自动包含 profiler.Window 事件。这些事件表示每次 Time-to-Safepoint 暂停的时间区间，使您无需依赖 JVM 日志即可分析这些暂停。

示例

```bash
profiler start --begin SafepointSynchronize::begin --end RuntimeService::record_safepoint_synchronized
profiler start --ttsp --format jfr
```

生成的 JFR 文件将包含 profiler.Window 事件，可以使用 JDK Mission Control 等工具查看和分析这些事件。

**注意事项:**

- profiler.Window 事件是通用的事件，适用于任何使用 --begin 和 --end 触发器的时间窗口，不仅限于 Safepoint 暂停。

- 在分析长时间的 Safepoint 暂停时，profiler.Window 事件可帮助您识别造成延迟的原因。

- 当使用 --ttsp 选项时，请确保使用 JFR 输出格式，以便能够生成并查看 profiler.Window 事件。

## 使用 profiler 记录的 event 生成 JFR 文件

用 `--jfrsync CONFIG` 选项可以指定配置启动 Java Flight Recording，输出的 jfr 文件会包含所有常规的 JFR event，但采样的来源是由 profiler 提供的。

CONFIG 参数:

- 预置配置：CONFIG 可以是 profile，表示使用 $JAVA_HOME/lib/jfr 目录下预置的 profile 配置。
- 自定义配置文件：CONFIG 也可以是自定义的 JFR 配置文件（.jfc），此选项的值采用与 jcmd JFR.start 命令的 settings 选项相同的格式。
- 指定 JFR 事件列表：现在，可以直接在 --jfrsync 中指定要启用的 JFR 事件列表，而无需创建 .jfc 文件。要指定事件列表，请以 + 开头，多个事件用 + 分隔。

示例：

使用预置的 profile 配置启动 JFR：

```bash
profiler start -e cpu --jfrsync profile -f combined.jfr
```

直接指定 JFR 事件列表，例如启用 jdk.YoungGarbageCollection 和 jdk.OldGarbageCollection 事件：

```bash
profiler start -e cpu --jfrsync +jdk.YoungGarbageCollection+jdk.OldGarbageCollection -f combined.jfr
```

**注意事项**

- 当指定事件列表时，由于逗号 , 用于分隔不同的选项，因此事件之间使用加号 + 分隔。
- 如果 --jfrsync 参数不以 + 开头，则被视为预置配置名或 .jfc 配置文件的路径。
- 直接指定事件列表在目标应用运行在容器中时特别有用，无需额外的文件操作。

## 周期性保存结果

使用 `--loop TIME` 可以持续运行 profiler 并周期性保存结果。选项格式可以是具体时间 hh:mm:ss 或以秒、分钟、小时或天计算的时间间隔。需要确保指定的输出文件名中包含时间戳，否则每次输出的结果都会覆盖上次保存的结果。以下命令持续执行 profiling 并将每个小时内的记录保存到一个 jfr 文件中。

> 如果没有指定 `-f` 参数，则不会保存任何内容。如果 `-f` 参数里没有 `%t`，则会循环保存到同一个文件里。

```bash
profiler start --loop 1h -f /var/log/profile-%t.jfr
```

## `--timeout` 选项

```bash
profiler start --timeout 300s
```

这个选项指定 profiling 自动在多久后停止。该选项和 `--loop` 选项的格式一致，可以是时间点，也可以是一个时间间隔。这两个选项都是用于 `start` action。可参考 [async-profiler docs](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilerOptions.md) 了解更多信息。

## `--wall` 选项

通过 --wall 选项，可以同时进行 CPU 和 Wall Clock 的性能分析。

1. 这种联合分析有助于更全面地识别和理解应用程序的性能瓶颈。
2. 允许用户独立于 CPU 分析设置 Wall Clock 分析的采样间隔。比如，可以通过设置 -e cpu -i 10 --wall 200，将 CPU 采样间隔设为 10 毫秒，墙钟采样间隔设为 200 毫秒。
3. 联合进行 CPU 和 Wall Clock 分析时，输出格式必须设置为 jfr。这一格式支持记录线程的状态信息（如 STATE_RUNNABLE 或 STATE_SLEEPING），从而区分不同类型的采样事件。

可参考 [async-profiler Github pr#740](https://github.com/async-profiler/async-profiler/issues/740) 了解更多信息。

影响：

Linux 平台: 这个新功能仅在 Linux 平台上有效。macOS 上的 CPU 分析引擎已经基于 Wall clock 模式，因此没有额外的收益。
性能开销: 启用 Wall clock 分析会增加性能开销，因此在同时分析 CPU 和 Wall clock 时，建议增加 Wall clock 的间隔。

```bash
profiler start -e cpu -i 10 --wall 100 -f out.jfr
```

## `ctimer`事件

`ctimer` 事件是一种新的 CPU 采样模式，基于 `timer_create`，提供了无需 `perf_events` 的精确 CPU 采样。

在某些情况下，`perf_events` 可能不可用，例如由于 `perf_event_paranoid` 设置或 `seccomp` 限制，或者在容器环境中。虽然 itimer 事件可以在容器中工作，但可能存在采样不准确的问题。

`ctimer` 事件结合了 `cpu` 和 `itimer` 的优点：

- 高准确性：提供精确的 CPU 采样。
- 容器友好：默认在容器中可用。
- 低资源消耗：不消耗文件描述符。

**请注意，`ctimer` 事件目前仅在 `Linux` 上支持，不支持 `macOS`。**
可参考 [async-profiler Github Issues](https://github.com/async-profiler/async-profiler/issues/855) 了解更多信息。

示例：

```bash
profiler start -e ctimer -o jfr -f ./out-test.jfr
```

## `vtable`特性

在某些应用程序中，大量的 CPU 时间花费在调用 `megamorphic` 的虚方法或接口方法上，这在性能分析中显示为 `vtable stub` 或 `itable stub`。这无法帮助我们了解特定调用点为何是`megamorphic` 以及如何优化它。

vtable 特性可以在` vtable stub` 或 `itable stub` 之上添加一个伪帧，显示实际调用的对象类型。这有助于清楚地了解在特定调用点，不同接收者的比例。

该特性默认禁用，可以通过 `-F vtable` 选项启用（或使用 `features=vtable`）。
可参考 [async-profiler Github Issues](https://github.com/async-profiler/async-profiler/issues/736) 了解更多信息。

示例：

```bash
profiler start -F vtable
```

## `comptask` 特性

`profiler` 采样 JIT 编译器线程以及 Java 线程，可以显示 JIT 编译所消耗的 CPU 百分比。然而，Java 方法的编译资源消耗各不相同，了解哪些特定的 Java 方法在编译时消耗最多的 CPU 时间非常有用。

`comptask` 特性可以在 `C1/C2` 的堆栈跟踪中添加一个虚拟帧，显示当前正在编译的任务，即正在编译的 Java 方法。

该特性默认禁用，可以通过` -F comptask` 选项启用（或使用 `features=comptask`）。
可参考 [async-profiler Github Issues](https://github.com/async-profiler/async-profiler/issues/777) 了解更多信息。

示例：

```bash
profiler start -F comptask
```

## 配置替代的分析信号

`profiler` 使用 `POSIX` 信号来进行性能分析。默认情况下，`SIGPROF` 用于 `CPU` 分析，`SIGVTALRM` 用于 `Wall-Clock` 分析。然而，如果应用程序也使用这些信号，或者希望同时运行多个 `profiler` 实例，这可能会导致信号冲突。

现在，可以使用 `signal` 参数来配置用于分析的信号，以避免冲突。
可参考 [async-profiler Github Issues](https://github.com/async-profiler/async-profiler/issues/759) 了解更多信息。

语法

```bash
profiler start --signal <信号号码>
```

如果需要分别指定 CPU 和 Wall-Clock 分析的信号，可以使用以下语法：

```bash
profiler start --signal <CPU信号号码>/<Wall信号号码>
```

## `--clock` 选项

`--clock` 选项允许用户控制用于采样时间戳的时钟源。这对于需要将 `profiler` 的数据与其他工具的数据进行时间戳对齐的场景非常有用。

用法

```bash
profiler start --clock <tsc|monotonic>
```

参数

- `tsc`：使用 CPU 的时间戳计数器（`RDTSC`）。这是默认选项，提供高精度的时间戳。
- `monotonic`：使用操作系统的单调时钟（`CLOCK_MONOTONIC`）。这有助于在多种数据源之间对齐时间戳。
  可参考 [async-profiler Github Issues](https://github.com/async-profiler/async-profiler/issues/723) 了解更多信息。

示例 :

使用 `CLOCK_MONOTONIC` 作为时间戳源：

```bash
profiler start --clock monotonic
```

**注意事项:**

- 当需要将 `profiler` 的数据与其他使用 `CLOCK_MONOTONIC` 的工具（例如 `perf`）的数据进行对齐时，使用 `--clock monotonic`。
- 在使用 `jfrsync` 模式时，请谨慎使用 `--clock` 选项，因为 JVM 和 `profiler` 可能使用不同的时间戳源，这可能导致结果不一致。

## `--norm` 选项

在 Java 20 及更早的版本中，编译器为 `lambda` 表达式生成的方法名称包含唯一的数字后缀。例如，同一代码位置定义的 `lambda` 表达式，可能会生成多个不同的帧名称，因为每个 `lambda` 方法的名称都会附加一个唯一的数字后缀（如 `lambda$method$0`、`lambda$method$1` 等）。这会导致逻辑上相同的堆栈无法在火焰图中合并，增加了性能分析的复杂性。

为了解决这个问题，`profiler` 新增了 `--norm` 选项，可以在生成输出时自动规范化方法名称，去除这些数字后缀，使相同的堆栈能够正确地合并。
可参考 [async-profiler Github Issues](https://github.com/async-profiler/async-profiler/issues/832) 了解更多信息。

**示例:**

生成规范化的火焰图:

```bash
profiler start --norm
```
