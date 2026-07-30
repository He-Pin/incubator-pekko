/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.stream.javadsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pekko.Done;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.StreamTest;
import org.apache.pekko.testkit.PekkoJUnitActorSystemResource;
import org.apache.pekko.testkit.PekkoSpec;
import org.junit.ClassRule;
import org.junit.Test;

public class SinkWatchTerminationTest extends StreamTest {
  public SinkWatchTerminationTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static PekkoJUnitActorSystemResource actorSystemResource =
      new PekkoJUnitActorSystemResource("SinkWatchTerminationTest", PekkoSpec.testConf());

  @Test
  public void mustCompleteFutureWhenStreamCompletes()
      throws InterruptedException, ExecutionException, TimeoutException {
    CompletionStage<Done> future =
        Source.from(Arrays.asList(1, 2, 3, 4))
            .runWith(Sink.watchTermination(Sink.ignore(), (mat, cs) -> cs), system);
    assertEquals(Done.done(), future.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustPreserveOriginalMaterializedValue()
      throws InterruptedException, ExecutionException, TimeoutException {
    Pair<CompletionStage<Integer>, CompletionStage<Done>> result =
        Source.from(Arrays.asList(1, 2, 3))
            .toMat(Sink.watchTermination(Sink.<Integer>head(), Pair::create), Keep.right())
            .run(system);
    assertEquals(Integer.valueOf(1), result.first().toCompletableFuture().get(3, TimeUnit.SECONDS));
    assertEquals(Done.done(), result.second().toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustCompleteFutureWhenDownstreamCancels()
      throws InterruptedException, ExecutionException, TimeoutException {
    // Sink.head cancels after the first element, so the termination future completes with Done
    CompletionStage<Done> future =
        Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
            .runWith(Sink.watchTermination(Sink.<Integer>head(), (mat, cs) -> cs), system);
    assertEquals(Done.done(), future.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustFailFutureWhenUpstreamFails() throws InterruptedException, TimeoutException {
    RuntimeException ex = new RuntimeException("Stream failed.");
    CompletionStage<Done> future =
        Source.<Integer>failed(ex)
            .runWith(Sink.watchTermination(Sink.ignore(), (mat, cs) -> cs), system);
    try {
      future.toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertTrue("Should have thrown", false);
    } catch (ExecutionException e) {
      assertEquals(ex, e.getCause());
    }
  }

  @Test
  public void mustCompleteFutureForEmptyStream()
      throws InterruptedException, ExecutionException, TimeoutException {
    CompletionStage<Done> future =
        Source.<Integer>empty()
            .runWith(Sink.watchTermination(Sink.ignore(), (mat, cs) -> cs), system);
    assertEquals(Done.done(), future.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustWorkWithSinkSeq()
      throws InterruptedException, ExecutionException, TimeoutException {
    Pair<CompletionStage<List<Integer>>, CompletionStage<Done>> result =
        Source.from(Arrays.asList(1, 2, 3, 4, 5))
            .toMat(Sink.watchTermination(Sink.seq(), Pair::create), Keep.right())
            .run(system);
    List<Integer> seq = result.first().toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(Arrays.asList(1, 2, 3, 4, 5), seq);
    assertEquals(Done.done(), result.second().toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustWorkWithSinkFold()
      throws InterruptedException, ExecutionException, TimeoutException {
    Pair<CompletionStage<Integer>, CompletionStage<Done>> result =
        Source.from(Arrays.asList(1, 2, 3, 4, 5))
            .toMat(
                Sink.watchTermination(Sink.fold(0, (a, b) -> (int) a + (int) b), Pair::create),
                Keep.right())
            .run(system);
    assertEquals(
        Integer.valueOf(15), result.first().toCompletableFuture().get(3, TimeUnit.SECONDS));
    assertEquals(Done.done(), result.second().toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustWorkWithSingleElement()
      throws InterruptedException, ExecutionException, TimeoutException {
    CompletionStage<Done> future =
        Source.single(42).runWith(Sink.watchTermination(Sink.ignore(), (mat, cs) -> cs), system);
    assertEquals(Done.done(), future.toCompletableFuture().get(3, TimeUnit.SECONDS));
  }

  @Test
  public void mustFailFutureWhenWrappedSinkFails() throws InterruptedException, TimeoutException {
    RuntimeException ex = new RuntimeException("Sink failed.");
    Sink<Integer, CompletionStage<Done>> failingSink =
        Sink.foreach(
            n -> {
              if ((int) n == 3) throw ex;
            });
    Pair<CompletionStage<Done>, CompletionStage<Done>> result =
        Source.from(Arrays.asList(1, 2, 3, 4, 5))
            .toMat(Sink.watchTermination(failingSink, Pair::create), Keep.right())
            .run(system);
    try {
      result.second().toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertTrue("Should have thrown", false);
    } catch (ExecutionException e) {
      assertEquals(ex, e.getCause());
    }
  }

  @Test
  public void mustFailFutureWhenAbruptlyTerminated() throws InterruptedException, TimeoutException {
    org.apache.pekko.stream.Materializer mat =
        org.apache.pekko.stream.Materializer.createMaterializer(system);
    CompletionStage<Done> future =
        Source.<Integer>maybe().runWith(Sink.watchTermination(Sink.ignore(), (m, cs) -> cs), mat);
    mat.shutdown();
    try {
      future.toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertTrue("Should have thrown", false);
    } catch (ExecutionException e) {
      // The future should fail with either AbruptTerminationException (materializer shutdown)
      // or AbruptStageTerminationException (stage-level abrupt stop)
      String exceptionName = e.getCause().getClass().getName();
      assertTrue(
          "Expected abrupt termination exception but got: " + exceptionName,
          exceptionName.contains("AbruptTermination") || exceptionName.contains("Abrupt"));
    }
  }
}
