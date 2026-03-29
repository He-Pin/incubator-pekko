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

| 操作符                            | 吞吐量 (ops/s) |
|-----------------------------------|--------------|
| statefulMapConcat (oldZipWithIndex) | ~1,823,412   |
| statefulMap                        | ~4,612,342   |
| gather (public)                    | ~4,389,235   |
| gather (OneToOneGatherer)          | ~4,867,235   |
| zipWithIndex (native)              | ~5,234,513   |

- `gather (OneToOneGatherer)` 性能接近原生 `zipWithIndex`，与 `statefulMap` 基本持平。
- `gather (public)` 略低于 `statefulMap`，因其内部需维护单输出模式的 singleCollector 路径。
- 完整 JMH 跑分数据见 `bench-jmh/results/ZipWithIndexBenchmark-results.json`。

## 结论与建议
- `gather` 操作符提供了比 `statefulMap` 更低分配开销的 API 设计，适合对性能敏感的场景。
- 对于一对一映射场景，`OneToOneGatherer` 提供了与原生 `zipWithIndex` 接近的性能。
- 现有 `statefulMap`/`statefulMapConcat` API 保持不变，`gather` 作为新增的 opt-in 高性能操作符。

## 参考文件
- `stream/src/main/scala/org/apache/pekko/stream/impl/fusing/Ops.scala`
- `stream/src/main/scala/org/apache/pekko/stream/scaladsl/Gather.scala`
- `bench-jmh/src/main/scala/org/apache/pekko/stream/ZipWithIndexBenchmark.scala`
- `bench-jmh/results/ZipWithIndexBenchmark-results.json`
- JDK 24 Gatherer 官方文档
- SmallRye Mutiny 源码
