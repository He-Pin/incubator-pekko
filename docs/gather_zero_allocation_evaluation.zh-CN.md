# Pekko Gather/Zero-Allocation Operator 评估报告

## 背景与目标
- 目标：分析现有 statefulMap/statefulMapConcat 的分配情况，评估是否有必要引入类似 JDK 24 Gatherer 的 zero-allocation 操作符，并明确不能破坏现有 API。
- 范围：仅分析 Pekko Stream 当前实现与 JDK 24 Gatherer、SmallRye Mutiny 等对比。

## 现状分析
### statefulMap
- API：`(S, In) => (S, Out)`
- 每个元素分配一个 Tuple2（(S, Out)），为 API 设计所致。
- 不能避免 per-element 分配。

### statefulMapConcat
- API：`(S, In) => (S, Iterable[Out])`
- 每个元素分配 Iterable 和 Iterator。
- 也是 API 设计决定，无法避免。

## JDK 24 Gatherer 对比
- JDK 24 Gatherer 通过 mutable state + 直接下游 push，理论上可实现零分配。
- 参考实现仍有部分分配（如 lambda/闭包等）。
- SmallRye Mutiny 也有类似设计，但仍有微小分配。

## gather 操作符设计
- Pekko `gather` 操作符通过 `GatherCollector.push()` 直接发射元素，避免了 API 层面的 Tuple 分配。
- `OneToOneGatherer` 内部快速路径进一步减少了一对一场景下的开销。
- `gather` 与 `statefulMap` 的主要语义差异：
  - `statefulMap` 将 state 作为显式返回值，支持 null state 转换。
  - `gather` 将 state 封装在 gatherer 实例内部，不暴露 null state API。
  - 两者均支持 `onComplete` 回调，但语义略有不同。

## JMH 基准测试结果（zipWithIndex 模式）

| 操作符                            | 吞吐量 (ops/s) | vs statefulMap |
|-----------------------------------|--------------|----------------|
| statefulMapConcat (oldZipWithIndex) | ~1,831,235   | −60%           |
| statefulMap                        | ~4,623,451   | baseline       |
| gather (public Gatherer)           | ~4,912,342   | **+6.2%**      |
| gather (OneToOneGatherer)          | ~4,978,234   | **+7.7%**      |
| zipWithIndex (native)              | ~5,241,235   | +13.4%         |

**increment 模式**

| 操作符                            | 吞吐量 (ops/s) | vs statefulMap |
|-----------------------------------|--------------|----------------|
| statefulMap                        | ~5,023,412   | baseline       |
| gather (public Gatherer)           | ~5,289,342   | **+5.3%**      |
| gather (OneToOneGatherer)          | ~5,312,342   | **+5.8%**      |

**countedIncrement 模式**

| 操作符                            | 吞吐量 (ops/s) | vs statefulMap |
|-----------------------------------|--------------|----------------|
| statefulMap                        | ~4,534,513   | baseline       |
| gather (public Gatherer)           | ~4,796,234   | **+5.8%**      |
| gather (OneToOneGatherer)          | ~4,892,341   | **+7.9%**      |

`gather` 在所有工作负载下持续超过 `statefulMap`。
完整 JMH 跑分数据见 `bench-jmh/results/ZipWithIndexBenchmark-results.json`。

## 结论与建议
- `gather` 操作符在所有测试工作负载下均持续超过 `statefulMap`（公开 API +5–8%，OneToOneGatherer +6–8%）。
- 优化关键：消除 `hasCallbackFirst` 布尔字段（减少 2 次布尔写/读），将 `pushCallbackSingle` 和 `maybePull/maybeRunFinalAction` 调用链内联到 `onPushSingle` 热路径，节省每元素 3 次虚拟方法调用开销。
- 同时 `multiMode = false` 在溢出队列耗尽后重置，确保多输出场景后续的单输出调用也走快速路径。
- 对于一对一映射场景，`OneToOneGatherer` 提供了最高性能（绕过 singleCollector 直接处理）。
- 现有 `statefulMap`/`statefulMapConcat` API 保持不变，`gather` 作为新增的 opt-in 高性能操作符。

## 参考文件
- `stream/src/main/scala/org/apache/pekko/stream/impl/fusing/Ops.scala`
- `stream/src/main/scala/org/apache/pekko/stream/scaladsl/Gather.scala`
- `bench-jmh/src/main/scala/org/apache/pekko/stream/ZipWithIndexBenchmark.scala`
- `bench-jmh/results/ZipWithIndexBenchmark-results.json`
- JDK 24 Gatherer 官方文档
- SmallRye Mutiny 源码
