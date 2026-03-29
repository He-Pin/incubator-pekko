# Sink.watchTermination

Wraps a given `Sink` with a termination watcher, materializing to a @scala[`Future[Done]`] @java[`CompletionStage<Done>`] that signals when the stream terminates.

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.watchTermination](Sink$) { scala="#watchTermination[T,M,M2](sink:org.apache.pekko.stream.scaladsl.Sink[T,M])(matF:(M,scala.concurrent.Future[org.apache.pekko.Done])=&gt;M2):org.apache.pekko.stream.scaladsl.Sink[T,M2]" java="#watchTermination(org.apache.pekko.stream.javadsl.Sink,org.apache.pekko.japi.function.Function2)" }


## Description

Wraps a given `Sink` with a termination watcher that materializes to a @scala[`Future[Done]`] @java[`CompletionStage<Done>`] which completes when the stream connected to this sink terminates — whether by normal completion, cancellation, or failure. The `matF` function combines the original sink's materialized value with this termination signal into a new materialized value.

This is useful when you need to know when a stream has finished processing while also preserving the wrapped sink's materialized value. For example, you can use it to trigger cleanup logic, send notifications, or coordinate with other parts of your system upon stream termination.

Unlike @ref[`Source.watchTermination`](../Source-or-Flow/watchTermination.md) or @ref[`Flow.watchTermination`](../Source-or-Flow/watchTermination.md) which operate inline on the stream, `Sink.watchTermination` wraps an existing `Sink` and is applied at the end of the stream.

## Examples

Scala
:   @@snip [WatchTermination.scala](/docs/src/test/scala/docs/stream/operators/sink/WatchTermination.scala) { #watchTermination }

Java
:   @@snip [WatchTermination.java](/docs/src/test/java/jdocs/stream/operators/sink/WatchTermination.java) { #watchTermination }

## Reactive Streams semantics

@@@div { .callout }

**completes** when upstream completes

**backpressures** when the wrapped sink backpressures

**cancels** when the wrapped sink cancels

@@@
